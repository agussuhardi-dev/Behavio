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
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  QRIS_SCENARIOS,
  QrisView,
  Scenario,
  Simulator,
  SimulatorService,
} from '../simulators/simulator.service';
import { SimulatorFormDialog, SimulatorFormResult } from '../simulators/simulator-form-dialog';
import { EndpointUrlPanel } from '../../shared/components/endpoint-url-panel/endpoint-url-panel';
import { ConfirmDialog } from '../../shared/components/confirm-dialog/confirm-dialog';
import { LocalStorageService } from '../../shared/services/storage.service';

interface EpMeta {
  key: string; label: string; desc: string; product: string;
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
  ],
  templateUrl: './qris.html',
  styleUrl: './qris.scss',
})
export class Qris implements OnInit, OnDestroy {
  private readonly api = inject(SimulatorService);
  private readonly dialog = inject(MatDialog);
  private readonly storage = inject(LocalStorageService);

  /** Profil terakhir yang dipilih user — dipulihkan saat halaman dibuka lagi. */
  private static readonly LAST_SIM_KEY = 'behavio.qris.lastSimId';

  readonly qrisOperations = ['qris-generate', 'qris-query', 'qris-refund', 'qris-cancel', 'qris-decode', 'qris-payment', 'qris-apply-ott'];

  readonly sims = signal<Simulator[]>([]);
  readonly loading = signal(true);
  readonly selectedSimId = signal<string>('');
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

  private port(): number | string { return this.selectedSim?.port ?? '<port>'; }

  get selectedSim(): Simulator | undefined { return this.sims().find(s => s.id === this.selectedSimId()); }

  readonly endpointMeta = (): EpMeta[] => [
    {
      key: 'qris-generate', label: 'Generate QR', product: 'qris',
      desc: 'Buat QR (dynamic/static). Bisa di-custom: tolak amount ≤ 0, blokir merchant, simulasi down.',
      curl: this.curlDynamic(), curlKey: 'gen', scenarioList: QRIS_SCENARIOS,
    },
    {
      key: 'qris-query', label: 'Query Status', product: 'qris-query',
      desc: 'Cek status QR — pending/success/refunded/expired.',
      curl: this.curlQuery(), curlKey: 'qry', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-refund', label: 'Refund', product: 'qris-refund',
      desc: 'Refund QR yang sudah dibayar (full/partial).',
      curl: this.curlRefund(), curlKey: 'rfd', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-cancel', label: 'Cancel Payment', product: 'qris-cancel',
      desc: 'Batalkan QR yang belum dibayar (service 77).',
      curl: this.curlCancel(), curlKey: 'cnl', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-decode', label: 'Decode QR', product: 'qris-decode',
      desc: 'Decode QR content → merchant info.',
      curl: this.curlDecode(), curlKey: 'dcd', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-payment', label: 'Payment H2H', product: 'qris-payment',
      desc: 'Host-to-host payment — customer bayar QR.',
      curl: this.curlPayment(), curlKey: 'pay', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
    {
      key: 'qris-apply-ott', label: 'Apply OTT', product: 'qris-apply-ott',
      desc: 'One-time token untuk redirect payment.',
      curl: this.curlOtt(), curlKey: 'ott', scenarioList: [{ name: 'Normal', desc: 'Response standar SNAP.', icon: 'check_circle', tone: 'ok' }],
    },
  ];

  copy(text: string, key: string) {
    navigator.clipboard?.writeText(text).then(() => {
      this.copiedKey.set(key);
      setTimeout(() => this.copiedKey.set(null), 1500);
    });
  }

  curlDynamic(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-generate \\
  -H "X-PARTNER-ID: PARTNER001" -H "X-CALLBACK-URL: http://localhost:8080/api/admin/v1/webhook-sink" \\
  -d '{"partnerReferenceNo":"PR-001","merchantId":"MERCHANT01","amount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlQuery(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-query \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<referenceNo>","serviceCode":"47"}'`;
  }

  curlRefund(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-refund \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<refNo>","partnerRefundNo":"RF-001","refundAmount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlCancel(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-cancel \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"originalReferenceNo":"<refNo>","merchantId":"MERCHANT01","reason":"cancel reason"}'`;
  }

  curlDecode(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-decode \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerReferenceNo":"PR-003","qrContent":"0002010102...","merchantId":"MERCHANT01","scanTime":"2023-01-15T10:00:00+07:00"}'`;
  }

  curlPayment(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/qr-mpm-payment \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"partnerReferenceNo":"PAY-001","merchantId":"MERCHANT01","amount":{"value":"25000.00","currency":"IDR"}}'`;
  }

  curlOtt(): string {
    return `curl -X POST http://localhost:${this.port()}/v1.0/qr/apply-ott \\
  -H "X-PARTNER-ID: PARTNER001" \\
  -d '{"userResources":["OTT"]}'`;
  }

  // ---- lifecycle ----

  ngOnInit() { this.reload(); }

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
        if (this.selectedSimId()) { this.reloadQr(); this.syncAllScenarios(); this.connectLive(); }
        else this.disconnectLive();
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
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

  removeSelected() {
    const s = this.selectedSim; if (!s) return;
    this.confirmDialog({
      title: 'Hapus profil?',
      message: `Profil "${s.name}" (port ${s.port}) akan dihapus permanen beserta QR, partner, dan rekeningnya. Tindakan ini tidak bisa dibatalkan.`,
      confirmText: 'Hapus',
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
      this.api.getActiveScenario(this.selectedSimId(), ep.product).subscribe({
        next: r => this.activeScenarios.update(m => ({ ...m, [ep.key]: r.name })),
        error: () => this.activeScenarios.update(m => ({ ...m, [ep.key]: 'Normal' })),
      });
    }
  }

  activeScenarioFor(key: string): string { return this.activeScenarios()[key] ?? 'Normal'; }

  changeScenario(ep: EpMeta, name: string) {
    this.activeScenarios.update(m => ({ ...m, [ep.key]: name }));
    this.api.setScenario(this.selectedSimId(), name, ep.product).subscribe();
  }

  openEditor(ep: EpMeta) {
    const scenario = this.activeScenarioFor(ep.key);
    this.editorError.set(''); this.editorSaved.set(false); this.editorLoading.set(true);
    this.api.getDefinition(this.selectedSimId(), scenario, ep.product as any).subscribe({
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
    this.api.saveDefinition(this.selectedSimId(), this.editorScenario(), this.editorText(), ep.product as any).subscribe({
      next: () => { this.editorSaved.set(true); this.editorError.set(''); },
      error: err => this.editorError.set(err?.error?.error ?? 'JSON tidak valid.'),
    });
  }

  resetEditor(ep: EpMeta) {
    this.api.resetDefinition(this.selectedSimId(), this.editorScenario(), ep.product as any).subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(this.selectedSimId(), this.editorScenario(), ep.product as any).subscribe(t => this.editorText.set(t));
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

  pay(qr: QrisView) {
    const amount = qr.qrType === 'STATIC' ? this.payAmounts()[qr.referenceNo] : undefined;
    if (qr.qrType === 'STATIC' && !amount) { this.qrMsg.set('QR static butuh nominal.'); return; }
    const nominal = qr.qrType === 'STATIC' ? `${amount} ${qr.currency}` : `${qr.amount} ${qr.currency}`;
    this.confirmDialog({
      title: 'Tandai QR sebagai dibayar?',
      message: `QR "${qr.referenceNo}" akan berstatus PAID sebesar ${nominal}`
        + (qr.hasCallback ? ', dan Payment Notify (webhook) dikirim ke callback URL-nya.' : '. Tidak ada callback URL tersimpan, jadi webhook tidak dikirim.'),
      confirmText: 'Tandai Dibayar',
    }).subscribe(ok => {
      if (!ok) return;
      this.api.payQris(this.selectedSimId(), qr.referenceNo, amount).subscribe({
        next: r => { this.qrMsg.set(r.webhookSent ? 'Payment Notify terkirim.' : r.note); this.reloadQr(true); },
        error: () => this.qrMsg.set('Gagal.'),
      });
    });
  }

  expireQr(qr: QrisView) {
    this.confirmDialog({
      title: 'Kedaluwarsakan QR?',
      message: `QR "${qr.referenceNo}" akan berstatus EXPIRED dan tidak bisa dibayar lagi.`,
      confirmText: 'Kedaluwarsakan',
      danger: true,
    }).subscribe(ok => {
      if (!ok) return;
      this.api.expireQris(this.selectedSimId(), qr.referenceNo).subscribe({
        next: r => { this.qrMsg.set(r.note); this.reloadQr(true); },
        error: () => this.qrMsg.set('Gagal.'),
      });
    });
  }

  statusTone(s: string): 'ok' | 'warn' | 'fault' {
    if (s === 'PAID') return 'ok'; if (s === 'REFUNDED') return 'warn'; if (s === 'EXPIRED') return 'fault'; return 'warn';
  }
}
