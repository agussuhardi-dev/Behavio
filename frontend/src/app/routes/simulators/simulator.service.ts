import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

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

export interface AccountView {
  id: string;
  partnerRowId: string;
  partnerLabel: string;
  accountNo: string;
  holderName: string;
  currency: string;
  balance: string;
}

export interface VirtualAccountView {
  virtualAccountNo: string;
  virtualAccountName: string;
  amount: string;
  currency: string;
  status: 'ACTIVE' | 'PAID' | 'EXPIRED';
  trxId: string;
  hasCallback: boolean;
}

export interface QrisView {
  referenceNo: string;
  partnerReferenceNo: string;
  merchantId: string;
  qrType: 'STATIC' | 'DYNAMIC';
  amount: string | null;
  currency: string;
  status: 'ACTIVE' | 'PAID' | 'REFUNDED' | 'EXPIRED';
  hasCallback: boolean;
}

/** Satu halaman QR + total, sesuai respons Admin API `GET .../qris?page=&size=`. */
export interface QrisPage {
  items: QrisView[];
  total: number;
  page: number;
  size: number;
}

export interface Scenario {
  name: string;
  desc: string;
  icon: string;
  tone: 'ok' | 'warn' | 'fault';
}

/** Scenario preset Transfer Intrabank beserta penjelasan ramah end-user. */
export const SCENARIOS: Scenario[] = [
  { name: 'Normal', desc: 'Transaksi berjalan normal — dana didebit & dikredit.', icon: 'check_circle', tone: 'ok' },
  { name: 'Saldo Kurang', desc: 'Selalu ditolak: dana tidak cukup (4001714).', icon: 'money_off', tone: 'warn' },
  { name: 'Limit', desc: 'Ditolak bila nominal di atas 25 juta (4031800).', icon: 'trending_up', tone: 'warn' },
  { name: 'Bank Down', desc: 'Bank sedang gangguan — HTTP 503, saldo tetap utuh.', icon: 'cloud_off', tone: 'fault' },
  { name: 'Timeout', desc: 'Respons sengaja lambat (delay 5 detik).', icon: 'hourglass_empty', tone: 'fault' },
  { name: 'Commit Then Drop', desc: 'Dana terpotong tapi respons hilang — menguji idempotensi/rekonsiliasi.', icon: 'flash_off', tone: 'fault' },
  { name: 'Malformed', desc: 'Transaksi sukses tapi body respons dibuat rusak.', icon: 'broken_image', tone: 'fault' },
  { name: 'Async Callback', desc: 'Respons PENDING dulu, lalu webhook status sukses dikirim 2 detik kemudian.', icon: 'webhook', tone: 'ok' },
];

/** Scenario preset QRIS MPM Generate (design.md Lampiran A3). */
export const QRIS_SCENARIOS: Scenario[] = [
  { name: 'Normal', desc: 'QR berhasil dibuat. Ditolak hanya bila nominal (dynamic) ≤ 0.', icon: 'check_circle', tone: 'ok' },
  { name: 'Merchant Diblokir', desc: 'Selalu ditolak — merchant tidak terdaftar/nonaktif (4044712).', icon: 'block', tone: 'warn' },
  { name: 'Service Down', desc: 'Layanan QRIS gangguan — HTTP 503.', icon: 'cloud_off', tone: 'fault' },
];

@Injectable({ providedIn: 'root' })
export class SimulatorService {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/admin/v1/simulators';

  list(): Observable<Simulator[]> {
    return this.http.get<Simulator[]>(this.base);
  }

  start(id: string) {
    return this.http.post(`${this.base}/${id}/start`, {});
  }

  stop(id: string) {
    return this.http.post(`${this.base}/${id}/stop`, {});
  }

  /** Buat simulator (profil bank) baru — baseline SNAP lengkap otomatis disiapkan. */
  create(name: string, port: number, signatureMode: 'SIMULATED' | 'STRICT' = 'SIMULATED') {
    return this.http.post<Simulator>(this.base, { name, port, signatureMode });
  }

  /** Duplikat profil bank (konfigurasi, override, akun tersalin) dengan nama/port baru. */
  clone(sourceId: string, name: string, port: number) {
    return this.http.post<Simulator>(`${this.base}/${sourceId}/clone`, { name, port });
  }

  delete(id: string) {
    return this.http.delete(`${this.base}/${id}`);
  }

  /** @param product Endpoint mana yang diedit: 'transfer', 'qris', 'qris-query', dst. */
  setScenario(id: string, name: string, product: string = 'transfer') {
    return this.http.put(`${this.base}/${id}/active-scenario?product=${product}`, { name });
  }

  /** Scenario yang SEDANG aktif di server — agar dropdown dashboard sinkron. */
  getActiveScenario(id: string, product: string = 'transfer') {
    return this.http.get<{ name: string }>(`${this.base}/${id}/scenarios/active?product=${product}`);
  }

  /** Ambil definisi JSON scenario (custom bila ada, selain itu preset blueprint). */
  getDefinition(id: string, scenario: string, product: string = 'transfer') {
    return this.http.get(`${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?product=${product}`, {
      responseType: 'text',
    });
  }

  /** Simpan definisi custom (override request cond + response). */
  saveDefinition(id: string, scenario: string, json: string, product: string = 'transfer') {
    return this.http.put(
      `${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?product=${product}`,
      json,
      { headers: { 'Content-Type': 'text/plain' } }
    );
  }

  /** Kembalikan scenario ke preset (hapus override). */
  resetDefinition(id: string, scenario: string, product: string = 'transfer') {
    return this.http.delete(
      `${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition?product=${product}`
    );
  }

  /** URL SSE live view (dilewatkan proxy dev ke :8080). */
  streamUrl(id: string): string {
    return `${this.base}/${id}/logs/stream`;
  }

  /** Daftar Virtual Account yang sudah dibuat partner (via create-va) di simulator ini. */
  listVirtualAccounts(id: string) {
    return this.http.get<VirtualAccountView[]>(`${this.base}/${id}/virtual-accounts`);
  }

  /** Tandai VA sebagai dibayar — memicu Payment Notification (webhook) ke callback URL-nya. */
  payVirtualAccount(id: string, vaNo: string) {
    return this.http.post<{ webhookSent: boolean; note: string }>(
      `${this.base}/${id}/virtual-accounts/${encodeURIComponent(vaNo)}/pay`,
      {}
    );
  }

  // ---- Partner & Account (nasabah/rekening) ----

  listPartners(id: string) {
    return this.http.get<PartnerView[]>(`${this.base}/${id}/partners`);
  }

  createPartner(id: string, partnerId: string, clientSecret?: string, publicKeyPem?: string) {
    return this.http.post(`${this.base}/${id}/partners`, { partnerId, clientSecret, publicKeyPem });
  }

  deletePartner(id: string, partnerRowId: string) {
    return this.http.delete(`${this.base}/${id}/partners/${partnerRowId}`);
  }

  listAccounts(id: string) {
    return this.http.get<AccountView[]>(`${this.base}/${id}/accounts`);
  }

  createAccount(id: string, partnerId: string, accountNo: string, holderName: string, balance: string) {
    return this.http.post(`${this.base}/${id}/accounts`, { partnerId, accountNo, holderName, balance });
  }

  setAccountBalance(id: string, accountId: string, balance: string) {
    return this.http.put(`${this.base}/${id}/accounts/${accountId}/balance`, { balance });
  }

  deleteAccount(id: string, accountId: string) {
    return this.http.delete(`${this.base}/${id}/accounts/${accountId}`);
  }

  // ---- QRIS MPM ----

  /** Satu halaman QR (terbaru dulu) yang dibuat partner via qr-mpm-generate di simulator ini. */
  listQris(id: string, page = 0, size = 10) {
    return this.http.get<QrisPage>(`${this.base}/${id}/qris?page=${page}&size=${size}`);
  }

  /**
   * Tandai QR sebagai dibayar (simulasi pelanggan scan & bayar) — memicu Payment
   * Notify (webhook). `amount` WAJIB untuk QR static (nominal diisi saat bayar).
   */
  payQris(id: string, referenceNo: string, amount?: string) {
    return this.http.post<{ webhookSent: boolean; note: string }>(
      `${this.base}/${id}/qris/${encodeURIComponent(referenceNo)}/pay`,
      amount ? { amount } : {}
    );
  }

  /** Batalkan/kedaluwarsakan QR yang belum dibayar (hanya berlaku untuk status ACTIVE). */
  expireQris(id: string, referenceNo: string) {
    return this.http.post<{ note: string }>(
      `${this.base}/${id}/qris/${encodeURIComponent(referenceNo)}/expire`,
      {}
    );
  }

  // ---- URL Endpoint (path dapat di-custom per bank, design.md §2) ----

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
}
