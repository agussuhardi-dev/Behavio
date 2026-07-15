import { HttpClient } from '@angular/common/http';
import { inject } from '@angular/core';
import { Observable } from 'rxjs';

/**
 * Produk simulator (design.md §3.4). Bank & QRIS terpisah penuh di backend: modul,
 * schema PostgreSQL, dan port sendiri-sendiri. Nilai ini = segmen Admin API.
 */
export type ProductKey = 'bank' | 'qris';

export interface Simulator {
  id: string;
  name: string;
  port: number;
  status: 'RUNNING' | 'STOPPED';
}

export interface EndpointConfig {
  operation: string;
  method: string;
  path: string;
  defaultPath: string;
  label: string;
  headers?: string;
}

export interface PartnerView {
  id: string;
  partnerId: string;
  hasPublicKey: boolean;
  hasClientSecret: boolean;
}

export interface Scenario {
  name: string;
  desc: string;
  icon: string;
  tone: 'ok' | 'warn' | 'fault';
}

/**
 * Admin API yang bentuknya SAMA untuk semua produk: profil, partner, endpoint,
 * scenario, live view. Satu salinan kode, diturunkan sekali per produk — cermin dari
 * mesin generik di backend (`:adapter-persistence` / `:adapter-web`).
 *
 * Yang khusus produk (rekening & VA milik bank, QR milik QRIS) ada di subclass-nya.
 */
export abstract class ProductApi {
  protected readonly http = inject(HttpClient);

  /** Segmen produk di URL — juga nama schema-nya di backend. */
  abstract readonly product: ProductKey;

  protected get base(): string {
    return `/api/admin/v1/${this.product}/simulators`;
  }

  list(): Observable<Simulator[]> {
    return this.http.get<Simulator[]>(this.base);
  }

  start(id: string) {
    return this.http.post(`${this.base}/${id}/start`, {});
  }

  stop(id: string) {
    return this.http.post(`${this.base}/${id}/stop`, {});
  }

  /** Buat profil baru — baseline SNAP produk ini otomatis disiapkan backend. */
  create(name: string, port: number, signatureMode: 'SIMULATED' | 'STRICT' = 'SIMULATED') {
    return this.http.post<Simulator>(this.base, { name, port, signatureMode });
  }

  /** Duplikat profil (konfigurasi, override, state awal tersalin) dengan nama/port baru. */
  clone(sourceId: string, name: string, port: number) {
    return this.http.post<Simulator>(`${this.base}/${sourceId}/clone`, { name, port });
  }

  delete(id: string) {
    return this.http.delete(`${this.base}/${id}`);
  }

  /**
   * @param operation kunci operasi milik produk ini (mis. 'transfer', 'qris-generate').
   *        Meminta operasi milik produk lain ditolak backend dengan 400.
   */
  setScenario(id: string, name: string, operation: string) {
    return this.http.put(`${this.base}/${id}/active-scenario?operation=${operation}`, { name });
  }

  /** Scenario yang SEDANG aktif di server — agar dropdown dashboard sinkron. */
  getActiveScenario(id: string, operation: string) {
    return this.http.get<{ name: string }>(`${this.base}/${id}/scenarios/active?operation=${operation}`);
  }

  /** Ambil definisi JSON scenario (custom bila ada, selain itu preset blueprint). */
  getDefinition(id: string, scenario: string, operation: string) {
    return this.http.get(
      `${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?operation=${operation}`,
      { responseType: 'text' }
    );
  }

  /** Simpan definisi custom (override request cond + response). */
  saveDefinition(id: string, scenario: string, json: string, operation: string) {
    return this.http.put(
      `${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?operation=${operation}`,
      json,
      { headers: { 'Content-Type': 'text/plain' } }
    );
  }

  /** Kembalikan scenario ke preset (hapus override). */
  resetDefinition(id: string, scenario: string, operation: string) {
    return this.http.delete(
      `${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?operation=${operation}`
    );
  }

  /** URL SSE live view (dilewatkan proxy dev ke :8080). */
  streamUrl(id: string): string {
    return `${this.base}/${id}/logs/stream`;
  }

  // ---- Partner (generik: kedua produk punya partner + kunci SNAP sendiri) ----

  listPartners(id: string) {
    return this.http.get<PartnerView[]>(`${this.base}/${id}/partners`);
  }

  createPartner(id: string, partnerId: string, clientSecret?: string, publicKeyPem?: string) {
    return this.http.post(`${this.base}/${id}/partners`, { partnerId, clientSecret, publicKeyPem });
  }

  deletePartner(id: string, partnerRowId: string) {
    return this.http.delete(`${this.base}/${id}/partners/${partnerRowId}`);
  }

  // ---- URL Endpoint (path dapat di-custom per profil, design.md §2) ----

  listEndpoints(id: string) {
    return this.http.get<EndpointConfig[]>(`${this.base}/${id}/endpoints`);
  }

  addEndpoint(id: string, method: string, path: string, headers?: string, label?: string) {
    return this.http.post(`${this.base}/${id}/endpoints`, { method, path, headers, label });
  }

  deleteEndpoint(id: string, operation: string) {
    return this.http.delete(`${this.base}/${id}/endpoints/${operation}`);
  }

  updateEndpointPath(id: string, operation: string, path: string) {
    return this.http.put(`${this.base}/${id}/endpoints/${operation}`, { path });
  }

  updateEndpointMeta(id: string, operation: string, method: string, headers: string) {
    return this.http.put(`${this.base}/${id}/endpoints/${operation}`, { method, headers });
  }

  resetEndpointPath(id: string, operation: string) {
    return this.http.put(`${this.base}/${id}/endpoints/${operation}`, { path: null });
  }

  // ---- Registrasi URL notifikasi (design.md §9.1) ----

  listWebhookSubscriptions(id: string) {
    return this.http.get<WebhookSubscription[]>(`${this.base}/${id}/webhooks/subscriptions`);
  }

  /** Daftarkan/perbarui URL untuk (partner, event) — idempoten per pasangan itu. */
  registerWebhook(id: string, partnerId: string, event: string, url: string) {
    return this.http.post<WebhookSubscription>(`${this.base}/${id}/webhooks/subscriptions`, {
      partnerId,
      event,
      url,
    });
  }

  deleteWebhookSubscription(id: string, subscriptionId: string) {
    return this.http.delete(`${this.base}/${id}/webhooks/subscriptions/${subscriptionId}`);
  }

  setWebhookSubscriptionStatus(id: string, subscriptionId: string, status: WebhookStatus) {
    return this.http.put(`${this.base}/${id}/webhooks/subscriptions/${subscriptionId}/status`, {
      status,
    });
  }

  // ---- Export / Import OpenAPI (design.md §15) ----

  /**
   * Unduh spec sebagai file. Dipakai Postman/Swagger apa adanya; perilaku (rule,
   * scenario, fault, webhook) ikut di extension `x-behavio` yang diabaikan tool lain.
   */
  exportOpenApi(id: string, format: 'yaml' | 'json' = 'yaml') {
    return this.http.get(`${this.base}/${id}/openapi?format=${format}`, {
      responseType: 'blob',
      observe: 'response',
    });
  }

  /** Pratinjau impor — TIDAK mengubah apa pun di server (design.md §15.4). */
  previewOpenApi(id: string, spec: string) {
    return this.http.post<OpenApiPreview>(`${this.base}/${id}/openapi/preview`, spec, {
      headers: { 'Content-Type': 'text/plain' },
    });
  }

  /** Terapkan impor sesuai pemetaan yang dikonfirmasi user. */
  importOpenApi(id: string, spec: string, mappings: OpenApiMapping[]) {
    return this.http.post<OpenApiImportResult>(`${this.base}/${id}/openapi/import`, {
      spec,
      mappings,
    });
  }
}

// ---- Model registrasi webhook (cermin WebhookSubscriptions.Subscription) ----

export type WebhookStatus = 'ACTIVE' | 'INACTIVE';

export interface WebhookSubscription {
  id: string;
  /** Nilai X-PARTNER-ID, bukan UUID baris — itu yang dikenali user. */
  partnerId: string;
  event: string;
  url: string;
  status: WebhookStatus;
}

/**
 * Event notifikasi per produk (design.md §9.1). `ALL` = tangkap semua, dipakai bila
 * partner tak butuh URL berbeda per jenis notifikasi.
 */
export const WEBHOOK_EVENTS: Record<ProductKey, { key: string; label: string }[]> = {
  bank: [
    { key: 'ALL', label: 'ALL — semua notifikasi' },
    { key: 'transfer-notify', label: 'transfer-notify — Async Callback transfer' },
    { key: 'va-payment', label: 'va-payment — Payment Notification VA' },
  ],
  qris: [
    { key: 'ALL', label: 'ALL — semua notifikasi' },
    { key: 'qris-payment', label: 'qris-payment — Payment Notify (service 52)' },
  ],
};

// ---- Model export/import OpenAPI (cermin record di OpenApiImporter) ----

export type OpenApiAction = 'CATALOG' | 'CUSTOM' | 'SKIP';

export interface OpenApiPreviewRow {
  path: string;
  method: string;
  label: string;
  /** Kunci katalog yang disarankan; kosong = sistem tak menebak. */
  suggestedOperation: string;
  /** Dari mana tebakan datang — ditampilkan agar user tahu seberapa layak dipercaya. */
  confidence: string;
  hasBehavior: boolean;
  scenarioNames: string[];
}

export interface OpenApiPreview {
  product: ProductKey;
  sourceTitle: string;
  rows: OpenApiPreviewRow[];
}

export interface OpenApiMapping {
  path: string;
  method: string;
  action: OpenApiAction;
  operation: string;
}

export interface OpenApiImportResult {
  overridden: number;
  created: number;
  skipped: number;
  scenariosRestored: number;
  messages: string[];
}
