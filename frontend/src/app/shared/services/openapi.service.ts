import { Injectable, inject } from '@angular/core';
import { MatDialog } from '@angular/material/dialog';
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
      error: () => this.alert('Export gagal', 'Spec tidak bisa dibuat. Periksa log server.'),
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
    if (result.overridden) parts.push(`${result.overridden} path operasi di-override`);
    if (result.created) parts.push(`${result.created} endpoint kustom dibuat`);
    if (result.scenariosRestored) parts.push(`${result.scenariosRestored} scenario dipulihkan`);
    if (result.skipped) parts.push(`${result.skipped} dilewati`);
    const head = parts.length ? parts.join(' · ') : 'Tak ada yang diubah';
    return result.messages.length ? `${head}\n\n${result.messages.join('\n')}` : head;
  }

  showResult(result: OpenApiImportResult) {
    return this.alert(
      result.messages.length ? 'Impor selesai dengan catatan' : 'Impor selesai',
      this.summarize(result)
    );
  }

  private alert(title: string, message: string) {
    return this.dialog
      .open(ConfirmDialog, {
        data: { title, message, confirmText: 'Tutup', cancelText: '' },
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
