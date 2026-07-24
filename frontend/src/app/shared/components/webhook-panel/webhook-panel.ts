import { Component, computed, effect, inject, input, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslatePipe, TranslateService } from '@ngx-translate/core';

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
    TranslatePipe,
  ],
  templateUrl: './webhook-panel.html',
  styleUrl: './webhook-panel.scss',
})
export class WebhookPanel {
  private readonly translate = inject(TranslateService);

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
      this.msg.set(this.translate.instant('webhook.msg.fields_required'));
      return;
    }
    this.api()
      .registerWebhook(this.simulatorId(), partnerId, this.newEvent(), url)
      .subscribe({
        next: s => {
          this.msg.set(this.translate.instant('webhook.msg.registered', { event: s.event, partner: s.partnerId }));
          this.newUrl.set('');
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? this.translate.instant('webhook.msg.register_failed')),
      });
  }

  toggle(s: WebhookSubscription) {
    const next = s.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE';
    this.api()
      .setWebhookSubscriptionStatus(this.simulatorId(), s.id, next)
      .subscribe({
        next: () => {
          this.msg.set(this.translate.instant('webhook.msg.status_changed', { event: s.event, partner: s.partnerId, status: next }));
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? this.translate.instant('webhook.msg.status_change_failed')),
      });
  }

  remove(s: WebhookSubscription) {
    this.api()
      .deleteWebhookSubscription(this.simulatorId(), s.id)
      .subscribe({
        next: () => {
          this.msg.set(this.translate.instant('webhook.msg.deleted', { event: s.event, partner: s.partnerId }));
          this.reload();
        },
        error: err => this.msg.set(err?.error?.error ?? this.translate.instant('webhook.msg.delete_failed')),
      });
  }

  eventLabel(key: string): string {
    return this.events().find(e => e.key === key)?.label ?? key;
  }

  /** Kunci i18n untuk label event webhook — nilai `key` tetap identifier ke backend. */
  eventLabelKey(key: string): string {
    const map: Record<string, string> = {
      ALL: 'webhook.event_all',
      'transfer-notify': 'webhook.event_transfer_notify',
      'va-payment': 'webhook.event_va_payment',
      'qris-payment': 'webhook.event_qris_payment',
    };
    return map[key] ?? key;
  }
}
