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

/**
 * Scenario preset QRIS MPM Generate — katalog LENGKAP responseCode service 47
 * menurut tabel ASPI (design.md Lampiran A3.6). Urut: sukses → 4xx → 5xx.
 */
export const QRIS_SCENARIOS: Scenario[] = [
  { name: 'Normal', desc: 'QR berhasil dibuat (2004700). Ditolak hanya bila nominal (dynamic) ≤ 0.', icon: 'check_circle', tone: 'ok' },
  { name: 'Bad Request', desc: 'Request ditolak — 4004700 Bad Request.', icon: 'report_problem', tone: 'warn' },
  { name: 'Format Field Salah', desc: 'Format field tidak sesuai — 4004701 Invalid Field Format.', icon: 'rule', tone: 'warn' },
  { name: 'Field Wajib Kosong', desc: 'Field wajib tidak diisi — 4004702 Invalid Mandatory Field.', icon: 'edit_off', tone: 'warn' },
  { name: 'Unauthorized', desc: 'Ditolak di gerbang auth — 4014700 Unauthorized.', icon: 'lock', tone: 'warn' },
  { name: 'Token Tidak Valid', desc: 'Token B2B tidak valid/kedaluwarsa — 4014701 Invalid Token (B2B).', icon: 'key_off', tone: 'warn' },
  { name: 'Transaksi Kedaluwarsa', desc: 'Transaksi sudah lewat masa berlaku — 4034700 Transaction Expired.', icon: 'timer_off', tone: 'warn' },
  { name: 'Fitur Tidak Diizinkan', desc: 'Fitur tidak aktif untuk partner ini — 4034701 Feature Not Allowed.', icon: 'do_disturb', tone: 'warn' },
  { name: 'Melebihi Limit', desc: 'Ditolak bila nominal di atas 10 juta — 4034702 Exceeds Transaction Amount Limit.', icon: 'trending_up', tone: 'warn' },
  { name: 'Suspected Fraud', desc: 'Terindikasi penipuan — 4034703 Suspected Fraud.', icon: 'gpp_maybe', tone: 'warn' },
  { name: 'Batas Aktivitas Terlampaui', desc: 'Jumlah transaksi melebihi batas — 4034704 Activity Count Limit Exceeded.', icon: 'filter_list_off', tone: 'warn' },
  { name: 'Do Not Honor', desc: 'Ditolak penerbit tanpa alasan spesifik — 4034705 Do Not Honor.', icon: 'thumb_down', tone: 'warn' },
  { name: 'Merchant Diblokir', desc: 'Merchant tidak terdaftar/nonaktif — 4044708 Invalid Merchant.', icon: 'block', tone: 'warn' },
  { name: 'Terminal Tidak Valid', desc: 'Terminal tidak dikenal — 4044717 Invalid Terminal.', icon: 'point_of_sale', tone: 'warn' },
  { name: 'Terlalu Banyak Request', desc: 'Kena rate limit — 4294700 Too Many Requests.', icon: 'speed', tone: 'warn' },
  { name: 'General Error', desc: 'Kesalahan umum sisi acquirer — 5004700 General Error.', icon: 'error', tone: 'fault' },
  { name: 'Service Down', desc: 'Layanan QRIS gangguan — 5004701 Internal Server Error.', icon: 'cloud_off', tone: 'fault' },
  { name: 'Timeout', desc: 'Respons sengaja lambat 5 detik lalu 5044700 Timeout.', icon: 'hourglass_empty', tone: 'fault' },
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
