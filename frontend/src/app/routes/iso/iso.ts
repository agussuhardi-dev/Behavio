import { Component, OnInit, inject, signal } from '@angular/core';
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
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  IsoAccount,
  IsoApi,
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
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTabsModule, MatTooltipModule,
  ],
  templateUrl: './iso.html',
  styleUrl: './iso.scss',
})
export class Iso implements OnInit {
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
  readonly upParent = signal('');
  readonly upOperations = signal('balance-inquiry:0200:30, transfer:0200:40, network-management:0800');
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
  readonly logs = signal<LogRow[]>([]);

  get selectedSim(): IsoSimulator | undefined {
    return this.sims().find(s => s.id === this.selectedSimId());
  }

  ngOnInit() {
    this.publicHost.load();
    this.reload();
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
          this.newProfile.set(`${p[0].name}:${p[0].version}`);
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
  }

  loadLogs() {
    this.api.logs(this.selectedSimId(), 30).subscribe({
      next: l => this.logs.set(l.map(x => ({ ...x, open: false }))),
      error: () => this.logs.set([]),
    });
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
