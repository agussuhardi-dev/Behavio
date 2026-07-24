import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { ClipboardService } from '../../shared/services/clipboard.service';
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
import { TranslatePipe, TranslateService } from '@ngx-translate/core';

import {
  ISO_SCENARIOS,
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
    TranslatePipe,
  ],
  templateUrl: './iso.html',
  styleUrl: './iso.scss',
})
export class Iso implements OnInit, OnDestroy {
  private static readonly LAST_SIM_KEY = 'behavio.iso.lastSimId';
  private static readonly LAST_TAB_KEY = 'behavio.iso.lastTab';

  /**
   * Urutan tab — disimpan sebagai ID stabil, bukan label maupun indeks.
   *
   * <p>Label kini diterjemahkan (EN/ID), jadi tak bisa lagi jadi kunci logika: teks tab
   * berubah saat bahasa berganti. ID ini tetap; indeks bergeser diam-diam begitu ada tab
   * baru disisipkan. Kalau suatu tab dihapus, ID-nya sekadar tak ketemu dan pilihan jatuh
   * ke tab pertama.
   */
  private static readonly TAB_IDS = [
    'operations', 'connection', 'profiles', 'trace',
    'accounts', 'live', 'create',
  ];

  private readonly clipboard = inject(ClipboardService);
  private readonly translate = inject(TranslateService);

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
  /** Katalog scenario bawaan — ditampilkan sebagai panduan di bawah halaman. */
  readonly isoScenarios = ISO_SCENARIOS;

  readonly operations = signal<string[]>([]);
  /** Operasi + processing code-nya — dipakai panel "informasi request" di tab Rekening. */
  readonly operationRoutes = signal<{ name: string; mti: string; processingCode: string }[]>([]);
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

  // Dicocokkan dengan ID tab, bukan indeks: indeks bergeser diam-diam begitu ada tab
  // baru disisipkan, dan pemuatan akan menempel di tab yang salah tanpa gejala apa pun.
  /** Profil bawaan — memuat seluruh operasi Shinhan, dipilih otomatis saat membuat simulator. */
  private static readonly DEFAULT_PROFILE = 'shinhan-default';
  private static readonly TAB_ACCOUNTS = 'accounts';
  private static readonly TAB_LIVE = 'live';

  get selectedSim(): IsoSimulator | undefined {
    return this.sims().find(s => s.id === this.selectedSimId());
  }

  /** Indeks tab yang dipulihkan dari kunjungan terakhir; 0 bila belum ada/tak dikenal. */
  readonly tabIndex = signal(0);

  ngOnInit() {
    const id = this.rememberedTab();
    const i = Iso.TAB_IDS.indexOf(id);
    if (i >= 0) {
      this.tabIndex.set(i);
      // activeTab diisi SEKARANG juga: reload() memakainya untuk memutuskan data apa yang
      // perlu dimuat, dan ia berjalan sebelum mat-tab-group sempat memancarkan event.
      this.activeTab.set(id);
    }
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
          mti: d.mti, processing_code: d.processingCode,
          operation: d.operation, response_code: d.responseCode,
          request_hex: d.requestHex, response_hex: d.responseHex,
          duration_ms: d.durationMillis, error: d.error,
          created_at: new Date().toISOString(), open: false,
        };
        // Pesan yang masuk BISA mengubah state: saldo, PIN, nomor telepon. Tanpa ini
        // tab Rekening & Kartu tetap menampilkan nilai lama sampai tabnya dibuka ulang —
        // PIN yang baru saja diubah masih tertulis "belum", dan itu tampak seperti
        // operasinya gagal padahal berhasil.
        this.scheduleStateRefresh();
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
    if (this.stateRefreshTimer) {
      clearTimeout(this.stateRefreshTimer);
      this.stateRefreshTimer = undefined;
    }
  }

  private stateRefreshTimer?: ReturnType<typeof setTimeout>;

  /**
   * Muat ulang rekening & kartu setelah ada lalu lintas, dengan jeda pendek.
   *
   * <p>Jedanya disengaja: satu klien bisa mengirim puluhan pesan beruntun, dan memuat
   * ulang di setiap pesan berarti puluhan request yang hasilnya langsung ditimpa. Jeda
   * ini menggabungkannya jadi satu.
   */
  private scheduleStateRefresh() {
    if (this.stateRefreshTimer) {
      return;
    }
    this.stateRefreshTimer = setTimeout(() => {
      this.stateRefreshTimer = undefined;
      if (this.selectedSimId()) {
        this.loadAccounts();
      }
    }, 600);
  }

  /**
   * Data dimuat saat tabnya dibuka. Live View tak butuh ini — ia menerima dorongan lewat
   * SSE begitu simulator dipilih, jadi pesan yang datang saat tab lain terbuka pun tetap
   * tercatat.
   */
  onTabChange(index: number) {
    const id = Iso.TAB_IDS[index] ?? Iso.TAB_IDS[0];
    this.activeTab.set(id);
    this.tabIndex.set(Math.max(0, index));
    this.storage.set(Iso.LAST_TAB_KEY, id);
    if (!this.selectedSimId()) {
      return;
    }
    if (id === Iso.TAB_ACCOUNTS) {
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
    const t = (k: string) => this.translate.instant(k);
    const rows: { label: string; value: string; key: string; note?: string }[] = [
      { label: 'Host', value: this.host(), key: 'host' },
      { label: t('iso.port_tcp'), value: String(sim.port), key: 'port' },
      { label: t('iso.address'), value: this.address(), key: 'addr' },
      { label: t('iso.conn_spec'), value: `${sim.specProfileName}:${sim.specProfileVersion}`, key: 'spec' },
    ];
    if (d) {
      rows.push(
        { label: t('iso.conn_len_header'), value: `${d.transport.lengthPrefixBytes} byte`, key: 'lenb',
          note: t('iso.conn_len_note') },
        { label: t('iso.conn_enc_header'), value: d.transport.lengthPrefixEncoding, key: 'lene' },
        { label: t('iso.conn_charset'), value: d.transport.charset, key: 'cs' },
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
    // Lewat ClipboardService, BUKAN navigator.clipboard langsung: API itu undefined di
    // halaman non-HTTPS (mis. http://<ip>:81), sehingga tombol salin diam-diam tak
    // berfungsi tanpa error apa pun.
    this.clipboard.copy(text).then(ok => {
      if (!ok) {
        this.err.set(this.translate.instant('iso.msg.copy_failed'));
        return;
      }
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
  }

  /** Operasi datang dari PROFIL SPEC simulator, bukan daftar tetap di frontend. */
  private loadOperations() {
    const sim = this.selectedSim;
    if (!sim) return;
    this.api.profileDetail(sim.specProfileName, sim.specProfileVersion).subscribe({
      next: d => {
        this.profileDetail.set(d);
        // Satu operasi bisa punya BEBERAPA rute dengan nama sama — mis. `reversal`
        // menangani MTI 0400 dan 0420 (dua rute, satu operasi logis). Scenario di backend
        // di-key per NAMA operasi, jadi keduanya berbagi satu set scenario. Tanpa dedup di
        // sini, tab Operasi & Scenario me-render panel (beserta dropdown scenario) ganda
        // untuk nama yang sama — persis gejala "scenario dobel" pada profil turunan.
        const ops = [...new Set(d.operations.map(o => o.name))];
        this.operations.set(ops);
        this.operationRoutes.set(d.operations);
        ops.forEach(op => this.loadScenario(op));
      },
      error: () => {
        this.operations.set([]);
        this.operationRoutes.set([]);
      },
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
      this.err.set(this.translate.instant('iso.msg.acc_no_required'));
      return;
    }
    this.api
      .addAccount(this.selectedSimId(), this.accNo().trim(), this.accName().trim() || 'Nasabah',
        this.accBalance().trim() || '0', this.accPhone().trim())
      .subscribe({
        next: () => {
          this.msg.set(this.translate.instant('iso.msg.acc_created', { account: this.accNo() }));
          this.err.set('');
          // Nomor rekening diisikan ke form kartu: langkah berikutnya hampir selalu
          // membuatkan kartunya, dan mengetik ulang nomor itu mudah salah.
          this.cardAccNo.set(this.accNo().trim());
          this.accNo.set('');
          this.accName.set('');
          this.accPhone.set('');
          this.loadAccounts();
        },
        error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.acc_create_failed')),
      });
  }

  addCard() {
    if (!this.cardPan().trim() || !this.cardAccNo().trim()) {
      this.err.set(this.translate.instant('iso.msg.card_fields_required'));
      return;
    }
    this.api.addCard(this.selectedSimId(), this.cardPan().trim(), this.cardAccNo().trim()).subscribe({
      next: () => {
        this.msg.set(this.translate.instant('iso.msg.card_created', { pan: this.cardPan(), account: this.cardAccNo() }));
        this.err.set('');
        this.cardPan.set('');
        this.loadAccounts();
      },
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.card_create_failed')),
    });
  }

  removeAccount(a: IsoAccount) {
    this.confirm(this.translate.instant('iso.msg.delete_account_title'),
      this.translate.instant('iso.msg.delete_account_body', { account: a.accountNo, holder: a.holderName }),
      () => this.api.deleteAccount(this.selectedSimId(), a.accountNo).subscribe({
        next: () => { this.msg.set(this.translate.instant('iso.msg.account_deleted')); this.loadAccounts(); },
      }));
  }

  removeCard(c: IsoCard) {
    this.confirm(this.translate.instant('iso.msg.delete_card_title'),
      this.translate.instant('iso.msg.delete_card_body', { pan: c.pan }),
      () => this.api.deleteCard(this.selectedSimId(), c.pan).subscribe({
        next: () => { this.msg.set(this.translate.instant('iso.msg.card_deleted')); this.loadAccounts(); },
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
    this.confirm(this.translate.instant('iso.msg.clear_history_title'),
      this.translate.instant('iso.msg.clear_history_body', { name: sim.name }),
      () => this.api.clearLogs(sim.id).subscribe({
        next: r => {
          this.msg.set(this.translate.instant('iso.msg.history_cleared', { deleted: r.deleted }));
          this.err.set('');
          this.logPageIndex.set(0);
          this.loadLogs();
        },
        error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.history_clear_failed')),
      }));
  }

  // ── simulator ───────────────────────────────────────────────────────────

  createSim() {
    const [name, version] = this.newProfile().split(':');
    if (!name) {
      this.err.set(this.translate.instant('iso.msg.pick_profile_first'));
      return;
    }
    this.api.create(this.newName(), Number(this.newPort()), name, version).subscribe({
      next: s => {
        this.msg.set(this.translate.instant('iso.msg.sim_created', { name: s.name, port: s.port }));
        this.err.set('');
        this.reload();
      },
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.sim_create_failed')),
    });
  }

  start() {
    this.api.start(this.selectedSimId()).subscribe({
      next: s => {
        this.msg.set(this.translate.instant('iso.msg.listening', { port: s.port }));
        this.reload();
      },
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.start_failed')),
    });
  }

  stop() {
    this.api.stop(this.selectedSimId()).subscribe({
      next: () => {
        this.msg.set(this.translate.instant('iso.msg.sim_stopped'));
        this.reload();
      },
    });
  }

  removeSim() {
    const sim = this.selectedSim;
    if (!sim) return;
    this.confirm(this.translate.instant('iso.msg.delete_sim_title'),
      this.translate.instant('iso.msg.delete_sim_body', { name: sim.name }),
      () => this.api.remove(sim.id).subscribe({
        next: () => {
          this.msg.set(this.translate.instant('iso.msg.sim_deleted'));
          this.selectedSimId.set('');
          this.reload();
        },
      }));
  }

  seedDemo() {
    this.api.seedDemo(this.selectedSimId()).subscribe({
      next: () => {
        this.msg.set(this.translate.instant('iso.msg.seed_done'));
        this.loadAccounts();
      },
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.seed_failed')),
    });
  }

  // ── profil spec ─────────────────────────────────────────────────────────

  upload() {
    const done = (id: string) => {
      this.msg.set(this.translate.instant('iso.msg.profile_saved', { name: this.upName(), version: this.upVersion() }));
      this.err.set('');
      this.upContent.set('');
      this.reload();
    };
    const fail = (e: any) => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.upload_rejected'));

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
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.profile_load_failed')),
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
    this.confirm(this.translate.instant('iso.msg.switch_title'),
      this.translate.instant('iso.msg.switch_body', { name: sim.name, profile: p.name, version: p.version }) +
      (jalan ? this.translate.instant('iso.msg.switch_body_running') : ''),
      () => this.api.switchProfile(sim.id, p.name, p.version).subscribe({
        next: () => {
          this.msg.set(this.translate.instant('iso.msg.switch_done', { name: sim.name, profile: p.name, version: p.version }));
          this.err.set('');
          this.reload();
        },
        error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.switch_failed')),
      }));
  }

  /**
   * Menghapus satu versi profil. Backend menolak (409) bila masih dipakai dan menyebutkan
   * pemakainya — pesan itu ditampilkan apa adanya, karena justru itu yang perlu ditindak.
   */
  removeProfile(p: SpecProfileSummary) {
    this.confirm(this.translate.instant('iso.msg.delete_profile_title'),
      this.translate.instant('iso.msg.delete_profile_body', { profile: p.name, version: p.version }),
      () => this.api.deleteProfile(p.name, p.version).subscribe({
        next: () => {
          this.msg.set(this.translate.instant('iso.msg.profile_deleted', { profile: p.name, version: p.version }));
          this.err.set('');
          if (this.profileDetail()) {
            this.profileDetail.set(null);
          }
          this.reload();
        },
        error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.profile_delete_failed')),
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
      this.err.set(this.translate.instant('iso.msg.no_spec_profile'));
      return;
    }
    this.api.testTrace(name, version, this.traceHex()).subscribe({
      next: r => this.traceResult.set(r),
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.trace_failed')),
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

  /**
   * Kunci i18n untuk label/deskripsi scenario. `name` TETAP identifier ke backend
   * (nilai select & payload) — hanya TAMPILANNYA yang dipetakan ke terjemahan.
   */
  scName(name: string): string { return `scenario.iso.${this.scSlug(name)}.name`; }
  scDesc(name: string): string { return `scenario.iso.${this.scSlug(name)}.desc`; }
  private scSlug(name: string): string {
    return (name || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  }

  changeScenario(operation: string, name: string) {
    this.api.setActiveScenario(this.selectedSimId(), operation, name).subscribe({
      next: () => {
        this.activeScenarios.update(m => ({ ...m, [operation]: name }));
        this.msg.set(this.translate.instant('iso.msg.scenario_active', { scenario: name, operation }));
      },
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.scenario_change_failed')),
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
      error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.def_load_failed')),
    });
  }

  saveEditor(operation: string) {
    this.api
      .saveScenarioDefinition(this.selectedSimId(), operation, this.activeFor(operation), this.editorText())
      .subscribe({
        next: () => {
          this.editorSaved.set(true);
          this.msg.set(this.translate.instant('iso.msg.def_saved'));
        },
        error: e => this.err.set(e?.error?.error ?? this.translate.instant('iso.msg.def_rejected')),
      });
  }

  resetEditor(operation: string) {
    this.api
      .resetScenarioDefinition(this.selectedSimId(), operation, this.activeFor(operation))
      .subscribe({
        next: () => {
          this.msg.set(this.translate.instant('iso.msg.reset_default_done'));
          this.openEditor(operation);
          this.editingOp.set(operation);
        },
      });
  }

  // ── util ────────────────────────────────────────────────────────────────

  // ── panel "informasi request" (tab Rekening & Kartu) ────────────────────

  readonly reqInfoFor = signal<string>('');

  toggleReqInfo(pan: string) {
    this.reqInfoFor.set(this.reqInfoFor() === pan ? '' : pan);
  }

  /**
   * Contoh Track 2 (DE35) dari sebuah PAN.
   *
   * <p>Formatnya {@code PAN=YYMM SVC DISCRETIONARY}. Kedaluwarsa & service code di sini
   * <b>nilai contoh</b> — simulator tak menyimpannya dan tak memeriksanya; yang dibacanya
   * hanya bagian PAN sebelum tanda {@code =}. Kartu kedaluwarsa diuji lewat scenario
   * (DE39=54), bukan lewat tanggal di track ini.
   */
  track2Example(pan: string): string {
    return `${pan}=49121011234567890`;
  }

  /** Rekening yang tertaut ke sebuah kartu — untuk DE102 di panel informasi. */
  accountOf(pan: string): IsoAccount | undefined {
    const card = this.cards().find(c => c.pan === pan);
    return card ? this.accounts().find(a => a.accountNo === card.accountNo) : undefined;
  }

  /** Rekening LAIN sebagai contoh tujuan transfer (DE103). */
  otherAccountNo(pan: string): string {
    const own = this.accountOf(pan)?.accountNo;
    return this.accounts().find(a => a.accountNo !== own)?.accountNo ?? '9876543210';
  }

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
      .open(ConfirmDialog, { data: { title, message, confirmText: this.translate.instant('common.delete'), danger: true } })
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

  private rememberedTab(): string {
    const v = this.storage.get(Iso.LAST_TAB_KEY);
    return typeof v === 'string' ? v : '';
  }
}
