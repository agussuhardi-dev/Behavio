import { Injectable } from '@angular/core';

import { ProductApi, ProductKey, Scenario } from './product-api';

export interface QrisView {
  referenceNo: string;
  partnerReferenceNo: string;
  merchantId: string;
  qrType: 'STATIC' | 'DYNAMIC';
  amount: string | null;
  currency: string;
  status: 'ACTIVE' | 'PAID' | 'REFUNDED' | 'EXPIRED';
  // Tanpa hasCallback — lihat catatan di VirtualAccountView (design.md §9.1).
}

/** Satu halaman QR + total, sesuai respons Admin API `GET .../qris?page=&size=`. */
export interface QrisPage {
  items: QrisView[];
  total: number;
  page: number;
  size: number;
}

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

/**
 * Admin API produk QRIS (schema `qris`, profil default :9101) — profil di sini adalah
 * **PJP**, entitas tersendiri dengan partner, access-token, dan port sendiri; bukan
 * profil bank yang sama seperti sebelum pemisahan (design.md §3.4).
 */
@Injectable({ providedIn: 'root' })
export class QrisApi extends ProductApi {
  readonly product: ProductKey = 'qris';

  /** Satu halaman QR (terbaru dulu) yang dibuat partner via qr-mpm-generate. */
  listQris(id: string, page = 0, size = 10) {
    return this.http.get<QrisPage>(`${this.base}/${id}/qris?page=${page}&size=${size}`);
  }

  /**
   * Tandai QR dibayar (simulasi pelanggan scan & bayar) — memicu Payment Notify
   * (webhook). `amount` WAJIB untuk QR static (nominal diisi saat bayar).
   */
  payQris(id: string, referenceNo: string, amount?: string) {
    return this.http.post<{ webhookSent: boolean; note: string }>(
      `${this.base}/${id}/qris/${encodeURIComponent(referenceNo)}/pay`,
      amount ? { amount } : {}
    );
  }

  /**
   * Kirim ulang Payment Notify memakai status AKTIF QR — retry/test (design.md §9.2).
   * Tidak mengubah data.
   */
  resendQrisNotification(id: string, referenceNo: string) {
    return this.http.post<{ webhookSent: boolean; note: string }>(
      `${this.base}/${id}/qris/${encodeURIComponent(referenceNo)}/resend-notification`,
      {}
    );
  }

  /** Batalkan/kedaluwarsakan QR yang belum dibayar (hanya berlaku untuk status ACTIVE). */
  expireQris(id: string, referenceNo: string) {
    return this.http.post<{ note: string }>(
      `${this.base}/${id}/qris/${encodeURIComponent(referenceNo)}/expire`,
      {}
    );
  }
}
