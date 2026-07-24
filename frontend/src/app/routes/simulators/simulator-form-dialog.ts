import { Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { TranslatePipe } from '@ngx-translate/core';

export interface SimulatorFormData {
  mode: 'create' | 'clone';
  sourceName?: string;
  suggestedPort: number;
}

export interface SimulatorFormResult {
  name: string;
  port: number;
  signatureMode: 'SIMULATED' | 'STRICT';
}

/** Dialog form: tambah profil bank baru, atau clone dari profil yang sudah ada. */
@Component({
  selector: 'app-simulator-form-dialog',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    TranslatePipe,
  ],
  templateUrl: './simulator-form-dialog.html',
  styleUrl: './simulator-form-dialog.scss',
})
export class SimulatorFormDialog {
  readonly dialogRef = inject(MatDialogRef<SimulatorFormDialog>);
  readonly data = inject<SimulatorFormData>(MAT_DIALOG_DATA);

  readonly name = signal(this.data.mode === 'clone' ? `${this.data.sourceName} (Copy)` : '');
  readonly port = signal(this.data.suggestedPort);
  readonly signatureMode = signal<'SIMULATED' | 'STRICT'>('SIMULATED');

  submit() {
    if (!this.name().trim() || !this.port()) return;
    const result: SimulatorFormResult = {
      name: this.name().trim(),
      port: Number(this.port()),
      signatureMode: this.signatureMode(),
    };
    this.dialogRef.close(result);
  }
}
