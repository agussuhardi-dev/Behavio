import { Injectable } from '@angular/core';

import { ProductApi, ProductKey, Scenario } from './product-api';

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

/** Scenario preset Transfer Intrabank beserta penjelasan ramah end-user. */
export const SCENARIOS: Scenario[] = [
  { name: 'Normal', desc: 'Transaksi berjalan normal — dana didebit & dikredit.', icon: 'check_circle', tone: 'ok' },
  { name: 'Saldo Kurang', desc: 'Selalu ditolak: dana tidak cukup (4001714).', icon: 'money_off', tone: 'warn' },
  { name: 'Limit', desc: 'Ditolak bila nominal di atas 25 juta (4031700).', icon: 'trending_up', tone: 'warn' },
  { name: 'Bank Down', desc: 'Bank sedang gangguan — HTTP 503, saldo tetap utuh.', icon: 'cloud_off', tone: 'fault' },
  { name: 'Timeout', desc: 'Respons sengaja lambat (delay 5 detik).', icon: 'hourglass_empty', tone: 'fault' },
  { name: 'Commit Then Drop', desc: 'Dana terpotong tapi respons hilang — menguji idempotensi/rekonsiliasi.', icon: 'flash_off', tone: 'fault' },
  { name: 'Malformed', desc: 'Transaksi sukses tapi body respons dibuat rusak.', icon: 'broken_image', tone: 'fault' },
  { name: 'Async Callback', desc: 'Respons PENDING dulu, lalu webhook status sukses dikirim 2 detik kemudian.', icon: 'webhook', tone: 'ok' },
];

/**
 * Admin API produk BANK (schema `bank`, profil default :9001) — semua yang generik
 * diwarisi dari {@link ProductApi}; di sini hanya yang khas bank: rekening & Virtual
 * Account. Profil QRIS tak punya keduanya (design.md §3.4).
 */
@Injectable({ providedIn: 'root' })
export class BankApi extends ProductApi {
  readonly product: ProductKey = 'bank';

  // ---- Rekening ----

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

  // ---- Virtual Account ----

  /** Daftar VA yang sudah dibuat partner (via create-va) di profil ini. */
  listVirtualAccounts(id: string) {
    return this.http.get<VirtualAccountView[]>(`${this.base}/${id}/virtual-accounts`);
  }

  /** Tandai VA dibayar — memicu Payment Notification (webhook) ke callback URL-nya. */
  payVirtualAccount(id: string, vaNo: string) {
    return this.http.post<{ webhookSent: boolean; note: string }>(
      `${this.base}/${id}/virtual-accounts/${encodeURIComponent(vaNo)}/pay`,
      {}
    );
  }
}
