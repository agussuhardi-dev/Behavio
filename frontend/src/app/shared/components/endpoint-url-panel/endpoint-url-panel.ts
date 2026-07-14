import { CommonModule } from '@angular/common';
import { Component, OnChanges, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatOptionModule } from '@angular/material/core';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { EndpointConfig, SimulatorService } from '../../../routes/simulators/simulator.service';

@Component({
  selector: 'app-endpoint-url-panel',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatFormFieldModule, MatIconModule, MatInputModule,
            MatOptionModule, MatSelectModule, MatTooltipModule],
  templateUrl: './endpoint-url-panel.html',
  styleUrl: './endpoint-url-panel.scss',
})
export class EndpointUrlPanel implements OnChanges {
  private readonly api = inject(SimulatorService);

  readonly simulatorId = input.required<string>();
  readonly operations = input.required<string[]>();

  readonly rows = signal<EndpointConfig[]>([]);
  readonly edits = signal<Record<string, string>>({});
  readonly msg = signal('');
  readonly loading = signal(false);
  readonly headersOpen = signal<string | null>(null);
  readonly headerKeys = signal<Record<string, string>>({});
  readonly headerVals = signal<Record<string, string>>({});
  readonly newHdrKey = signal('');
  readonly newHdrVal = signal('');

  readonly newMethod = signal('POST');
  readonly newPath = signal('');

  ngOnChanges() {
    this.reload();
  }

  reload() {
    if (!this.simulatorId()) return;
    this.loading.set(true);
    this.api.listEndpoints(this.simulatorId()).subscribe({
      next: list => {
        const filtered = list.filter(e => this.operations().includes(e.operation));
        this.rows.set(filtered);
        const edits: Record<string, string> = {};
        filtered.forEach(e => (edits[e.operation] = e.path));
        this.edits.set(edits);
        this.loading.set(false);
      },
      error: () => this.loading.set(false),
    });
  }

  onEdit(operation: string, value: string) {
    this.edits.update(m => ({ ...m, [operation]: value }));
  }

  isCustom(row: EndpointConfig): boolean {
    return row.path !== row.defaultPath;
  }

  save(row: EndpointConfig) {
    const newPath = this.edits()[row.operation];
    this.api.updateEndpointPath(this.simulatorId(), row.operation, newPath).subscribe({
      next: () => { this.msg.set(`URL "${row.label}" diperbarui.`); this.reload(); },
      error: err => this.msg.set(err?.error?.error ?? 'Gagal menyimpan URL.'),
    });
  }

  reset(row: EndpointConfig) {
    this.api.updateEndpointPath(this.simulatorId(), row.operation, row.defaultPath).subscribe({
      next: () => { this.msg.set(`URL "${row.label}" dikembalikan ke default.`); this.reload(); },
      error: () => this.msg.set('Gagal reset URL.'),
    });
  }

  deleteEndpoint(row: EndpointConfig) {
    if (!row.operation) return;
    if (!confirm(`Hapus endpoint "${row.label}"?`)) return;
    this.api.deleteEndpoint(this.simulatorId(), row.operation).subscribe({
      next: () => { this.msg.set(`Endpoint "${row.label}" dihapus.`); this.reload(); },
      error: err => this.msg.set(err?.error?.error ?? 'Gagal menghapus endpoint.'),
    });
  }

  addEndpoint() {
    const path = this.newPath().trim();
    if (!path) return;
    this.api.addEndpoint(this.simulatorId(), this.newMethod(), path).subscribe({
      next: () => { this.msg.set('Endpoint ditambahkan.'); this.newPath.set(''); this.reload(); },
      error: err => this.msg.set(err?.error?.error ?? 'Gagal menambah endpoint.'),
    });
  }

  // ---- header editor ----

  toggleHeaders(row: EndpointConfig) {
    if (this.headersOpen() === row.operation) {
      this.headersOpen.set(null);
      return;
    }
    this.headersOpen.set(row.operation);
    try {
      const obj = row.headers ? JSON.parse(row.headers) : {};
      const keys: Record<string, string> = {};
      const vals: Record<string, string> = {};
      Object.keys(obj).forEach((k, i) => {
        keys[i] = k;
        vals[i] = obj[k];
      });
      this.headerKeys.set(keys);
      this.headerVals.set(vals);
    } catch {
      this.headerKeys.set({});
      this.headerVals.set({});
    }
    this.newHdrKey.set('');
    this.newHdrVal.set('');
  }

  addHeader() {
    const k = this.newHdrKey().trim();
    if (!k) return;
    const idx = Object.keys(this.headerKeys()).length;
    this.headerKeys.update(m => ({ ...m, [idx]: k }));
    this.headerVals.update(m => ({ ...m, [idx]: this.newHdrVal() }));
    this.newHdrKey.set('');
    this.newHdrVal.set('');
  }

  removeHeader(key: string) {
    const newKeys: Record<string, string> = {};
    const newVals: Record<string, string> = {};
    const keys = this.headerKeys();
    const vals = this.headerVals();
    for (const k of Object.keys(keys)) {
      if (k !== key) {
        newKeys[k] = keys[k];
        newVals[k] = vals[k];
      }
    }
    this.headerKeys.set(newKeys);
    this.headerVals.set(newVals);
  }

  saveHeaders(row: EndpointConfig) {
    const obj: Record<string, string> = {};
    const keys = this.headerKeys();
    const vals = this.headerVals();
    Object.keys(keys).forEach(k => {
      obj[keys[k]] = vals[k] || '';
    });
    const json = Object.keys(obj).length > 0 ? JSON.stringify(obj) : '';
    this.api.updateEndpointMeta(this.simulatorId(), row.operation, row.method, json).subscribe({
      next: () => {
        this.msg.set(`Headers "${row.label}" diperbarui.`);
        this.headersOpen.set(null);
        this.reload();
      },
      error: err => this.msg.set(err?.error?.error ?? 'Gagal menyimpan headers.'),
    });
  }

  onHeaderKey(key: string, val: string) {
    this.headerKeys.update(m => ({ ...m, [key]: val }));
  }

  onHeaderVal(key: string, val: string) {
    this.headerVals.update(m => ({ ...m, [key]: val }));
  }
}
