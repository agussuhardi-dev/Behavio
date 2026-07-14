import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
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

@Component({
  selector: 'app-qris',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatDividerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './qris.html',
  styleUrl: './qris.scss',
})
export class Qris implements OnInit {
  private readonly api = inject(SimulatorService);

  readonly scenarios = QRIS_SCENARIOS;
  readonly sims = signal<Simulator[]>([]);
  readonly loading = signal(true);
  readonly selectedSimId = signal<string>('');
  readonly activeScenario = signal<string>('Normal');

  readonly qrList = signal<QrisView[]>([]);
  readonly qrLoading = signal(false);
  readonly qrMsg = signal('');
  readonly payAmounts = signal<Record<string, string>>({});

  readonly editing = signal(false);
  readonly editorText = signal('');
  readonly editorError = signal('');
  readonly editorSaved = signal(false);

  ngOnInit() {
    this.loading.set(true);
    this.api.list().subscribe({
      next: sims => {
        this.sims.set(sims);
        if (sims.length > 0) {
          this.selectedSimId.set(sims[0].id);
          this.reloadQr();
        }
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  get selectedSim(): Simulator | undefined {
    return this.sims().find(s => s.id === this.selectedSimId());
  }

  onSelectSim(id: string) {
    this.selectedSimId.set(id);
    this.editing.set(false);
    this.reloadQr();
  }

  scenarioMeta(name: string): Scenario | undefined {
    return this.scenarios.find(s => s.name === name);
  }

  changeScenario(name: string) {
    this.activeScenario.set(name);
    this.api.setScenario(this.selectedSimId(), name, 'qris').subscribe();
  }

  reloadQr() {
    if (!this.selectedSimId()) return;
    this.qrLoading.set(true);
    this.qrMsg.set('');
    this.api.listQris(this.selectedSimId()).subscribe({
      next: list => {
        this.qrList.set(list);
        this.qrLoading.set(false);
      },
      error: () => this.qrLoading.set(false),
    });
  }

  onPayAmount(refNo: string, value: string) {
    this.payAmounts.update(m => ({ ...m, [refNo]: value }));
  }

  pay(qr: QrisView) {
    const amount = qr.qrType === 'STATIC' ? this.payAmounts()[qr.referenceNo] : undefined;
    if (qr.qrType === 'STATIC' && !amount) {
      this.qrMsg.set('QR static butuh nominal saat bayar — isi kolom nominal dahulu.');
      return;
    }
    this.api.payQris(this.selectedSimId(), qr.referenceNo, amount).subscribe({
      next: r => {
        this.qrMsg.set(r.webhookSent ? 'Payment Notify terkirim ke callback URL.' : r.note);
        this.reloadQr();
      },
      error: () => this.qrMsg.set('Gagal menandai QR dibayar.'),
    });
  }

  openEditor() {
    this.editorError.set('');
    this.editorSaved.set(false);
    this.api.getDefinition(this.selectedSimId(), this.activeScenario(), 'qris').subscribe({
      next: text => {
        this.editorText.set(text);
        this.editing.set(true);
      },
      error: () => this.editorError.set('Gagal memuat definisi.'),
    });
  }

  closeEditor() {
    this.editing.set(false);
  }

  onEditorInput(value: string) {
    this.editorText.set(value);
    this.editorSaved.set(false);
    this.editorError.set('');
  }

  saveEditor() {
    this.api.saveDefinition(this.selectedSimId(), this.activeScenario(), this.editorText(), 'qris').subscribe({
      next: () => {
        this.editorSaved.set(true);
        this.editorError.set('');
      },
      error: err => this.editorError.set(err?.error?.error ?? 'JSON tidak valid.'),
    });
  }

  resetEditor() {
    this.api.resetDefinition(this.selectedSimId(), this.activeScenario(), 'qris').subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(this.selectedSimId(), this.activeScenario(), 'qris').subscribe(text => this.editorText.set(text));
    });
  }

  statusTone(status: string): 'ok' | 'warn' | 'fault' {
    if (status === 'PAID') return 'ok';
    if (status === 'REFUNDED') return 'warn';
    if (status === 'EXPIRED') return 'fault';
    return 'warn';
  }
}
