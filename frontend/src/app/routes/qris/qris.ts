import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { MatSnackBar } from '@angular/material/snack-bar';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';
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
import { MatTooltipModule } from '@angular/material/tooltip';

import { QRIS_SCENARIOS, QrisApi, QrisView } from '../../core/api/qris-api';
import { PartnerView, Scenario, Simulator } from '../../core/api/product-api';
import { PublicHost } from '../../core/api/public-host';
import { SimulatorFormDialog, SimulatorFormResult } from '../simulators/simulator-form-dialog';
import { EndpointUrlPanel } from '../../shared/components/endpoint-url-panel/endpoint-url-panel';
import { WebhookPanel } from '../../shared/components/webhook-panel/webhook-panel';
import { ConfirmDialog } from '../../shared/components/confirm-dialog/confirm-dialog';
import { LocalStorageService } from '../../shared/services/storage.service';
import { OpenApiService } from '../../shared/services/openapi.service';

/** Satu kartu endpoint QRIS. `operation` = kunci operasi di Admin API produk qris. */
interface EpMeta {
  key: string; label: string; desc: string; operation: string;
  curl: string; curlKey: string; scenarioList: Scenario[];
}

interface LiveEvent {
  method: string; path: string; httpStatus: number;
  responseCode: string; durationMillis: number; at: string;
  requestHeaders: Record<string, string>; requestBody: string; responseBody: string;
  open: boolean;
}

@Component({
  selector: 'app-qris',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule, MatCardModule, MatDialogModule, MatDividerModule,
    MatExpansionModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatPaginatorModule, MatProgressBarModule, MatSelectModule, MatTooltipModule,
    EndpointUrlPanel,
    WebhookPanel,
    TranslatePipe,
  ],
  templateUrl: './qris.html',
  styleUrl: './qris.scss',
})
export class Qris implements OnInit, OnDestroy {
  /**
   * Produk QRIS (schema `qris`, design.md §3.4). Profil di halaman ini adalah **PJP**
   * dengan port & partner sendiri — sejak pemisahan, bukan lagi profil bank yang sama.
   */
  private readonly clipboard = inject(ClipboardService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly translate = inject(TranslateService);
  readonly api = inject(QrisApi);
  private readonly openApi = inject(OpenApiService);
  private readonly dialog = inject(MatDialog);
  private readonly storage = inject(LocalStorageService);

  /** Profil terakhir yang dipilih user — dipulihkan saat halaman dibuka lagi. */
  private static readonly LAST_SIM_KEY = 'behavio.qris.lastSimId';

  readonly qrisOperations = ['access-token', 'qris-generate', 'qris-query', 'qris-refund', 'qris-cancel', 'qris-decode', 'qris-payment', 'qris-apply-ott'];

  /**
   * Dipakai kartu "Panduan Skenario QRIS" — cermin `transferScenarios` di halaman bank.
   *
   * Sebelumnya template membaca `endpointMeta()[0].scenarioList`, dan sejak `access-token`
   * (yang scenario-nya kosong) masuk ke urutan PERTAMA, panduannya jadi kosong tanpa
   * jejak apa pun. Menunjuk konstanta, bukan indeks: urutan kartu boleh berubah tanpa
   * diam-diam mengosongkan panduan.
   */
  readonly qrisScenarios = QRIS_SCENARIOS;

  readonly sims = signal<Simulator[]>([]);
  readonly loading = signal(true);
  readonly selectedSimId = signal<string>('');
  readonly partnerList = signal<PartnerView[]>([]);
  readonly epuOpen = signal(false);
  readonly copiedKey = signal<string | null>(null);

  readonly qrList = signal<QrisView[]>([]);
  readonly qrLoading = signal(false);
  readonly qrMsg = signal('');
  readonly payAmounts = signal<Record<string, string>>({});

  // paging daftar QR (server-side; terbaru selalu di halaman pertama)
  readonly qrTotal = signal(0);
  readonly qrPage = signal(0);
  readonly qrSize = signal(10);

  // Live View (SSE) — request QRIS real-time
  readonly liveOpen = signal(false);
  readonly liveEvents = signal<LiveEvent[]>([]);
  readonly qrFlash = signal(false);
  private es?: EventSource;

  // per-endpoint scenario editing
  readonly activeScenarios = signal<Record<string, string>>({});
  readonly editingEp = signal<string | null>(null);
  readonly editorScenario = signal('');
  readonly editorText = signal('');
  readonly editorError = signal('');
  readonly editorSaved = signal(false);
  readonly editorLoading = signal(false);

  private readonly publicHost = inject(PublicHost);

  /** Host untuk contoh curl — DEPLOY_HOST dari backend, jatuh ke host browser. */
  private host(): string { return this.publicHost.host(); }

  private port(): number | string { return this.selectedSim?.port ?? '<port>'; }

  get selectedSim(): Simulator | undefined { return this.sims().find(s => s.id === this.selectedSimId()); }

  readonly endpointMeta = (): EpMeta[] => [
    {
      // Sejak pemisahan produk (design.md §3.4), profil QRIS = PJP tersendiri yang
      // menerbitkan token B2B-nya SENDIRI — token profil bank tak berlaku di sini.
      key: 'access-token', label: 'qris.ep.access-token.label', operation: '',
      desc: 'qris.ep.access-token.desc',
      curl: this.curlToken(), curlKey: 'tok', scenarioList: [],
    },
    {
      key: 'qris-generate', label: 'qris.ep.qris-generate.label', operation: 'qris-generate',
      desc: 'qris.ep.qris-generate.desc',
      curl: this.curlDynamic(), curlKey: 'gen', scenarioList: QRIS_SCENARIOS,
    },
    {
      key: 'qris-query', label: 'qris.ep.qris-query.label', operation: 'qris-query',
      desc: 'qris.ep.qris-query.desc',
      curl: this.curlQuery(), curlKey: 'qry', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-refund', label: 'qris.ep.qris-refund.label', operation: 'qris-refund',
      desc: 'qris.ep.qris-refund.desc',
      curl: this.curlRefund(), curlKey: 'rfd', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-cancel', label: 'qris.ep.qris-cancel.label', operation: 'qris-cancel',
      desc: 'qris.ep.qris-cancel.desc',
      curl: this.curlCancel(), curlKey: 'cnl', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-decode', label: 'qris.ep.qris-decode.label', operation: 'qris-decode',
      desc: 'qris.ep.qris-decode.desc',
      curl: this.curlDecode(), curlKey: 'dcd', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-payment', label: 'qris.ep.qris-payment.label', operation: 'qris-payment',
      desc: 'qris.ep.qris-payment.desc',
      curl: this.curlPayment(), curlKey: 'pay', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-apply-ott', label: 'qris.ep.qris-apply-ott.label', operation: 'qris-apply-ott',
      desc: 'qris.ep.qris-apply-ott.desc',
      curl: this.curlOtt(), curlKey: 'ott', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
  ];

  copy(text: string, key: string) {
    // Lewat ClipboardService, BUKAN navigator.clipboard langsung: API itu undefined di
    // halaman non-HTTPS (mis. http://<ip>:81), sehingga tombol salin diam-diam tak
    // berfungsi tanpa error apa pun.
    this.clipboard.copy(text).then(ok => {
      if (!ok) {
        this.snackBar.open(this.translate.instant('common.copy_failed'),
          this.translate.instant('common.close'), { duration: 4000 });
        return;
      }
      this.copiedKey.set(key);
      setTimeout(() => this.copiedKey.set(null), 1500);
    });
  }

  curlToken(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/access-token/b2b \\
  -H "X-CLIENT-KEY: PARTNER001" -H "Content-Type: application/json" \\
  -d '{"grantType":"client_credentials"}'`;
  }

  curlDynamic(): string {
    // Tanpa X-CALLBACK-URL (design.md §9.1): URL notifikasi didaftarkan partner di panel
    // Webhook, bukan dititipkan per-request.
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-generate \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerReferenceNo":"PR-001","merchantId":"MERCHANT01","amount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlQuery(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-query \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<referenceNo>","serviceCode":"47"}'`;
  }

  curlRefund(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-refund \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<refNo>","partnerRefundNo":"RF-001","refundAmount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlCancel(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-cancel \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<refNo>","merchantId":"MERCHANT01","reason":"cancel reason"}'`;
  }

  curlDecode(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-decode \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerReferenceNo":"PR-003","qrContent":"0002010102...","merchantId":"MERCHANT01","scanTime":"2023-01-15T10:00:00+07:00"}'`;
  }

  curlPayment(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/qr-mpm-payment \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerReferenceNo":"PAY-001","merchantId":"MERCHANT01","amount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlOtt(): string {
    return `curl -X POST http://${this.host()}:${this.port()}/v1.0/qr/apply-ott \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"userResources":["OTT"]}'`;
  }

  // ---- lifecycle ----

  ngOnInit() { this.publicHost.load(); this.reload(); }

  ngOnDestroy() { this.disconnectLive(); }

  private rememberSim(id: string) {
    if (id) this.storage.set(Qris.LAST_SIM_KEY, id);
  }

  /** LocalStorageService.get() balikin {} bila key belum ada — jadi cek tipenya. */
  private rememberedSimId(): string {
    const v = this.storage.get(Qris.LAST_SIM_KEY);
    return typeof v === 'string' ? v : '';
  }

  reload() {
    this.loading.set(true);
    this.api.list().subscribe({
      next: sims => {
        this.sims.set(sims);
        const keep = sims.some(s => s.id === this.selectedSimId());
        if (!keep) {
          // Pulihkan profil terakhir yang dipilih; bila sudah dihapus → profil pertama.
          const remembered = sims.find(s => s.id === this.rememberedSimId())?.id;
          this.selectedSimId.set(remembered ?? sims[0]?.id ?? '');
        }
        if (this.selectedSimId()) {
          this.reloadQr();
          this.syncAllScenarios();
          this.loadPartners();
          this.connectLive();
        } else {
          this.disconnectLive();
        }
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  /** Partner profil ini — dipakai panel registrasi webhook (design.md §9.1). */
  private loadPartners() {
    this.api.listPartners(this.selectedSimId()).subscribe({
      next: list => this.partnerList.set(list),
      error: () => this.partnerList.set([]),
    });
  }

  // ---- Live View (SSE) ----

  /** Sambungkan stream SSE simulator terpilih. Aktif walau panel Live tertutup
   *  agar card "QR Dibuat" tetap auto-update saat ada request generate masuk. */
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
        // Request QRIS masuk → segarkan halaman QR aktif + kedipkan card sebagai penanda.
        this.reloadQr(true);
        this.qrFlash.set(true);
        setTimeout(() => this.qrFlash.set(false), 900);
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
    if (!text) return this.translate.instant('common.empty');
    try { return JSON.stringify(JSON.parse(text), null, 2); } catch { return text; }
  }

  headerLines(h: Record<string, string>): string {
    const keys = Object.keys(h ?? {});
    if (keys.length === 0) return this.translate.instant('common.no_header');
    return keys.sort().map(k => `${k}: ${h[k]}`).join('\n');
  }

  /**
   * Saran port untuk profil PJP baru. Memakai pita 9101+ — TERPISAH dari pita bank
   * (9001+): halaman ini hanya melihat profil QRIS, jadi kalau menyarankan dari 9001 ia
   * tak akan sadar port itu milik profil bank dan create selalu ditolak 409 oleh
   * platform.port_registry (design.md §3.4). Bentrok sisa tetap dijaga backend.
   */
  private suggestedPort(): number {
    const used = new Set(this.sims().map(s => s.port));
    let p = 9101; while (used.has(p)) p++;
    return p;
  }

  // ---- profile management ----

  // Error HTTP tidak di-alert lagi: errorInterceptor sudah menampilkan toast berisi
  // pesan dari Admin API (mis. "port 9001 sudah dipakai") untuk semua request.

  openCreate() {
    const ref = this.dialog.open(SimulatorFormDialog, { data: { mode: 'create', suggestedPort: this.suggestedPort() } });
    ref.afterClosed().subscribe((r?: SimulatorFormResult) => {
      if (!r) return;
      this.api.create(r.name, r.port, r.signatureMode).subscribe({
        next: c => { this.selectedSimId.set(c.id); this.rememberSim(c.id); this.reload(); },
      });
    });
  }

  openClone() {
    const s = this.selectedSim; if (!s) return;
    const ref = this.dialog.open(SimulatorFormDialog, { data: { mode: 'clone', sourceName: s.name, suggestedPort: this.suggestedPort() } });
    ref.afterClosed().subscribe((r?: SimulatorFormResult) => {
      if (!r) return;
      this.api.clone(s.id, r.name, r.port).subscribe({
        next: c => { this.selectedSimId.set(c.id); this.rememberSim(c.id); this.reload(); },
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
    const operations = this.endpointMeta().flatMap(m =>
      m.operation ? [{ key: m.operation, label: m.label }] : []
    );

    this.openApi.openImport(this.api, s, operations).subscribe(result => {
      if (!result) return;
      this.openApi.showResult(result);
      // Path & scenario bisa berubah → muat ulang, jangan percaya tampilan lama.
      this.reload();
    });
  }

  removeSelected() {
    const s = this.selectedSim; if (!s) return;
    this.confirmDialog({
      title: this.translate.instant('qris.msg.delete_title'),
      message: this.translate.instant('qris.msg.delete_body', { name: s.name, port: s.port }),
      confirmText: this.translate.instant('common.delete'),
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.delete(s.id).subscribe(() => {
        this.storage.remove(Qris.LAST_SIM_KEY);
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

  onSelectSim(id: string) {
    this.selectedSimId.set(id);
    this.rememberSim(id);
    this.editingEp.set(null);
    this.liveEvents.set([]);
    this.qrPage.set(0);
    this.reloadQr();
    this.syncAllScenarios();
    this.connectLive();
  }

  // ---- scenario per-endpoint ----

  private syncAllScenarios() {
    for (const ep of this.endpointMeta()) {
      // Endpoint berlogika tetap (access-token) tak punya scenario. Tanpa penjaga ini
      // dashboard menembak `?operation=` kosong → backend 400 → errorInterceptor
      // memunculkan toast error tiap halaman dibuka.
      if (!ep.operation) continue;
      this.api.getActiveScenario(this.selectedSimId(), ep.operation).subscribe({
        next: r => this.activeScenarios.update(m => ({ ...m, [ep.key]: r.name })),
        error: () => this.activeScenarios.update(m => ({ ...m, [ep.key]: 'Normal' })),
      });
    }
  }

  activeScenarioFor(key: string): string { return this.activeScenarios()[key] ?? 'Normal'; }

  /**
   * Kunci i18n untuk label/deskripsi scenario. `name` TETAP identifier ke backend
   * (nilai select & payload) — hanya TAMPILANNYA yang dipetakan ke terjemahan.
   */
  scName(name: string): string { return `scenario.qris.${this.scSlug(name)}.name`; }
  scDesc(name: string): string { return `scenario.qris.${this.scSlug(name)}.desc`; }
  private scSlug(name: string): string {
    return (name || '').toLowerCase().replace(/[^a-z0-9]+/g, '_').replace(/^_+|_+$/g, '');
  }

  changeScenario(ep: EpMeta, name: string) {
    this.activeScenarios.update(m => ({ ...m, [ep.key]: name }));
    this.api.setScenario(this.selectedSimId(), name, ep.operation).subscribe();
  }

  openEditor(ep: EpMeta) {
    const scenario = this.activeScenarioFor(ep.key);
    this.editorError.set(''); this.editorSaved.set(false); this.editorLoading.set(true);
    this.api.getDefinition(this.selectedSimId(), scenario, ep.operation).subscribe({
      next: text => {
        this.editorText.set(text); this.editorScenario.set(scenario);
        this.editingEp.set(ep.key); this.editorLoading.set(false);
      },
      error: err => {
        this.editorLoading.set(false);
        this.editorError.set(err?.status === 0
          ? this.translate.instant('qris.msg.backend_unreachable')
          : this.translate.instant('qris.msg.load_failed', { status: err?.status ?? '?' }));
      },
    });
  }

  closeEditor() { this.editingEp.set(null); }

  onEditorInput(v: string) { this.editorText.set(v); this.editorSaved.set(false); this.editorError.set(''); }

  saveEditor(ep: EpMeta) {
    this.api.saveDefinition(this.selectedSimId(), this.editorScenario(), this.editorText(), ep.operation).subscribe({
      next: () => { this.editorSaved.set(true); this.editorError.set(''); },
      error: err => this.editorError.set(err?.error?.error ?? this.translate.instant('qris.msg.invalid_json')),
    });
  }

  resetEditor(ep: EpMeta) {
    this.api.resetDefinition(this.selectedSimId(), this.editorScenario(), ep.operation).subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(this.selectedSimId(), this.editorScenario(), ep.operation).subscribe(t => this.editorText.set(t));
    });
  }

  // ---- QR list ----

  reloadQr(keepMsg = false) {
    if (!this.selectedSimId()) return;
    this.qrLoading.set(true);
    if (!keepMsg) this.qrMsg.set('');
    this.api.listQris(this.selectedSimId(), this.qrPage(), this.qrSize()).subscribe({
      next: p => {
        this.qrList.set(p.items);
        this.qrTotal.set(p.total);
        this.qrPage.set(p.page); // server bisa mundurkan halaman bila di luar jangkauan
        this.qrLoading.set(false);
      },
      error: () => this.qrLoading.set(false),
    });
  }

  onQrPage(e: PageEvent) {
    this.qrPage.set(e.pageIndex);
    this.qrSize.set(e.pageSize);
    this.reloadQr();
  }

  onPayAmount(refNo: string, value: string) { this.payAmounts.update(m => ({ ...m, [refNo]: value })); }

  /**
   * Kirim ulang Payment Notify memakai status aktif QR — retry/test (design.md §9.2).
   * Tanpa konfirmasi: tak ada data yang berubah.
   */
  resendNotification(qr: QrisView) {
    this.api.resendQrisNotification(this.selectedSimId(), qr.referenceNo).subscribe({
      next: r => this.qrMsg.set(r.note),
      error: err => this.qrMsg.set(err?.error?.error ?? this.translate.instant('qris.msg.resend_failed')),
    });
  }

  pay(qr: QrisView) {
    const amount = qr.qrType === 'STATIC' ? this.payAmounts()[qr.referenceNo] : undefined;
    if (qr.qrType === 'STATIC' && !amount) { this.qrMsg.set(this.translate.instant('qris.msg.static_needs_amount')); return; }
    const nominal = qr.qrType === 'STATIC' ? `${amount} ${qr.currency}` : `${qr.amount} ${qr.currency}`;
    this.confirmDialog({
      title: this.translate.instant('qris.msg.pay_title'),
      message: this.translate.instant('qris.msg.pay_body', { qr: qr.referenceNo, nominal }),
      confirmText: this.translate.instant('common.mark_paid'),
    }).subscribe(ok => {
      if (!ok) return;
      this.api.payQris(this.selectedSimId(), qr.referenceNo, amount).subscribe({
        next: r => { this.qrMsg.set(r.webhookSent ? this.translate.instant('qris.msg.notify_sent') : r.note); this.reloadQr(true); },
        error: () => this.qrMsg.set(this.translate.instant('qris.msg.failed')),
      });
    });
  }

  expireQr(qr: QrisView) {
    this.confirmDialog({
      title: this.translate.instant('qris.msg.expire_title'),
      message: this.translate.instant('qris.msg.expire_body', { qr: qr.referenceNo }),
      confirmText: this.translate.instant('qris.expire'),
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.expireQris(this.selectedSimId(), qr.referenceNo).subscribe({
        next: r => { this.qrMsg.set(r.note); this.reloadQr(true); },
        error: () => this.qrMsg.set(this.translate.instant('qris.msg.failed')),
      });
    });
  }

  statusTone(s: string): 'ok' | 'warn' | 'fault' {
    if (s === 'PAID') return 'ok'; if (s === 'REFUNDED') return 'warn'; if (s === 'EXPIRED') return 'fault'; return 'warn';
  }
}
