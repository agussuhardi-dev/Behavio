import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import {
  PartnerView,
  ProductApi,
  WEBHOOK_EVENTS,
  WebhookSubscription,
} from '../../../core/api/product-api';

/**
 * Registrasi URL notifikasi per partner (design.md §9.1).
 *
 * Satu komponen untuk bank & QRIS: mesin webhook-nya generik, yang berbeda hanya daftar
 * event-nya — dan itu datang dari `WEBHOOK_EVENTS[product]`, bukan dari cabang kode.
 */
@Component({
  selector: 'app-webhook-panel',
  standalone: true,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
  ],
  templateUrl: './webhook-panel.html',
  styleUrl: './webhook-panel.scss',
})
export class WebhookPanel {
  readonly api = input.required<ProductApi>();
  readonly simulatorId = input.required<string>();
  readonly partners = input.required<PartnerView[]>();

  readonly subscriptions = signal<WebhookSubscription[]>([]);
  readonly msg = signal('');

  readonly newPartnerId = signal('');
  readonly newEvent = signal('ALL');
  readonly newUrl = signal('');

  readonly events = computed(() => WEBHOOK_EVENTS[this.api().product]);

  /** URL sink bawaan — tujuan tes paling sering, jadi disediakan sekali klik. */
  readonly sinkUrl = '/api/admin/v1/webhook-sink';

  constructor() {
    effect(() => {
      const id = this.simulatorId();
      if (id) {
        this.reload();
      } else {
        this.subscriptions.set([]);
      }
    });
  }

  reload() {
    this.api()
      .listWebhookSubscriptions(this.simulatorId())
      .subscribe({
        next: list => this.subscriptions.set(list),
        error: () => this.subscriptions.set([]),
      });
  }

  useSink() {
    this.newUrl.set(`${window.location.origin}${this.sinkUrl}`);
  }

  register() {
    const partnerId = this.newPartnerId().trim();
    const url = this.newUrl().trim();
    if (!partnerId || !url) {
      this.msg.set('Partner dan URL wajib diisi.');
      return;
    }
    this.api()
      .registerWebhook(this.simulatorId(), partnerId, this.newEvent(), url)
      .subscribe({
        next: s => {
          this.msg.set(`URL ${s.event} untuk ${s.partnerId} tersimpan.`);
          this.newUrl.set('');
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? 'Gagal menyimpan registrasi.'),
      });
  }

  toggle(s: WebhookSubscription) {
    const next = s.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    this.api()
      .setWebhookSubscriptionStatus(this.simulatorId(), s.id, next)
      .subscribe({
        next: () => {
          this.msg.set(`Registrasi ${s.event} untuk ${s.partnerId} → ${next}.`);
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? 'Gagal mengubah status.'),
      });
  }

  remove(s: WebhookSubscription) {
    this.api()
      .deleteWebhookSubscription(this.simulatorId(), s.id)
      .subscribe({
        next: () => {
          this.msg.set(`Registrasi ${s.event} untuk ${s.partnerId} dihapus.`);
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? 'Gagal menghapus registrasi.'),
      });
  }

  eventLabel(key: string): string {
    return this.events().find(e => e.key === key)?.label ?? key;
  }
}
