import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { AccountView, PartnerView, SCENARIOS, Scenario, Simulator, SimulatorService, VirtualAccountView } from './simulator.service';
import { SimulatorFormDialog, SimulatorFormResult } from './simulator-form-dialog';

interface LiveEvent {
  method: string;
  path: string;
  httpStatus: number;
  responseCode: string;
  durationMillis: number;
  at: string;
}

@Component({
  selector: 'app-simulators',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatDialogModule,
    MatDividerModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatMenuModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './simulators.html',
  styleUrl: './simulators.scss',
})
export class Simulators implements OnInit, OnDestroy {
  private readonly api = inject(SimulatorService);
  private readonly dialog = inject(MatDialog);

  readonly scenarios = SCENARIOS;
  readonly sims = signal<Simulator[]>([]);
  readonly loading = signal(true);
  readonly selected = signal<Record<string, string>>({});
  readonly liveOpen = signal<string | null>(null);
  readonly events = signal<LiveEvent[]>([]);
  private es?: EventSource;

  // editor request/response
  readonly editing = signal<string | null>(null);
  readonly editorScenario = signal<string>('');
  readonly editorText = signal<string>('');
  readonly editorError = signal<string>('');
  readonly editorSaved = signal<boolean>(false);

  // panel Virtual Account
  readonly vaOpen = signal<string | null>(null);
  readonly vaList = signal<VirtualAccountView[]>([]);
  readonly vaLoading = signal(false);
  readonly vaMsg = signal<string>('');

  // panel Partner & Rekening
  readonly bankOpen = signal<string | null>(null);
  readonly partnerList = signal<PartnerView[]>([]);
  readonly accountList = signal<AccountView[]>([]);
  readonly bankMsg = signal<string>('');
  readonly newPartnerId = signal('');
  readonly newPartnerSecret = signal('');
  readonly newAccPartnerRowId = signal('');
  readonly newAccNo = signal('');
  readonly newAccHolder = signal('');
  readonly newAccBalance = signal('0');
  readonly balanceEdits = signal<Record<string, string>>({});

  ngOnInit() {
    this.reload();
  }

  ngOnDestroy() {
    this.closeLive();
  }

  reload() {
    this.loading.set(true);
    this.api.list().subscribe({
      next: sims => {
        this.sims.set(sims);
        // default pilihan scenario = Normal jika belum dipilih
        const sel = { ...this.selected() };
        sims.forEach(s => (sel[s.id] ??= 'Normal'));
        this.selected.set(sel);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  scenarioFor(id: string): string {
    return this.selected()[id] ?? 'Normal';
  }

  scenarioMeta(name: string): Scenario | undefined {
    return this.scenarios.find(s => s.name === name);
  }

  start(s: Simulator) {
    this.api.start(s.id).subscribe(() => this.reload());
  }

  stop(s: Simulator) {
    this.api.stop(s.id).subscribe(() => {
      if (this.liveOpen() === s.id) {
        this.closeLive();
      }
      this.reload();
    });
  }

  changeScenario(s: Simulator, name: string) {
    this.selected.update(m => ({ ...m, [s.id]: name }));
    this.api.setScenario(s.id, name).subscribe();
  }

  toggleLive(s: Simulator) {
    if (this.liveOpen() === s.id) {
      this.closeLive();
      return;
    }
    this.closeLive();
    this.events.set([]);
    this.liveOpen.set(s.id);
    this.es = new EventSource(this.api.streamUrl(s.id));
    this.es.addEventListener('request', (e: MessageEvent) => {
      try {
        const d = JSON.parse(e.data);
        const ev: LiveEvent = {
          method: d.method,
          path: d.path,
          httpStatus: d.httpStatus,
          responseCode: d.responseCode,
          durationMillis: d.durationMillis,
          at: new Date().toLocaleTimeString(),
        };
        this.events.update(list => [ev, ...list].slice(0, 50));
      } catch {
        /* abaikan payload tak valid */
      }
    });
  }

  closeLive() {
    this.es?.close();
    this.es = undefined;
    this.liveOpen.set(null);
  }

  openEditor(s: Simulator) {
    const scenario = this.scenarioFor(s.id);
    this.editorError.set('');
    this.editorSaved.set(false);
    this.editorScenario.set(scenario);
    this.api.getDefinition(s.id, scenario).subscribe({
      next: text => {
        this.editorText.set(text);
        this.editing.set(s.id);
      },
      error: () => this.editorError.set('Gagal memuat definisi.'),
    });
  }

  onEditorInput(value: string) {
    this.editorText.set(value);
    this.editorSaved.set(false);
    this.editorError.set('');
  }

  saveEditor(s: Simulator) {
    this.api.saveDefinition(s.id, this.editorScenario(), this.editorText()).subscribe({
      next: () => {
        this.editorSaved.set(true);
        this.editorError.set('');
      },
      error: err => this.editorError.set(err?.error?.error ?? 'JSON tidak valid.'),
    });
  }

  resetEditor(s: Simulator) {
    this.api.resetDefinition(s.id, this.editorScenario()).subscribe(() => {
      this.editorSaved.set(false);
      this.api.getDefinition(s.id, this.editorScenario()).subscribe(text => this.editorText.set(text));
    });
  }

  closeEditor() {
    this.editing.set(null);
    this.editorText.set('');
  }

  isSuccess(code: string) {
    return code?.startsWith('200');
  }

  toggleVaPanel(s: Simulator) {
    if (this.vaOpen() === s.id) {
      this.vaOpen.set(null);
      return;
    }
    this.vaOpen.set(s.id);
    this.reloadVa(s);
  }

  reloadVa(s: Simulator) {
    this.vaLoading.set(true);
    this.vaMsg.set('');
    this.api.listVirtualAccounts(s.id).subscribe({
      next: list => {
        this.vaList.set(list);
        this.vaLoading.set(false);
      },
      error: () => this.vaLoading.set(false),
    });
  }

  payVa(s: Simulator, va: VirtualAccountView) {
    this.api.payVirtualAccount(s.id, va.virtualAccountNo).subscribe({
      next: r => {
        this.vaMsg.set(r.webhookSent ? 'Payment Notification terkirim ke callback URL.' : r.note);
        this.reloadVa(s);
      },
      error: () => this.vaMsg.set('Gagal menandai VA dibayar.'),
    });
  }

  /** Port bebas berikutnya (mulai 9001), berguna sebagai saran di form. */
  private suggestedPort(): number {
    const used = new Set(this.sims().map(s => s.port));
    let p = 9001;
    while (used.has(p)) p++;
    return p;
  }

  openCreate() {
    const ref = this.dialog.open(SimulatorFormDialog, {
      data: { mode: 'create', suggestedPort: this.suggestedPort() },
    });
    ref.afterClosed().subscribe((result?: SimulatorFormResult) => {
      if (!result) return;
      this.api.create(result.name, result.port, result.signatureMode).subscribe({
        next: () => this.reload(),
        error: err => alert(err?.error?.error ?? 'Gagal membuat simulator.'),
      });
    });
  }

  openClone(s: Simulator) {
    const ref = this.dialog.open(SimulatorFormDialog, {
      data: { mode: 'clone', sourceName: s.name, suggestedPort: this.suggestedPort() },
    });
    ref.afterClosed().subscribe((result?: SimulatorFormResult) => {
      if (!result) return;
      this.api.clone(s.id, result.name, result.port).subscribe({
        next: () => this.reload(),
        error: err => alert(err?.error?.error ?? 'Gagal menduplikat simulator.'),
      });
    });
  }

  remove(s: Simulator) {
    if (!confirm(`Hapus simulator "${s.name}"? Semua konfigurasi & data rekening ikut terhapus.`)) {
      return;
    }
    this.api.delete(s.id).subscribe(() => this.reload());
  }

  toggleBankPanel(s: Simulator) {
    if (this.bankOpen() === s.id) {
      this.bankOpen.set(null);
      return;
    }
    this.bankOpen.set(s.id);
    this.reloadBank(s);
  }

  reloadBank(s: Simulator) {
    this.bankMsg.set('');
    this.api.listPartners(s.id).subscribe(list => {
      this.partnerList.set(list);
      if (!this.newAccPartnerRowId() && list.length > 0) {
        this.newAccPartnerRowId.set(list[0].id);
      }
    });
    this.api.listAccounts(s.id).subscribe(list => {
      this.accountList.set(list);
      const edits: Record<string, string> = {};
      list.forEach(a => (edits[a.id] = a.balance));
      this.balanceEdits.set(edits);
    });
  }

  addPartner(s: Simulator) {
    const partnerId = this.newPartnerId().trim();
    if (!partnerId) return;
    this.api.createPartner(s.id, partnerId, this.newPartnerSecret().trim() || undefined).subscribe({
      next: () => {
        this.newPartnerId.set('');
        this.newPartnerSecret.set('');
        this.bankMsg.set(`Partner "${partnerId}" ditambahkan.`);
        this.reloadBank(s);
      },
      error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal menambah partner.'),
    });
  }

  removePartner(s: Simulator, p: PartnerView) {
    if (!confirm(`Hapus partner "${p.partnerId}"? Rekening miliknya ikut terhapus.`)) return;
    this.api.deletePartner(s.id, p.id).subscribe(() => this.reloadBank(s));
  }

  addAccount(s: Simulator) {
    const accNo = this.newAccNo().trim();
    if (!accNo || !this.newAccPartnerRowId()) return;
    this.api
      .createAccount(s.id, this.newAccPartnerRowId(), accNo, this.newAccHolder().trim(), this.newAccBalance())
      .subscribe({
        next: () => {
          this.newAccNo.set('');
          this.newAccHolder.set('');
          this.newAccBalance.set('0');
          this.bankMsg.set(`Rekening "${accNo}" ditambahkan.`);
          this.reloadBank(s);
        },
        error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal menambah rekening.'),
      });
  }

  onBalanceEdit(accountId: string, value: string) {
    this.balanceEdits.update(m => ({ ...m, [accountId]: value }));
  }

  saveBalance(s: Simulator, a: AccountView) {
    const value = this.balanceEdits()[a.id];
    this.api.setAccountBalance(s.id, a.id, value).subscribe({
      next: () => {
        this.bankMsg.set(`Saldo ${a.accountNo} diperbarui.`);
        this.reloadBank(s);
      },
      error: err => this.bankMsg.set(err?.error?.error ?? 'Gagal mengubah saldo.'),
    });
  }

  removeAccount(s: Simulator, a: AccountView) {
    if (!confirm(`Hapus rekening "${a.accountNo}"?`)) return;
    this.api.deleteAccount(s.id, a.id).subscribe(() => this.reloadBank(s));
  }
}
