import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
import { TranslateService } from '@ngx-translate/core';
import { Observable } from 'rxjs';

import { OpenApiImportResult, ProductApi, Simulator } from '../../core/api/product-api';
import { ConfirmDialog } from '../components/confirm-dialog/confirm-dialog';
import { OpenApiImportDialog } from '../components/openapi-import-dialog/openapi-import-dialog';

/**
 * Export/import OpenAPI dari dashboard (design.md §15).
 *
 * Ditaruh di service, bukan di dua komponen: halaman bank & QRIS memakai alur yang
 * identik — persis seperti Admin API-nya yang satu set untuk kedua produk. Yang berbeda
 * hanya `ProductApi` yang dioper.
 */
@Injectable({ providedIn: 'root' })
export class OpenApiService {
  private readonly dialog = inject(MatDialog);
  private readonly translate = inject(TranslateService);

  /** Unduh spec sebagai file. Nama file mengikuti Content-Disposition dari server. */
  export(api: ProductApi, sim: Simulator, format: 'yaml' | 'json' = 'yaml') {
    api.exportOpenApi(sim.id, format).subscribe({
      next: res => {
        const blob = res.body;
        if (!blob) return;
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = this.filenameFrom(res.headers.get('content-disposition'), api.product, sim, format);
        a.click();
        URL.revokeObjectURL(url);
      },
      error: () =>
        this.alert(
          this.translate.instant('openapi.export_failed_title'),
          this.translate.instant('openapi.export_failed_body')
        ),
    });
  }

  /**
   * Buka dialog impor. Emit hasil hanya bila benar-benar diterapkan, agar pemanggil tahu
   * kapan perlu memuat ulang endpoint/scenario.
   */
  openImport(
    api: ProductApi,
    sim: Simulator,
    operations: { key: string; label: string }[]
  ): Observable<OpenApiImportResult | undefined> {
    return this.dialog
      .open(OpenApiImportDialog, {
        data: { api, simulatorId: sim.id, simulatorName: sim.name, operations },
        width: '60rem',
        maxWidth: '92vw',
        autoFocus: false,
      })
      .afterClosed();
  }

  /** Ringkasan hasil impor — termasuk yang gagal, bukan cuma yang berhasil. */
  summarize(result: OpenApiImportResult): string {
    const parts: string[] = [];
    if (result.overridden) parts.push(this.translate.instant('openapi.sum_overridden', { count: result.overridden }));
    if (result.created) parts.push(this.translate.instant('openapi.sum_created', { count: result.created }));
    if (result.scenariosRestored) parts.push(this.translate.instant('openapi.sum_restored', { count: result.scenariosRestored }));
    if (result.skipped) parts.push(this.translate.instant('openapi.sum_skipped', { count: result.skipped }));
    const head = parts.length ? parts.join(' · ') : this.translate.instant('openapi.sum_nothing');
    return result.messages.length ? `${head}\n\n${result.messages.join('\n')}` : head;
  }

  showResult(result: OpenApiImportResult) {
    return this.alert(
      this.translate.instant(result.messages.length ? 'openapi.import_done_notes' : 'openapi.import_done'),
      this.summarize(result)
    );
  }

  private alert(title: string, message: string) {
    return this.dialog
      .open(ConfirmDialog, {
        data: { title, message, confirmText: this.translate.instant('common.close'), cancelText: '' },
        width: '32rem',
        autoFocus: false,
      })
      .afterClosed();
  }

  private filenameFrom(
    disposition: string | null,
    product: string,
    sim: Simulator,
    format: string
  ): string {
    const match = disposition?.match(/filename="?([^"]+)"?/);
    return match?.[1] ?? `behavio-${product}-${sim.id}.${format}`;
  }
}
