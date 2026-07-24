import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';

import {
  OpenApiAction,
  OpenApiImportResult,
  OpenApiMapping,
  OpenApiPreviewRow,
  ProductApi,
} from '../../../core/api/product-api';

export interface OpenApiImportData {
  api: ProductApi;
  simulatorId: string;
  simulatorName: string;
  /** Operasi katalog yang tersedia untuk dipetakan (kunci + label). */
  operations: { key: string; label: string }[];
}

/** Satu baris pratinjau + keputusan user atasnya. */
interface Row extends OpenApiPreviewRow {
  action: OpenApiAction;
  operation: string;
}

/**
 * Impor OpenAPI (design.md §15.4): upload → pratinjau → user memetakan → terapkan.
 *
 * Pratinjau sengaja tak menyentuh server: path bank berbeda-beda, jadi sistem hanya
 * MENYARANKAN operasi dan menampilkan dari mana tebakan itu datang. Tebakan yang salah
 * dan diterapkan diam-diam akan meng-override path operasi lain tanpa peringatan.
 */
@Component({
  selector: 'app-openapi-import-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    MatTooltipModule,
    TranslatePipe,
  ],
  templateUrl: './openapi-import-dialog.html',
  styleUrl: './openapi-import-dialog.scss',
})
export class OpenApiImportDialog {
  readonly dialogRef = inject(MatDialogRef<OpenApiImportDialog, OpenApiImportResult>);
  readonly data = inject<OpenApiImportData>(MAT_DIALOG_DATA);
  private readonly translate = inject(TranslateService);

  readonly fileName = signal('');
  readonly sourceTitle = signal('');
  readonly rows = signal<Row[]>([]);
  readonly busy = signal(false);
  readonly error = signal('');

  private spec = '';

  readonly applyCount = computed(() => this.rows().filter(r => r.action !== 'SKIP').length);

  readonly hasDuplicateOperation = computed(() => {
    const used = this.rows()
      .filter(r => r.action === 'CATALOG' && r.operation)
      .map(r => r.operation);
    return new Set(used).size !== used.length;
  });

  async pick(event: Event) {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) return;
    this.fileName.set(file.name);
    this.error.set('');
    this.spec = await file.text();
    this.preview();
    input.value = ''; // agar memilih file yang sama lagi tetap memicu change
  }

  private preview() {
    this.busy.set(true);
    this.data.api.previewOpenApi(this.data.simulatorId, this.spec).subscribe({
      next: p => {
        this.sourceTitle.set(p.sourceTitle);
        this.rows.set(
          p.rows.map(r => ({
            ...r,
            // Default aman: hanya baris dengan tebakan yang otomatis dipetakan.
            // Sisanya SKIP — user memutuskan sendiri, bukan diam-diam dibuat.
            action: r.suggestedOperation ? ('CATALOG' as const) : ('SKIP' as const),
            operation: r.suggestedOperation,
          }))
        );
        this.busy.set(false);
      },
      error: err => {
        this.error.set(err?.error?.error ?? this.translate.instant('openapi.spec_unreadable'));
        this.rows.set([]);
        this.busy.set(false);
      },
    });
  }

  setAction(row: Row, action: OpenApiAction) {
    this.rows.update(rows =>
      rows.map(r =>
        r === row ? { ...r, action, operation: action === 'CATALOG' ? r.operation : '' } : r
      )
    );
  }

  setOperation(row: Row, operation: string) {
    this.rows.update(rows =>
      rows.map(r => (r === row ? { ...r, operation, action: 'CATALOG' as const } : r))
    );
  }

  /** Operasi yang sudah dipakai baris lain — ditandai agar bentrok terlihat sebelum apply. */
  isDuplicate(row: Row): boolean {
    if (row.action !== 'CATALOG' || !row.operation) return false;
    return this.rows().filter(r => r.action === 'CATALOG' && r.operation === row.operation).length > 1;
  }

  confidenceIcon(row: Row): string {
    if (row.confidence.startsWith('pasti')) return 'verified';
    if (row.confidence === 'tak ada tebakan') return 'help_outline';
    return 'lightbulb';
  }

  apply() {
    const mappings: OpenApiMapping[] = this.rows().map(r => ({
      path: r.path,
      method: r.method,
      action: r.action,
      operation: r.operation,
    }));
    this.busy.set(true);
    this.data.api.importOpenApi(this.data.simulatorId, this.spec, mappings).subscribe({
      next: result => this.dialogRef.close(result),
      error: err => {
        this.error.set(err?.error?.error ?? this.translate.instant('openapi.import_failed'));
        this.busy.set(false);
      },
    });
  }
}
