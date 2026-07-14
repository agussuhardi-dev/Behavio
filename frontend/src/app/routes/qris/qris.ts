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

interface EpMeta {
  key: string; label: string; desc: string; product: string;
  curl: string; curlKey: string; scenarioList: Scenario[];
}

@Component({
  selector: 'app-qris',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule, MatCardModule, MatDialogModule, MatDividerModule,
    MatExpansionModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatMenuModule, MatProgressBarModule, MatSelectModule, MatTooltipModule,
    EndpointUrlPanel,
  ],
  templateUrl: './qris.html',
  styleUrl: './qris.scss',
})
export class Qris implements OnInit {
  private readonly api = inject(SimulatorService);
  private readonly dialog = inject(MatDialog);

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

  reload() {
    this.loading.set(true);
    this.api.list().subscribe({
      next: sims => {
        this.sims.set(sims);
        const keep = sims.some(s => s.id === this.selectedSimId());
        if (!keep && sims.length > 0) this.selectedSimId.set(sims[0].id);
        if (sims.length === 0) this.selectedSimId.set('');
        if (this.selectedSimId()) { this.reloadQr(); this.syncAllScenarios(); }
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
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
      this.api.create(r.name, r.port, r.signatureMode).subscribe({
        next: c => { this.selectedSimId.set(c.id); this.reload(); },
        error: err => alert(err?.error?.error ?? 'Gagal membuat profil.'),
      });
    });
  }

  openClone() {
    const s = this.selectedSim; if (!s) return;
    const ref = this.dialog.open(SimulatorFormDialog, { data: { mode: 'clone', sourceName: s.name, suggestedPort: this.suggestedPort() } });
    ref.afterClosed().subscribe((r?: SimulatorFormResult) => {
      if (!r) return;
      this.api.clone(s.id, r.name, r.port).subscribe({
        next: c => { this.selectedSimId.set(c.id); this.reload(); },
        error: err => alert(err?.error?.error ?? 'Gagal menduplikat profil.'),
      });
    });
  }

  removeSelected() {
    const s = this.selectedSim; if (!s) return;
    if (!confirm(`Hapus profil "${s.name}"?`)) return;
    this.api.delete(s.id).subscribe(() => { this.selectedSimId.set(''); this.reload(); });
  }

  toggleRunning() {
    const s = this.selectedSim; if (!s) return;
    (s.status === 'RUNNING' ? this.api.stop(s.id) : this.api.start(s.id)).subscribe(() => this.reload());
  }

  onSelectSim(id: string) {
    this.selectedSimId.set(id);
    this.editingEp.set(null);
    this.reloadQr();
    this.syncAllScenarios();
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

  reloadQr() {
    if (!this.selectedSimId()) return;
    this.qrLoading.set(true); this.qrMsg.set('');
    this.api.listQris(this.selectedSimId()).subscribe({
      next: list => { this.qrList.set(list); this.qrLoading.set(false); },
      error: () => this.qrLoading.set(false),
    });
  }

  onPayAmount(refNo: string, value: string) { this.payAmounts.update(m => ({ ...m, [refNo]: value })); }

  pay(qr: QrisView) {
    const amount = qr.qrType === 'STATIC' ? this.payAmounts()[qr.referenceNo] : undefined;
    if (qr.qrType === 'STATIC' && !amount) { this.qrMsg.set('QR static butuh nominal.'); return; }
    this.api.payQris(this.selectedSimId(), qr.referenceNo, amount).subscribe({
      next: r => { this.qrMsg.set(r.webhookSent ? 'Payment Notify terkirim.' : r.note); this.reloadQr(); },
      error: () => this.qrMsg.set('Gagal.'),
    });
  }

  expireQr(qr: QrisView) {
    if (!confirm(`Kedaluwarsakan QR "${qr.referenceNo}"?`)) return;
    this.api.expireQris(this.selectedSimId(), qr.referenceNo).subscribe({
      next: r => { this.qrMsg.set(r.note); this.reloadQr(); },
      error: () => this.qrMsg.set('Gagal.'),
    });
  }

  statusTone(s: string): 'ok' | 'warn' | 'fault' {
    if (s === 'PAID') return 'ok'; if (s === 'REFUNDED') return 'warn'; if (s === 'EXPIRED') return 'fault'; return 'warn';
  }
}
