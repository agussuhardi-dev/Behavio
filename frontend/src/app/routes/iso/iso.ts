import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatPaginatorModule, PageEvent } from '@angular/material/paginator';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  IsoAccount,
  IsoApi,
  IsoCard,
  IsoLog,
  IsoSimulator,
  SpecProfileDetail,
  SpecProfileSummary,
  TraceResult,
} from '../../core/api/iso-api';
import { PublicHost } from '../../core/api/public-host';
import { ConfirmDialog } from '../../shared/components/confirm-dialog/confirm-dialog';
import { LocalStorageService } from '../../shared/services/storage.service';

/** Baris Live View, dengan penanda buka/tutup untuk melihat hex penuh. */
interface LogRow extends IsoLog {
  open: boolean;
}

/**
 * Halaman simulator ISO-8583.
 *
 * <p>Kosakatanya sengaja BERBEDA dari halaman bank/QRIS: tak ada URL endpoint, contoh
 * curl, maupun export OpenAPI — ISO-8583 tak mengenal semua itu. Yang ada: profil spec
 * (kamus DE), operasi ber-MTI, dan pesan dalam hex.
 */
@Component({
  selector: 'app-iso',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule, MatCardModule, MatDialogModule, MatDividerModule,
    MatExpansionModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule,
    MatPaginatorModule, MatProgressBarModule, MatSelectModule, MatTabsModule, MatTooltipModule,
  ],
  templateUrl: './iso.html',
  styleUrl: './iso.scss',
})
export class Iso implements OnInit, OnDestroy {
  private static readonly LAST_SIM_KEY = 'behavio.iso.lastSimId';

  readonly api = inject(IsoApi);
  private readonly dialog = inject(MatDialog);
  private readonly storage = inject(LocalStorageService);
  private readonly publicHost = inject(PublicHost);

  readonly copiedKey = signal<string | null>(null);

  readonly loading = signal(false);
  readonly msg = signal('');
  readonly err = signal('');

  // simulator
  readonly sims = signal<IsoSimulator[]>([]);
  readonly selectedSimId = signal('');

  // profil spec
  readonly profiles = signal<SpecProfileSummary[]>([]);
  readonly profileDetail = signal<SpecProfileDetail | null>(null);
  readonly supportedClasses = signal<string[]>([]);

  // form buat simulator
  readonly newName = signal('Host ISO');
  readonly newPort = signal(9201);
  readonly newProfile = signal('');

  // unggah profil
  readonly upName = signal('');
  readonly upVersion = signal('1.0');
  /**
   * Bawaan: mewarisi profil Shinhan dan TIDAK menulis ulang operasinya.
   *
   * <p>Dulu isian ini terisi tiga operasi contoh, dan siapa pun yang menekan Unggah tanpa
   * menyentuhnya mendapat profil yang hanya mengenal tiga transaksi — sisanya dibalas
   * DE39=30 tanpa petunjuk. Mewarisi jauh lebih aman: berkas packager memang tak memuat
   * operasi, jadi induknyalah sumber yang benar.
   */
  readonly upParent = signal(Iso.DEFAULT_PROFILE);
  readonly upOperations = signal('');
  readonly upContent = signal('');
  readonly upFormat = signal<'XML' | 'JSON'>('XML');

  // uji trace
  readonly traceHex = signal('');
  readonly traceResult = signal<TraceResult | null>(null);

  // scenario
  readonly operations = signal<string[]>([]);
  readonly scenarioNames = signal<Record<string, string[]>>({});
  readonly activeScenarios = signal<Record<string, string>>({});
  readonly editingOp = signal('');
  readonly editorText = signal('');
  readonly editorSaved = signal(false);

  // state & log
  readonly accounts = signal<IsoAccount[]>([]);
  readonly cards = signal<IsoCard[]>([]);

  // form rekening & kartu
  readonly accNo = signal('');
  readonly accName = signal('');
  readonly accBalance = signal('1000000.00');
  readonly accPhone = signal('');
  readonly cardPan = signal('');
  readonly cardAccNo = signal('');
  readonly logs = signal<LogRow[]>([]);

  /** Tab aktif — menentukan data apa yang dimuat saat tab dibuka. */
  readonly activeTab = signal('');

  /**
   * Live View memakai <b>SSE</b>, sama seperti bank simulator — bukan polling dan bukan
   * tombol muat ulang. Pesan ISO datang lewat socket kapan saja, dan justru pesan pertama
   * sesudah klien menyambung yang paling ingin dilihat; polling membuatnya telat satu
   * interval, dan "live" yang harus diklik dulu bukanlah live.
   */
  private es?: EventSource;
  readonly liveConnected = signal(false);

  /**
   * Paging riwayat. Pesan baru dari SSE hanya disisipkan saat berada di halaman 1 —
   * kalau tidak, isi halaman yang sedang dibaca akan bergeser sendiri, persis gangguan
   * yang membuat polling dulu ditinggalkan. Di halaman lain jumlahnya dihitung sebagai
   * lencana "pesan baru".
   */
  readonly logTotal = signal(0);
  readonly logPageIndex = signal(0);
  readonly logPageSize = signal(20);
  readonly livePending = signal(0);

  // Dicocokkan dengan LABEL tab, bukan indeks: indeks bergeser diam-diam begitu ada tab
  // baru disisipkan, dan pemuatan akan menempel di tab yang salah tanpa gejala apa pun.
  /** Profil bawaan — memuat seluruh operasi Shinhan, dipilih otomatis saat membuat simulator. */
  private static readonly DEFAULT_PROFILE = 'shinhan-default';
  private static readonly TAB_ACCOUNTS = 'Rekening & Kartu';
  private static readonly TAB_LIVE = 'Live View';

  get selectedSim(): IsoSimulator | undefined {
    return this.sims().find(s => s.id === this.selectedSimId());
  }

  ngOnInit() {
    this.publicHost.load();
    this.reload();
  }

  ngOnDestroy() {
    this.disconnectLive();
  }

  private connectLive() {
    const id = this.selectedSimId();
    this.disconnectLive();
    if (!id) {
      return;
    }
    const es = new EventSource(this.api.streamUrl(id));
    es.addEventListener('open', () => this.liveConnected.set(true));
    es.addEventListener('exchange', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        const row: LogRow = {
          mti: d.mti, operation: d.operation, response_code: d.responseCode,
          request_hex: d.requestHex, response_hex: d.responseHex,
          duration_ms: d.durationMillis, error: d.error,
          created_at: new Date().toISOString(), open: false,
        };
        this.logTotal.update(n => n + 1);
        if (this.logPageIndex() === 0) {
          // Halaman 1 = "terbaru", jadi sisipkan di atas dan potong sesuai ukuran halaman.
          this.logs.update(list => [row, ...list].slice(0, this.logPageSize()));
        } else {
          this.livePending.update(n => n + 1);
        }
      } catch { /* payload tak terbaca — abaikan, jangan jatuhkan stream */ }
    });
    es.onerror = () => this.liveConnected.set(false);
    this.es = es;
  }

  private disconnectLive() {
    this.es?.close();
    this.es = undefined;
    this.liveConnected.set(false);
  }

  /**
   * Data dimuat saat tabnya dibuka. Live View tak butuh ini — ia menerima dorongan lewat
   * SSE begitu simulator dipilih, jadi pesan yang datang saat tab lain terbuka pun tetap
   * tercatat.
   */
  onTabChange(label: string) {
    this.activeTab.set(label);
    if (!this.selectedSimId()) {
      return;
    }
    if (label === Iso.TAB_ACCOUNTS) {
      this.loadAccounts();
    }
  }

  // ── info koneksi (untuk disalin ke klien) ───────────────────────────────

  /**
   * Host yang dipakai KLIEN untuk menyambung — dari `DEPLOY_HOST`/`X-Forwarded-Host`,
   * bukan `localhost` yang dihardcode. Alamat loopback hanya berguna di mesin yang
   * menjalankan Behavio, padahal nilai ini justru untuk ditempel ke klien di mesin lain.
   */
  host(): string {
    return this.publicHost.host();
  }

  /** Alamat TCP siap tempel. ISO-8583 tak punya URL — yang ada host:port. */
  address(): string {
    const sim = this.selectedSim;
    return sim ? `${this.host()}:${sim.port}` : '';
  }

  /** Parameter yang HARUS cocok di sisi klien; kalau beda, seluruh aliran byte bergeser. */
  connectionRows(): { label: string; value: string; key: string; note?: string }[] {
    const sim = this.selectedSim;
    if (!sim) return [];
    const d = this.profileDetail();
    const rows: { label: string; value: string; key: string; note?: string }[] = [
      { label: 'Host', value: this.host(), key: 'host' },
      { label: 'Port TCP', value: String(sim.port), key: 'port' },
      { label: 'Alamat', value: this.address(), key: 'addr' },
      { label: 'Profil spec', value: `${sim.specProfileName}:${sim.specProfileVersion}`, key: 'spec' },
    ];
    if (d) {
      rows.push(
        { label: 'Panjang header', value: `${d.transport.lengthPrefixBytes} byte`, key: 'lenb',
          note: 'penanda panjang di depan tiap pesan' },
        { label: 'Encoding header', value: d.transport.lengthPrefixEncoding, key: 'lene' },
        { label: 'Charset field', value: d.transport.charset, key: 'cs' },
        { label: 'Bitmap', value: d.transport.bitmap, key: 'bm' }
      );
    }
    return rows;
  }

  /** Cuplikan siap jalan — menghemat waktu menyusun framing yang benar. */
  pythonSnippet(): string {
    const sim = this.selectedSim;
    if (!sim) return '';
    const d = this.profileDetail();
    const bytes = d ? d.transport.lengthPrefixBytes : 2;
    const fmt = bytes === 2 ? '">H"' : '">I"';
    return [
      'import socket, struct',
      '',
      `s = socket.create_connection(("${this.host()}", ${sim.port}), timeout=5)`,
      '',
      '# echo / network management (0800)',
      '# bitmap 0020…  = hanya DE11 (STAN) yang dikirim.',
      '# DE70 sengaja TIDAK disertakan: ia ada di DE65-128 sehingga butuh bitmap',
      '# SEKUNDER — menyalakannya di bitmap primer membuat panjang pesan tak cocok',
      '# dan host mengabaikannya (klien lalu menggantung sampai timeout).',
      'msg = b"0800" + bytes.fromhex("0020000000000000") + b"000001"',
      `s.sendall(struct.pack(${fmt}, len(msg)) + msg)`,
      '',
      `n = struct.unpack(${fmt}, s.recv(${bytes}))[0]`,
      'print(s.recv(n))',
    ].join('\n');
  }

  copy(text: string, key: string) {
    navigator.clipboard?.writeText(text).then(() => {
      this.copiedKey.set(key);
      setTimeout(() => this.copiedKey.set(null), 1500);
    });
  }

  // ── pemuatan ────────────────────────────────────────────────────────────

  reload() {
    this.loading.set(true);
    this.api.listProfiles().subscribe({
      next: p => {
        this.profiles.set(p);
        if (!this.newProfile() && p.length) {
          // Profil bawaan diutamakan: ia memuat SEMUA operasi Shinhan, sementara profil
          // pertama dalam daftar bisa saja baseline generik yang hanya sebagian.
          const bawaan = p.find(x => x.name === Iso.DEFAULT_PROFILE);
          const pilih = bawaan ?? p[0];
          this.newProfile.set(`${pilih.name}:${pilih.version}`);
        }
      },
      error: () => this.profiles.set([]),
    });
    this.api.supportedPackagerClasses().subscribe({
      next: r => this.supportedClasses.set(r.supported ?? []),
      error: () => this.supportedClasses.set([]),
    });
    this.api.list().subscribe({
      next: list => {
        this.sims.set(list);
        const remembered = this.rememberedSimId();
        const pick = list.find(s => s.id === remembered) ?? list[0];
        this.selectSim(pick ? pick.id : '');
        this.loading.set(false);
      },
      error: () => {
        this.sims.set([]);
        this.loading.set(false);
      },
    });
  }

  selectSim(id: string) {
    this.selectedSimId.set(id);
    if (!id) {
      this.operations.set([]);
      this.accounts.set([]);
      this.logs.set([]);
      return;
    }
    this.rememberSim(id);
    this.loadOperations();
    this.loadAccounts();
    this.loadLogs();
    // Stream mengikuti simulator yang sedang dipilih, bukan yang lama.
    this.connectLive();
    this.onTabChange(this.activeTab());
  }

  /** Operasi datang dari PROFIL SPEC simulator, bukan daftar tetap di frontend. */
  private loadOperations() {
    const sim = this.selectedSim;
    if (!sim) return;
    this.api.profileDetail(sim.specProfileName, sim.specProfileVersion).subscribe({
      next: d => {
        this.profileDetail.set(d);
        const ops = d.operations.map(o => o.name);
        this.operations.set(ops);
        ops.forEach(op => this.loadScenario(op));
      },
      error: () => this.operations.set([]),
    });
  }

  private loadScenario(operation: string) {
    const id = this.selectedSimId();
    this.api.scenarioNames(id, operation).subscribe({
      next: names => this.scenarioNames.update(m => ({ ...m, [operation]: names })),
    });
    this.api.activeScenario(id, operation).subscribe({
      next: r => this.activeScenarios.update(m => ({ ...m, [operation]: r.name })),
    });
  }

  loadAccounts() {
    this.api.accounts(this.selectedSimId()).subscribe({
      next: a => this.accounts.set(a),
      error: () => this.accounts.set([]),
    });
    this.api.cards(this.selectedSimId()).subscribe({
      next: c => this.cards.set(c),
      error: () => this.cards.set([]),
    });
  }

  /**
   * Rekening menyimpan saldo; KARTU memetakan PAN → rekening. Klien ISO mengirim PAN
   * (DE2), jadi rekening tanpa kartu tetap dibalas DE39=14 — karena itu keduanya
   * disediakan berdampingan di sini.
   */
  addAccount() {
    if (!this.accNo().trim()) {
      this.err.set('Nomor rekening wajib diisi.');
      return;
    }
    this.api
      .addAccount(this.selectedSimId(), this.accNo().trim(), this.accName().trim() || 'Nasabah',
        this.accBalance().trim() || '0', this.accPhone().trim())
      .subscribe({
        next: () => {
          this.msg.set(`Rekening ${this.accNo()} dibuat.`);
          this.err.set('');
          // Nomor rekening diisikan ke form kartu: langkah berikutnya hampir selalu
          // membuatkan kartunya, dan mengetik ulang nomor itu mudah salah.
          this.cardAccNo.set(this.accNo().trim());
          this.accNo.set('');
          this.accName.set('');
          this.accPhone.set('');
          this.loadAccounts();
        },
        error: e => this.err.set(e?.error?.error ?? 'Gagal membuat rekening.'),
      });
  }

  addCard() {
    if (!this.cardPan().trim() || !this.cardAccNo().trim()) {
      this.err.set('PAN dan nomor rekening wajib diisi.');
      return;
    }
    this.api.addCard(this.selectedSimId(), this.cardPan().trim(), this.cardAccNo().trim()).subscribe({
      next: () => {
        this.msg.set(`Kartu ${this.cardPan()} ditautkan ke rekening ${this.cardAccNo()}.`);
        this.err.set('');
        this.cardPan.set('');
        this.loadAccounts();
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal membuat kartu.'),
    });
  }

  removeAccount(a: IsoAccount) {
    this.confirm('Hapus rekening?', `Rekening ${a.accountNo} (${a.holderName}) akan dihapus.`,
      () => this.api.deleteAccount(this.selectedSimId(), a.accountNo).subscribe({
        next: () => { this.msg.set('Rekening dihapus.'); this.loadAccounts(); },
      }));
  }

  removeCard(c: IsoCard) {
    this.confirm('Hapus kartu?', `Kartu ${c.pan} akan dihapus.`,
      () => this.api.deleteCard(this.selectedSimId(), c.pan).subscribe({
        next: () => { this.msg.set('Kartu dihapus.'); this.loadAccounts(); },
      }));
  }

  /** Kartu yang menunjuk rekening ini — dipakai memberi tanda "belum punya kartu". */
  cardsFor(accountNo: string): IsoCard[] {
    return this.cards().filter(c => c.accountNo === accountNo);
  }

  loadLogs() {
    const id = this.selectedSimId();
    if (!id) {
      this.logs.set([]);
      this.logTotal.set(0);
      return;
    }
    this.api.logs(id, this.logPageSize(), this.logPageIndex() * this.logPageSize()).subscribe({
      next: p => {
        this.logs.set(p.rows.map(x => ({ ...x, open: false })));
        this.logTotal.set(p.total);
        this.livePending.set(0);
      },
      error: () => {
        this.logs.set([]);
        this.logTotal.set(0);
      },
    });
  }

  onLogPage(e: PageEvent) {
    this.logPageIndex.set(e.pageIndex);
    this.logPageSize.set(e.pageSize);
    this.loadLogs();
  }

  /** Kembali ke halaman 1 dan tarik ulang — dipakai lencana "pesan baru". */
  gotoLatestLogs() {
    this.logPageIndex.set(0);
    this.loadLogs();
  }

  /**
   * Menghapus riwayat di DATABASE, bukan sekadar mengosongkan tampilan — itulah yang
   * dimaksud saat orang menekan "bersihkan" setelah sesi uji yang berantakan.
   */
  clearLogs() {
    const sim = this.selectedSim;
    if (!sim) {
      return;
    }
    this.confirm('Hapus riwayat pesan?',
      `Seluruh riwayat pesan ${sim.name} akan dihapus dari database dan tak bisa dikembalikan.`
      + ' Rekening, kartu, dan profil tidak tersentuh.',
      () => this.api.clearLogs(sim.id).subscribe({
        next: r => {
          this.msg.set(`${r.deleted} baris riwayat dihapus.`);
          this.err.set('');
          this.logPageIndex.set(0);
          this.loadLogs();
        },
        error: e => this.err.set(e?.error?.error ?? 'Gagal menghapus riwayat.'),
      }));
  }

  // ── simulator ───────────────────────────────────────────────────────────

  createSim() {
    const [name, version] = this.newProfile().split(':');
    if (!name) {
      this.err.set('Pilih profil spec dulu.');
      return;
    }
    this.api.create(this.newName(), Number(this.newPort()), name, version).subscribe({
      next: s => {
        this.msg.set(`Simulator "${s.name}" dibuat di TCP :${s.port}.`);
        this.err.set('');
        this.reload();
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal membuat simulator.'),
    });
  }

  start() {
    this.api.start(this.selectedSimId()).subscribe({
      next: s => {
        this.msg.set(`Mendengarkan di TCP :${s.port}.`);
        this.reload();
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal start.'),
    });
  }

  stop() {
    this.api.stop(this.selectedSimId()).subscribe({
      next: () => {
        this.msg.set('Simulator dihentikan.');
        this.reload();
      },
    });
  }

  removeSim() {
    const sim = this.selectedSim;
    if (!sim) return;
    this.confirm('Hapus simulator?',
      `"${sim.name}" beserta rekening, kartu, dan lognya akan dihapus permanen.`,
      () => this.api.remove(sim.id).subscribe({
        next: () => {
          this.msg.set('Simulator dihapus.');
          this.selectedSimId.set('');
          this.reload();
        },
      }));
  }

  seedDemo() {
    this.api.seedDemo(this.selectedSimId()).subscribe({
      next: () => {
        this.msg.set('Data contoh ditambahkan (2 rekening + 1 kartu).');
        this.loadAccounts();
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal menambah data contoh.'),
    });
  }

  // ── profil spec ─────────────────────────────────────────────────────────

  upload() {
    const done = (id: string) => {
      this.msg.set(`Profil "${this.upName()}" v${this.upVersion()} tersimpan.`);
      this.err.set('');
      this.upContent.set('');
      this.reload();
    };
    const fail = (e: any) => this.err.set(e?.error?.error ?? 'Unggahan ditolak.');

    if (this.upFormat() === 'JSON') {
      this.api.uploadJson(this.upContent()).subscribe({ next: r => done(r.id), error: fail });
      return;
    }
    const ops = this.upOperations().split(',').map(s => s.trim()).filter(Boolean);
    this.api
      .uploadXml(this.upContent(), this.upName(), this.upVersion(), this.upParent(), ops)
      .subscribe({ next: r => done(r.id), error: fail });
  }

  showProfile(p: SpecProfileSummary) {
    this.api.profileDetail(p.name, p.version).subscribe({
      next: d => this.profileDetail.set(d),
      error: e => this.err.set(e?.error?.error ?? 'Gagal memuat profil.'),
    });
  }

  /**
   * Mengalihkan simulator aktif ke profil spec lain — cara memperbaiki ketidakcocokan
   * seperti bitmap HEX vs BINER tanpa membuat ulang simulator (rekening, kartu, dan
   * riwayat pesannya tetap utuh).
   */
  switchProfile(p: SpecProfileSummary) {
    const sim = this.selectedSim;
    if (!sim) {
      return;
    }
    const jalan = sim.status === 'RUNNING';
    this.confirm('Ganti profil spec?',
      `${sim.name} akan memakai ${p.name} v${p.version}.` +
      (jalan ? ' Simulator sedang berjalan, jadi akan di-start ulang — koneksi TCP yang'
             + ' terbuka akan terputus.' : ''),
      () => this.api.switchProfile(sim.id, p.name, p.version).subscribe({
        next: () => {
          this.msg.set(`${sim.name} kini memakai ${p.name} v${p.version}.`);
          this.err.set('');
          this.reload();
        },
        error: e => this.err.set(e?.error?.error ?? 'Gagal mengganti profil.'),
      }));
  }

  /**
   * Menghapus satu versi profil. Backend menolak (409) bila masih dipakai dan menyebutkan
   * pemakainya — pesan itu ditampilkan apa adanya, karena justru itu yang perlu ditindak.
   */
  removeProfile(p: SpecProfileSummary) {
    this.confirm('Hapus profil spec?',
      `Profil ${p.name} v${p.version} akan dihapus. Simulator yang menunjuknya tak akan bisa start.`,
      () => this.api.deleteProfile(p.name, p.version).subscribe({
        next: () => {
          this.msg.set(`Profil ${p.name} v${p.version} dihapus.`);
          this.err.set('');
          if (this.profileDetail()) {
            this.profileDetail.set(null);
          }
          this.reload();
        },
        error: e => this.err.set(e?.error?.error ?? 'Gagal menghapus profil.'),
      }));
  }

  /**
   * Uji trace — cara membuktikan profil tanpa dokumen spec: kalau trace nyata ter-parse
   * bersih, profilnya benar; kalau tidak, errornya menunjuk DE yang bermasalah.
   */
  runTrace() {
    const sim = this.selectedSim;
    const p = this.profiles();
    const name = sim ? sim.specProfileName : p[0]?.name;
    const version = sim ? sim.specProfileVersion : p[0]?.version;
    if (!name) {
      this.err.set('Belum ada profil spec.');
      return;
    }
    this.api.testTrace(name, version, this.traceHex()).subscribe({
      next: r => this.traceResult.set(r),
      error: e => this.err.set(e?.error?.error ?? 'Uji trace gagal.'),
    });
  }

  traceFields(r: TraceResult): { key: string; value: string }[] {
    return Object.entries(r.fields ?? {}).map(([key, value]) => ({ key, value }));
  }

  // ── scenario ────────────────────────────────────────────────────────────

  activeFor(operation: string): string {
    return this.activeScenarios()[operation] ?? 'Normal';
  }

  namesFor(operation: string): string[] {
    return this.scenarioNames()[operation] ?? [];
  }

  changeScenario(operation: string, name: string) {
    this.api.setActiveScenario(this.selectedSimId(), operation, name).subscribe({
      next: () => {
        this.activeScenarios.update(m => ({ ...m, [operation]: name }));
        this.msg.set(`Scenario "${name}" aktif untuk ${operation}.`);
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal mengubah scenario.'),
    });
  }

  openEditor(operation: string) {
    if (this.editingOp() === operation) {
      this.editingOp.set('');
      return;
    }
    this.editorSaved.set(false);
    this.api.scenarioDefinition(this.selectedSimId(), operation, this.activeFor(operation)).subscribe({
      next: json => {
        this.editorText.set(this.pretty(json));
        this.editingOp.set(operation);
      },
      error: e => this.err.set(e?.error?.error ?? 'Gagal memuat definisi.'),
    });
  }

  saveEditor(operation: string) {
    this.api
      .saveScenarioDefinition(this.selectedSimId(), operation, this.activeFor(operation), this.editorText())
      .subscribe({
        next: () => {
          this.editorSaved.set(true);
          this.msg.set('Definisi tersimpan — pesan berikutnya memakai ini.');
        },
        error: e => this.err.set(e?.error?.error ?? 'Definisi ditolak.'),
      });
  }

  resetEditor(operation: string) {
    this.api
      .resetScenarioDefinition(this.selectedSimId(), operation, this.activeFor(operation))
      .subscribe({
        next: () => {
          this.msg.set('Dikembalikan ke bawaan.');
          this.openEditor(operation);
          this.editingOp.set(operation);
        },
      });
  }

  // ── util ────────────────────────────────────────────────────────────────

  toggleLog(row: LogRow) {
    this.logs.update(list => list.map(r => (r === row ? { ...r, open: !r.open } : r)));
  }

  /** Hex dari log bisa langsung dipakai menguji profil — alur debugging jadi utuh. */
  useLogAsTrace(row: LogRow) {
    this.traceHex.set(row.request_hex);
    this.runTrace();
  }

  private pretty(json: string): string {
    try {
      return JSON.stringify(JSON.parse(json), null, 2);
    } catch {
      return json;
    }
  }

  private confirm(title: string, message: string, onOk: () => void) {
    this.dialog
      .open(ConfirmDialog, { data: { title, message, confirmText: 'Hapus', danger: true } })
      .afterClosed()
      .subscribe(ok => {
        if (ok) onOk();
      });
  }

  private rememberSim(id: string) {
    if (id) this.storage.set(Iso.LAST_SIM_KEY, id);
  }

  /** LocalStorageService.get() balikin {} bila key belum ada — jadi cek tipenya. */
  private rememberedSimId(): string {
    const v = this.storage.get(Iso.LAST_SIM_KEY);
    return typeof v === 'string' ? v : '';
  }
}
