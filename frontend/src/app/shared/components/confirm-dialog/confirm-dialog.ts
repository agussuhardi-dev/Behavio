import { Component, inject } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { TranslatePipe } from '@ngx-translate/core';

export interface ConfirmDialogData {
  title: string;
  message: string;
  /** Teks tombol aksi. Default: "Ya". */
  confirmText?: string;
  cancelText?: string;
  /** Aksi merusak (hapus dsb) → tombol merah + ikon peringatan. */
  danger?: boolean;
}

/**
 * Dialog konfirmasi Material — pengganti `confirm()` bawaan browser, yang tampilannya
 * di luar kendali tema dan memblokir seluruh tab.
 *
 * Ditutup dengan `true` bila dikonfirmasi, `undefined`/`false` bila dibatalkan.
 */
@Component({
  selector: 'app-confirm-dialog',
  standalone: true,
  imports: [MatButtonModule, MatDialogModule, MatIconModule, TranslatePipe],
  templateUrl: './confirm-dialog.html',
  styleUrl: './confirm-dialog.scss',
})
export class ConfirmDialog {
  readonly dialogRef = inject(MatDialogRef<ConfirmDialog, boolean>);
  readonly data = inject<ConfirmDialogData>(MAT_DIALOG_DATA);
}
