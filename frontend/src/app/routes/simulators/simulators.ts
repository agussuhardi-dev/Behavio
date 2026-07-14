import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
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

import {
  AccountView,
  PartnerView,
  SCENARIOS,
  Scenario,
  Simulator,
  SimulatorService,
  VirtualAccountView,
} from './simulator.service';
import { SimulatorFormDialog, SimulatorFormResult } from './simulator-form-dialog';
import { EndpointUrlPanel } from '../../shared/components/endpoint-url-panel/endpoint-url-panel';
import { ConfirmDialog } from '../../shared/components/confirm-dialog/confirm-dialog';
import { LocalStorageService } from '../../shared/services/storage.service';

/**
 * Satu kartu endpoint bank. `product` menentukan endpoint mana yang di-edit di
 * Admin API — HANYA diisi untuk endpoint yang punya preset Blueprint di backend.
 *
 * Endpoint berlogika tetap (access-token, VA) sengaja TIDAK punya product: memanggil
 * Admin API dengan product tak dikenal akan jatuh ke `default` = transfer
 * (ProductEndpoints.resolve), sehingga malah mengubah scenario transfer.
 */
interface EpMeta {
  key: string;
  label: string;
  method: string;
  desc: string;
  product?: string;
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
  private readonly api = inject(SimulatorService);
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

  readonly endpointMeta = (): EpMeta[] => [
    {
      key: 'balance-inquiry', label: 'Balance Inquiry', method: 'POST',
      desc: 'Cek saldo rekening — Info Saldo (service 11). Mengembalikan saldo available, ledger, float, hold.',
      curl: this.curlBalance(), curlKey: 'bal', scenarioList: [],
    },
    {
      key: 'account-inquiry-internal', label: 'Internal Account Inquiry', method: 'POST',
      desc: 'Validasi nomor & nama rekening internal sebelum transfer (service 15).',
      curl: this.curlAccountInquiry(), curlKey: 'aci', scenarioList: [],
    },
    {
      key: 'transaction-history-list', label: 'Transaction History List', method: 'POST',
      desc: 'Mini statement — riwayat transaksi per-partner (service 12). Mendukung paginasi & rentang tanggal.',
      curl: this.curlTxHistory(), curlKey: 'txh', scenarioList: [],
    },
    {
      key: 'access-token', label: 'Access Token B2B', method: 'POST',
      desc: 'Terbitkan token Bearer B2B — dipakai semua endpoint lain saat mode STRICT.',
      curl: this.curlToken(), curlKey: 'tok', scenarioList: [],
    },
    {
      key: 'transfer', label: 'Transfer Intrabank', method: 'POST',
      desc: 'Transfer antar rekening internal (service 17). Saldo benar-benar didebit & dikredit.',
      product: 'transfer', curl: this.curlTransfer(), curlKey: 'trf', scenarioList: SCENARIOS,
    },
    {
      key: 'transfer-interbank', label: 'Transfer Interbank', method: 'POST',
      desc: 'Transfer ke bank lain (service 18). Hanya debit sumber — rekening tujuan di bank berbeda.',
      product: 'transfer-interbank', curl: this.curlInterbank(), curlKey: 'ibi',
      scenarioList: SCENARIOS.filter(s => ['Normal', 'Saldo Kurang', 'Limit'].includes(s.name)),
    },
    {
      key: 'va-create', label: 'Virtual Account — Create', method: 'POST',
      desc: 'Buat VA. Tandai dibayar dari panel kanan untuk memicu Payment Notification.',
      curl: this.curlVaCreate(), curlKey: 'vac', scenarioList: [],
    },
    {
      key: 'va-status', label: 'Virtual Account — Inquiry Status', method: 'POST',
      desc: 'Cek status VA — ACTIVE/PAID/EXPIRED.',
      curl: this.curlVaStatus(), curlKey: 'vas', scenarioList: [],
    },
    {
      key: 'va-delete', label: 'Virtual Account — Delete', method: 'DELETE',
      desc: 'Hapus VA yang sudah dibuat.',
      curl: this.curlVaDelete(), curlKey: 'vad', scenarioList: [],
    },
  ];

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
          this.syncAllScenarios();
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
    this.syncAllScenarios();
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

  private syncAllScenarios() {
    for (const ep of this.endpointMeta()) {
      if (!ep.product) continue;
      this.api.getActiveScenario(this.selectedSimId(), ep.product).subscribe({
        next: r => this.activeScenarios.update(m => ({ ...m, [ep.key]: r.name })),
        error: () => this.activeScenarios.update(m => ({ ...m, [ep.key]: 'Normal' })),
      });
    }
  }

  activeScenarioFor(key: string): string { return this.activeScenarios()[key] ?? 'Normal'; }

  scenarioMeta(list: Scenario[], name: string): Scenario | undefined { return list.find(s => s.name === name); }

  changeScenario(ep: EpMeta, name: string) {
    if (!ep.product) return;
    this.activeScenarios.update(m => ({ ...m, [ep.key]: name }));
    this.api.setScenario(this.selectedSimId(), name, ep.product).subscribe();
  }

  openEditor(ep: EpMeta) {
    if (!ep.product) return;
    const scenario = this.activeScenarioFor(ep.key);
    this.editorError.set(''); this.editorSaved.set(false); this.editorLoading.set(true);
    this.api.getDefinition(this.selectedSimId(), scenario, ep.product).subscribe({
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
    if (!ep.product) return;
    this.api.saveDefinition(this.selectedSimId(), this.editorScenario(), this.editorText(), ep.product).subscribe({
      next: () => { this.editorSaved.set(true); this.editorError.set(''); },
      error: err => this.editorError.set(err?.error?.error ?? 'JSON tidak valid.'),
    });
  }

  resetEditor(ep: EpMeta) {
    if (!ep.product) return;
    this.api.resetDefinition(this.selectedSimId(), this.editorScenario(), ep.product).subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(this.selectedSimId(), this.editorScenario(), ep.product!).subscribe(t => this.editorText.set(t));
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
