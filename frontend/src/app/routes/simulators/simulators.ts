import { Component, OnDestroy, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatDividerModule } from '@angular/material/divider';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatMenuModule } from '@angular/material/menu';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { SCENARIOS, Scenario, Simulator, SimulatorService } from './simulator.service';
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
}
