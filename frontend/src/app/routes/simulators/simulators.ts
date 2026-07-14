import { Component, OnDestroy, OnInit, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AccountView, BankApi, SCENARIOS, VirtualAccountView } from '../../core/api/bank-api';
import { EndpointConfig, PartnerView, Scenario, Simulator } from '../../core/api/product-api';
import { SimulatorFormDialog, SimulatorFormResult } from './simulator-form-dialog';
import { EndpointUrlPanel } from '../../shared/components/endpoint-url-panel/endpoint-url-panel';
import { ConfirmDialog } from '../../shared/components/confirm-dialog/confirm-dialog';
import { LocalStorageService } from '../../shared/services/storage.service';
import { OpenApiService } from '../../shared/services/openapi.service';

/**
 * Satu kartu endpoint bank. `operation` = kunci operasi di Admin API, HANYA diisi untuk
 * endpoint yang punya preset Blueprint di backend; endpoint berlogika tetap
 * (access-token, VA) tak punya scenario untuk diedit.
 *
 * Dulu field ini bernama `product` dan nilai tak dikenal diam-diam jatuh ke transfer —
 * sejak pemisahan produk, backend memvalidasinya terhadap katalog dan membalas 400.
 */
interface EpMeta {
  key: string;
  label: string;
  method: string;
  desc: string;
  operation?: string;
  curl: string;
  curlKey: string;
  scenarioList: Scenario[];
}

interface LiveEvent {
  method: string;
  path: string;
  httpStatus: number;
  responseCode: string;
  durationMillis: number;
  at: string;
  requestHeaders: Record<string, string>;
  requestBody: string;
  responseBody: string;
  open: boolean;
}

@Component({
  selector: 'app-simulators',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule, MatCardModule, MatChipsModule, MatDialogModule, MatDividerModule,
    MatExpansionModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTooltipModule,
    EndpointUrlPanel,
  ],
  templateUrl: './simulators.html',
  styleUrl: './simulators.scss',
})
export class Simulators implements OnInit, OnDestroy {
  /** Produk BANK (schema `bank`, design.md §3.4) — halaman ini tak pernah menyentuh QRIS. */
  readonly api = inject(BankApi);
  private readonly openApi = inject(OpenApiService);
  private readonly dialog = inject(MatDialog);
  private readonly storage = inject(LocalStorageService);

  /** Profil terakhir yang dipilih user — dipulihkan saat halaman dibuka lagi. */
  private static readonly LAST_SIM_KEY = 'behavio.bank.lastSimId';

  readonly bankOperations = ['access-token', 'balance-inquiry', 'account-inquiry-internal',
    'transaction-history-list', 'transfer', 'transfer-interbank',
    'va-create', 'va-status', 'va-delete'];

  /** Dipakai kartu "Panduan Skenario" — penjelasan lengkap tiap scenario transfer. */
  readonly transferScenarios = SCENARIOS;

  readonly sims = signal<Simulator[]>([]);
  readonly loading = signal(true);
  readonly selectedSimId = signal<string>('');
  readonly epuOpen = signal(false);
  readonly copiedKey = signal<string | null>(null);
  readonly simMsg = signal('');
  readonly customEps = signal<EndpointConfig[]>([]);

  /** Merged endpoint list: built-in SNAP BI + custom endpoints from the database. */
  readonly allEndpointMeta = computed<EpMeta[]>(() => {
    const builtIn = this.buildMeta();
    const customs = this.customEps().map(cfg => ({
      key: cfg.operation,
      label: cfg.label,
      method: cfg.method,
      desc: `Custom endpoint — ${cfg.method} ${cfg.path}`,
      operation: cfg.operation,
      curl: `curl -X ${cfg.method} http://localhost:${this.port()}${cfg.path}`,
      curlKey: `cust-${cfg.operation}`,
      scenarioList: [{ name: 'Normal', desc: 'Response HTTP 200 OK default.', icon: 'check_circle', tone: 'ok' as const }],
    }));
    return [...builtIn, ...customs];
  });

  // Live View (SSE)
  readonly liveOpen = signal(false);
  readonly liveEvents = signal<LiveEvent[]>([]);
  private es?: EventSource;

  // per-endpoint scenario editing
  readonly activeScenarios = signal<Record<string, string>>({});
  readonly editingEp = signal<string | null>(null);
  readonly editorScenario = signal('');
  readonly editorText = signal('');
  readonly editorError = signal('');
  readonly editorSaved = signal(false);
  readonly editorLoading = signal(false);

  // panel Virtual Account
  readonly vaList = signal<VirtualAccountView[]>([]);
  readonly vaLoading = signal(false);
  readonly vaMsg = signal('');

  // panel Partner & Rekening
  readonly partnerList = signal<PartnerView[]>([]);
  readonly accountList = signal<AccountView[]>([]);
  readonly bankMsg = signal('');
  readonly newPartnerId = signal('');
  readonly newPartnerSecret = signal('');
  readonly newAccPartnerRowId = signal('');
  readonly newAccNo = signal('');
  readonly newAccHolder = signal('');
  readonly newAccBalance = signal('0');
  readonly balanceEdits = signal<Record<string, string>>({});

  private port(): number | string { return this.selectedSim?.port ?? '<port>'; }

  get selectedSim(): Simulator | undefined { return this.sims().find(s => s.id === this.selectedSimId()); }

  private buildMeta(): EpMeta[] { return [
    {
      key: 'balance-inquiry', label: 'Balance Inquiry', method: 'POST',
      desc: 'Cek saldo rekening — Info Saldo (service 11). Mengembalikan saldo available, ledger, float, hold.',
      operation: 'balance-inquiry', curl: this.curlBalance(), curlKey: 'bal',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'account-inquiry-internal', label: 'Internal Account Inquiry', method: 'POST',
      desc: 'Validasi nomor & nama rekening internal sebelum transfer (service 15).',
      operation: 'account-inquiry-internal', curl: this.curlAccountInquiry(), curlKey: 'aci',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'transaction-history-list', label: 'Transaction History List', method: 'POST',
      desc: 'Mini statement — riwayat transaksi per-partner (service 12). Mendukung paginasi & rentang tanggal.',
      operation: 'transaction-history-list', curl: this.curlTxHistory(), curlKey: 'txh',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'access-token', label: 'Access Token B2B', method: 'POST',
      desc: 'Terbitkan token Bearer B2B — dipakai semua endpoint lain saat mode STRICT.',
      operation: 'access-token', curl: this.curlToken(), curlKey: 'tok',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'transfer', label: 'Transfer Intrabank', method: 'POST',
      desc: 'Transfer antar rekening internal (service 17). Saldo benar-benar didebit & dikredit.',
      operation: 'transfer', curl: this.curlTransfer(), curlKey: 'trf', scenarioList: SCENARIOS,
    },
    {
      key: 'transfer-interbank', label: 'Transfer Interbank', method: 'POST',
      desc: 'Transfer ke bank lain (service 18). Hanya debit sumber — rekening tujuan di bank berbeda.',
      operation: 'transfer-interbank', curl: this.curlInterbank(), curlKey: 'ibi',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Saldo Kurang', 'Limit', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'va-create', label: 'Virtual Account — Create', method: 'POST',
      desc: 'Buat VA. Tandai dibayar dari panel kanan untuk memicu Payment Notification.',
      operation: 'va-create', curl: this.curlVaCreate(), curlKey: 'vac',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'va-status', label: 'Virtual Account — Inquiry Status', method: 'POST',
      desc: 'Cek status VA — ACTIVE/PAID/EXPIRED.',
      operation: 'va-status', curl: this.curlVaStatus(), curlKey: 'vas',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
    {
      key: 'va-delete', label: 'Virtual Account — Delete', method: 'DELETE',
      desc: 'Hapus VA yang sudah dibuat.',
      operation: 'va-delete', curl: this.curlVaDelete(), curlKey: 'vad',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Bank Down', 'Timeout'].includes(s.name)),
    },
  ]; }

  copy(text: string, key: string) {
    navigator.clipboard?.writeText(text).then(() => {
      this.copiedKey.set(key);
      setTimeout(() => this.copiedKey.set(null), 1500);
    });
  }

  curlToken(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/access-token/b2b \\
  -H "X-CLIENT-KEY: PARTNER001" -H "X-TIMESTAMP: 2026-01-01T00:00:00+07:00" \\
  -d '{"grantType":"client_credentials"}'`;
  }

  curlBalance(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/balance-inquiry \\
  -H "X-PARTNER-ID: PARTNER001" -H "CHANNEL-ID: 95221" \\
  -d '{"accountNo":"1234567890","partnerReferenceNo":"REF-BAL-001"}'`;
  }

  curlAccountInquiry(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/account-inquiry-internal \\
  -H "X-PARTNER-ID: PARTNER001" -H "CHANNEL-ID: 95221" \\
  -d '{"beneficiaryAccountNo":"1234567890","partnerReferenceNo":"REF-INQ-001"}'`;
  }

  curlTxHistory(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/transaction-history-list \\
  -H "X-PARTNER-ID: PARTNER001" -H "CHANNEL-ID: 95221" \\
  -d '{"partnerReferenceNo":"REF-HIST-001","pageSize":5,"pageNumber":0}'`;
  }

  curlTransfer(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/transfer-intrabank \\
  -H "X-PARTNER-ID: PARTNER001" -H "X-EXTERNAL-ID: TRX-001" \\
  -d '{"partnerReferenceNo":"PREF-001","amount":{"value":"50000.00","currency":"IDR"},
       "sourceAccountNo":"1234567890","beneficiaryAccountNo":"9876543210"}'`;
  }

  curlInterbank(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/transfer-interbank \\
  -H "X-PARTNER-ID: PARTNER001" -H "X-EXTERNAL-ID: IB-001" \\
  -d '{"partnerReferenceNo":"IB-REF-001","amount":{"value":"50000.00","currency":"IDR"},
       "sourceAccountNo":"1234567890","beneficiaryAccountNo":"888801000157508",
       "beneficiaryAccountName":"Yories Yolanda","beneficiaryBankCode":"002"}'`;
  }

  curlVaCreate(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/transfer-va/create-va \\
  -H "X-PARTNER-ID: PARTNER001" -H "X-CALLBACK-URL: http://localhost:8080/api/admin/v1/webhook-sink" \\
  -d '{"partnerServiceId":"12345","customerNo":"001","virtualAccountNo":"12345001",
       "virtualAccountName":"Budi","totalAmount":{"value":"150000.00","currency":"IDR"},"trxId":"INV-001"}'`;
  }

  curlVaStatus(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/transfer-va/status \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerServiceId":"12345","customerNo":"001","virtualAccountNo":"12345001","inquiryRequestId":"INQ-001"}'`;
  }

  curlVaDelete(): string {
    return `curl -X DELETE http://localhost:${this.port()}/v1.0/transfer-va/delete-va \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerServiceId":"12345","customerNo":"001","virtualAccountNo":"12345001","trxId":"INV-001"}'`;
  }

  // ---- lifecycle ----

  ngOnInit() { this.reload(); }

  ngOnDestroy() { this.disconnectLive(); }

  private rememberSim(id: string) {
    if (id) this.storage.set(Simulators.LAST_SIM_KEY, id);
  }

  /** LocalStorageService.get() balikin {} bila key belum ada — jadi cek tipenya. */
  private rememberedSimId(): string {
    const v = this.storage.get(Simulators.LAST_SIM_KEY);
    return typeof v === 'string' ? v : '';
  }

  reload() {
    this.loading.set(true);
    this.api.list().subscribe({
      next: sims => {
        this.sims.set(sims);
        const keep = sims.some(s => s.id === this.selectedSimId());
        if (!keep) {
          const remembered = sims.find(s => s.id === this.rememberedSimId())?.id;
          this.selectedSimId.set(remembered ?? sims[0]?.id ?? '');
        }
          if (this.selectedSimId()) {
            this.loadCustomEndpoints();
            this.reloadVa();
            this.reloadBank();
            this.connectLive();
        } else {
          this.disconnectLive();
        }
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  onSelectSim(id: string) {
    this.selectedSimId.set(id);
    this.rememberSim(id);
    this.editingEp.set(null);
    this.liveEvents.set([]);
    this.newAccPartnerRowId.set('');
    this.loadCustomEndpoints();
    this.reloadVa();
    this.reloadBank();
    this.connectLive();
  }

  // ---- Live View (SSE) ----

  private connectLive() {
    const id = this.selectedSimId();
    if (!id) return;
    this.disconnectLive();
    this.es = new EventSource(this.api.streamUrl(id));
    this.es.addEventListener('request', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        const ev: LiveEvent = {
          method: d.method, path: d.path, httpStatus: d.httpStatus,
          responseCode: d.responseCode, durationMillis: d.durationMillis,
          at: new Date().toLocaleTimeString(),
          requestHeaders: d.requestHeaders ?? {}, requestBody: d.requestBody ?? '',
          responseBody: d.responseBody ?? '', open: false,
        };
        this.liveEvents.update(list => [ev, ...list].slice(0, 50));
        // Request masuk bisa mengubah saldo (transfer) atau membuat VA — segarkan panel.
        this.reloadVa(true);
        this.reloadBank(true);
      } catch { /* abaikan payload tak valid */ }
    });
  }

  private disconnectLive() { this.es?.close(); this.es = undefined; }

  toggleLive() { this.liveOpen.set(!this.liveOpen()); }

  clearLive() { this.liveEvents.set([]); }

  isLiveSuccess(code: string): boolean { return !!code && code.startsWith('2'); }

  toggleEvent(ev: LiveEvent) {
    this.liveEvents.update(list => list.map(e => e === ev ? { ...e, open: !e.open } : e));
  }

  /** Rapikan JSON agar mudah dibaca saat debugging; biarkan apa adanya bila bukan JSON. */
  prettyJson(text: string): string {
    if (!text) return '(kosong)';
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
  }

  headerLines(h: Record<string, string>): string {
    const keys = Object.keys(h ?? {});
    if (keys.length === 0) return '(tidak ada header)';
    return keys.sort().map(k => `${k}: ${h[k]}`).join('\n');
  }

  /** Saran port profil bank — pita 9001+ (QRIS memakai pita 9101+, lihat design.md §3.4). */
  private suggestedPort(): number {
    const used = new Set(this.sims().map(s => s.port));
    let p = 9001; while (used.has(p)) p++;
    return p;
  }

  // ---- profile management ----

  openCreate() {
    const ref = this.dialog.open(SimulatorFormDialog, { data: { mode: 'create', suggestedPort: this.suggestedPort() } });
    ref.afterClosed().subscribe((r?: SimulatorFormResult) => {
      if (!r) return;
      this.simMsg.set('');
      this.api.create(r.name, r.port, r.signatureMode).subscribe({
        next: c => {
          this.simMsg.set(`Profil "${c.name}" berhasil dibuat di port ${c.port}.`);
          this.selectedSimId.set(c.id);
          this.rememberSim(c.id);
          this.reload();
        },
        error: err => this.simMsg.set(err?.error?.error ?? 'Gagal membuat profil.'),
      });
    });
  }

  openClone() {
    const s = this.selectedSim; if (!s) return;
    const ref = this.dialog.open(SimulatorFormDialog, { data: { mode: 'clone', sourceName: s.name, suggestedPort: this.suggestedPort() } });
    ref.afterClosed().subscribe((r?: SimulatorFormResult) => {
      if (!r) return;
      this.simMsg.set('');
      this.api.clone(s.id, r.name, r.port).subscribe({
        next: c => {
          this.simMsg.set(`Profil "${c.name}" berhasil diduplikasi di port ${c.port}.`);
          this.selectedSimId.set(c.id);
          this.rememberSim(c.id);
          this.reload();
        },
        error: err => this.simMsg.set(err?.error?.error ?? 'Gagal menduplikasi profil.'),
      });
    });
  }

  // ---- Export / Import OpenAPI (design.md §15) ----

  /** Unduh spec — dipakai Postman dkk; perilaku ikut di x-behavio. */
  exportOpenApi() {
    const s = this.selectedSim; if (!s) return;
    this.openApi.export(this.api, s);
  }

  openImportOpenApi() {
    const s = this.selectedSim; if (!s) return;
    // flatMap, bukan filter+map: filter tidak mempersempit `operation?: string`.
    const operations = this.buildMeta().flatMap(m =>
      m.operation ? [{ key: m.operation, label: m.label }] : []
    );

    this.openApi.openImport(this.api, s, operations).subscribe(result => {
      if (!result) return;
      this.simMsg.set(this.openApi.summarize(result));
      // Path & scenario bisa berubah → muat ulang, jangan percaya tampilan lama.
      this.reload();
      this.loadCustomEndpoints();
    });
  }

  removeSelected() {
    const s = this.selectedSim; if (!s) return;
    this.confirmDialog({
      title: 'Hapus profil?',
      message: `Profil "${s.name}" (port ${s.port}) akan dihapus permanen beserta partner, rekening, dan VA-nya. Tindakan ini tidak bisa dibatalkan.`,
      confirmText: 'Hapus',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.delete(s.id).subscribe(() => {
        this.storage.remove(Simulators.LAST_SIM_KEY);
        this.selectedSimId.set('');
        this.reload();
      });
    });
  }

  private confirmDialog(data: { title: string; message: string; confirmText?: string; danger?: boolean }) {
    return this.dialog.open(ConfirmDialog, { data, width: '420px', autoFocus: false }).afterClosed();
  }

  toggleRunning() {
    const s = this.selectedSim; if (!s) return;
    (s.status === 'RUNNING' ? this.api.stop(s.id) : this.api.start(s.id)).subscribe(() => this.reload());
  }

  // ---- scenario per-endpoint ----

  /** Reload custom endpoints from backend — public for template binding. */
  loadCustomEndpoints() {
    if (!this.selectedSimId()) {
      this.customEps.set([]);
      this.syncAllScenarios();
      return;
    }
    this.api.listEndpoints(this.selectedSimId()).subscribe({
      next: list => {
        this.customEps.set(list.filter(e => e.operation.startsWith('custom-')));
        this.syncAllScenarios();
      },
      error: () => {
        this.customEps.set([]);
        this.syncAllScenarios();
      },
    });
  }

  private syncAllScenarios() {
    for (const ep of this.allEndpointMeta()) {
      if (!ep.operation) continue;
      this.api.getActiveScenario(this.selectedSimId(), ep.operation).subscribe({
        next: r => this.activeScenarios.update(m => ({ ...m, [ep.key]: r.name })),
        error: () => this.activeScenarios.update(m => ({ ...m, [ep.key]: 'Normal' })),
      });
    }
  }

  activeScenarioFor(key: string): string { return this.activeScenarios()[key] ?? 'Normal'; }

  scenarioMeta(list: Scenario[], name: string): Scenario | undefined { return list.find(s => s.name === name); }

  changeScenario(ep: EpMeta, name: string) {
    if (!ep.operation) return;
    this.activeScenarios.update(m => ({ ...m, [ep.key]: name }));
    this.api.setScenario(this.selectedSimId(), name, ep.operation).subscribe();
  }

  openEditor(ep: EpMeta) {
    if (!ep.operation) return;
    const scenario = this.activeScenarioFor(ep.key);
    this.editorError.set(''); this.editorSaved.set(false); this.editorLoading.set(true);
    this.api.getDefinition(this.selectedSimId(), scenario, ep.operation).subscribe({
      next: text => {
        this.editorText.set(text); this.editorScenario.set(scenario);
        this.editingEp.set(ep.key); this.editorLoading.set(false);
      },
      error: err => {
        this.editorLoading.set(false);
        this.editorError.set(err?.status === 0 ? 'Backend tidak terjangkau.' : `Gagal memuat (${err?.status ?? '?'})`);
      },
    });
  }

  closeEditor() { this.editingEp.set(null); }

  onEditorInput(v: string) { this.editorText.set(v); this.editorSaved.set(false); this.editorError.set(''); }

  saveEditor(ep: EpMeta) {
    if (!ep.operation) return;
    this.api.saveDefinition(this.selectedSimId(), this.editorScenario(), this.editorText(), ep.operation).subscribe({
      next: () => { this.editorSaved.set(true); this.editorError.set(''); },
      error: err => this.editorError.set(err?.error?.error ?? 'JSON tidak valid.'),
    });
  }

  resetEditor(ep: EpMeta) {
    if (!ep.operation) return;
    this.api.resetDefinition(this.selectedSimId(), this.editorScenario(), ep.operation).subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(this.selectedSimId(), this.editorScenario(), ep.operation!).subscribe(t => this.editorText.set(t));
    });
  }

  // ---- Virtual Account ----

  reloadVa(keepMsg = false) {
    if (!this.selectedSimId()) return;
    this.vaLoading.set(true);
    if (!keepMsg) this.vaMsg.set('');
    this.api.listVirtualAccounts(this.selectedSimId()).subscribe({
      next: list => { this.vaList.set(list); this.vaLoading.set(false); },
      error: () => this.vaLoading.set(false),
    });
  }

  payVa(va: VirtualAccountView) {
    this.confirmDialog({
      title: 'Tandai VA sebagai dibayar?',
      message: `VA "${va.virtualAccountNo}" akan berstatus PAID sebesar ${va.amount} ${va.currency}`
        + (va.hasCallback ? ', dan Payment Notification (webhook) dikirim ke callback URL-nya.' : '. Tidak ada callback URL tersimpan, jadi webhook tidak dikirim.'),
      confirmText: 'Tandai Dibayar',
    }).subscribe(ok => {
      if (!ok) return;
      this.api.payVirtualAccount(this.selectedSimId(), va.virtualAccountNo).subscribe({
        next: r => { this.vaMsg.set(r.webhookSent ? 'Payment Notification terkirim.' : r.note); this.reloadVa(true); },
        error: () => this.vaMsg.set('Gagal menandai VA dibayar.'),
      });
    });
  }

  vaStatusTone(s: string): 'ok' | 'warn' | 'fault' {
    if (s === 'PAID') return 'ok'; if (s === 'EXPIRED') return 'fault'; return 'warn';
  }

  // ---- Partner & Rekening ----

  reloadBank(keepMsg = false) {
    if (!this.selectedSimId()) return;
    if (!keepMsg) this.bankMsg.set('');
    this.api.listPartners(this.selectedSimId()).subscribe(list => {
      this.partnerList.set(list);
      if (!this.newAccPartnerRowId() && list.length > 0) this.newAccPartnerRowId.set(list[0].id);
    });
    this.api.listAccounts(this.selectedSimId()).subscribe(list => {
      this.accountList.set(list);
      const edits: Record<string, string> = {};
      list.forEach(a => (edits[a.id] = a.balance));
      this.balanceEdits.set(edits);
    });
  }

  addPartner() {
    const partnerId = this.newPartnerId().trim();
    if (!partnerId) return;
    this.api.createPartner(this.selectedSimId(), partnerId, this.newPartnerSecret().trim() || undefined).subscribe({
      next: () => {
        this.newPartnerId.set('');
        this.newPartnerSecret.set('');
        this.bankMsg.set(`Partner "${partnerId}" ditambahkan.`);
        this.reloadBank(true);
      },
      error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal menambah partner.'),
    });
  }

  removePartner(p: PartnerView) {
    this.confirmDialog({
      title: 'Hapus partner?',
      message: `Partner "${p.partnerId}" akan dihapus beserta seluruh rekening miliknya.`,
      confirmText: 'Hapus',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.deletePartner(this.selectedSimId(), p.id).subscribe(() => this.reloadBank());
    });
  }

  addAccount() {
    const accNo = this.newAccNo().trim();
    if (!accNo || !this.newAccPartnerRowId()) return;
    this.api
      .createAccount(this.selectedSimId(), this.newAccPartnerRowId(), accNo, this.newAccHolder().trim(), this.newAccBalance())
      .subscribe({
        next: () => {
          this.newAccNo.set('');
          this.newAccHolder.set('');
          this.newAccBalance.set('0');
          this.bankMsg.set(`Rekening "${accNo}" ditambahkan.`);
          this.reloadBank(true);
        },
        error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal menambah rekening.'),
      });
  }

  onBalanceEdit(accountId: string, value: string) {
    this.balanceEdits.update(m => ({ ...m, [accountId]: value }));
  }

  saveBalance(a: AccountView) {
    this.api.setAccountBalance(this.selectedSimId(), a.id, this.balanceEdits()[a.id]).subscribe({
      next: () => { this.bankMsg.set(`Saldo ${a.accountNo} diperbarui.`); this.reloadBank(true); },
      error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal mengubah saldo.'),
    });
  }

  removeAccount(a: AccountView) {
    this.confirmDialog({
      title: 'Hapus rekening?',
      message: `Rekening "${a.accountNo}" (${a.holderName || 'tanpa nama'}) akan dihapus permanen.`,
      confirmText: 'Hapus',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.deleteAccount(this.selectedSimId(), a.id).subscribe(() => this.reloadBank());
    });
  }
}
