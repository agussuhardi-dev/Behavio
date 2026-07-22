import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';

export interface IsoSimulator {
  id: string;
  name: string;
  port: number;
  status: 'RUNNING' | 'STOPPED';
  specProfileName: string;
  specProfileVersion: string;
}

export interface SpecProfileSummary {
  id: string;
  name: string;
  version: string;
  parent: string | null;
  sourceFormat: 'XML' | 'JSON';
  createdAt: string;
}

export interface SpecFieldView {
  de: number;
  name: string;
  type: string;
  encoding: string;
  length: number;
  lengthPrefix: number;
}

export interface SpecProfileDetail {
  id: string;
  transport: {
    lengthPrefixBytes: number;
    lengthPrefixEncoding: string;
    charset: string;
    bitmap: string;
  };
  fields: SpecFieldView[];
  operations: { name: string; mti: string; processingCode: string }[];
}

export interface TraceResult {
  ok: boolean;
  mti: string | null;
  operation: string | null;
  fields: Record<string, string>;
  error: string | null;
}

export interface IsoAccount {
  id: string;
  accountNo: string;
  holderName: string;
  balance: number;
  currency: string;
  phone: string | null;
}

export interface IsoCard {
  id: string;
  pan: string;
  accountNo: string;
  status: string;
  /** PIN pernah di-set lewat operasi change-pin. Bukan nilai PIN-nya — itu tak pernah dibuka. */
  pinSet: boolean;
}

export interface IsoLog {
  mti: string;
  operation: string | null;
  response_code: string | null;
  request_hex: string;
  response_hex: string;
  duration_ms: number;
  /** Alasan pesan gagal diproses; null bila berhasil. Inilah sebab klien melihat timeout. */
  error: string | null;
  created_at: string;
}

/**
 * Admin API produk ISO-8583 (schema `iso8583`, transport TCP).
 *
 * <p><b>Sengaja TIDAK mewarisi `ProductApi`.</b> Produk HTTP punya partner, endpoint
 * ber-URL, webhook, dan export OpenAPI — ISO-8583 tak punya satu pun dari itu; yang ada
 * profil spec, kartu, dan DE. Mewarisi method yang pasti 404 justru melanggar prinsip
 * projek ini: sesuatu yang bisa dipanggil tapi tak berefek lebih buruk daripada tak ada.
 */
@Injectable({ providedIn: 'root' })
export class IsoApi {
  private readonly http = inject(HttpClient);
  private readonly base = '/api/admin/v1/iso8583';

  // ── simulator ────────────────────────────────────────────────────────────
  list() {
    return this.http.get<IsoSimulator[]>(`${this.base}/simulators`);
  }

  create(name: string, port: number, specProfileName: string, specProfileVersion: string) {
    return this.http.post<IsoSimulator>(`${this.base}/simulators`, {
      name,
      port,
      specProfileName,
      specProfileVersion,
    });
  }

  /**
   * Mengalihkan simulator ke profil spec lain. Rekening, kartu, dan riwayat pesan tetap;
   * yang berubah hanya cara pesan dibaca/ditulis. Simulator yang sedang RUNNING di-start
   * ulang oleh backend, sehingga koneksi TCP yang terbuka terputus.
   */
  switchProfile(id: string, specProfileName: string, specProfileVersion: string) {
    return this.http.put<IsoSimulator>(`${this.base}/simulators/${id}/spec-profile`, {
      specProfileName,
      specProfileVersion,
    });
  }

  start(id: string) {
    return this.http.post<IsoSimulator>(`${this.base}/simulators/${id}/start`, {});
  }

  stop(id: string) {
    return this.http.post<IsoSimulator>(`${this.base}/simulators/${id}/stop`, {});
  }

  remove(id: string) {
    return this.http.delete(`${this.base}/simulators/${id}`);
  }

  // ── profil spec ──────────────────────────────────────────────────────────
  listProfiles() {
    return this.http.get<SpecProfileSummary[]>(`${this.base}/spec-profiles`);
  }

  profileDetail(name: string, version: string) {
    return this.http.get<SpecProfileDetail>(
      `${this.base}/spec-profiles/${encodeURIComponent(name)}/${encodeURIComponent(version)}`
    );
  }

  supportedPackagerClasses() {
    return this.http.get<{ supported: string[]; catatan: string }>(
      `${this.base}/spec-profiles/supported-packager-classes`
    );
  }

  /** Unggah profil berformat JSON. */
  uploadJson(json: string) {
    return this.http.post<{ id: string }>(`${this.base}/spec-profiles`, json, {
      headers: { 'Content-Type': 'application/json' },
    });
  }

  /**
   * Unggah jPOS packager XML — bentuk yang biasanya diserahkan bank. Berkas itu hanya
   * memuat daftar field, jadi identitas & rute operasi dikirim sebagai query param.
   */
  uploadXml(xml: string, name: string, version: string, parent: string, operations: string[]) {
    let url = `${this.base}/spec-profiles?name=${encodeURIComponent(name)}&version=${encodeURIComponent(version)}`;
    if (parent) {
      url += `&parent=${encodeURIComponent(parent)}`;
    }
    for (const op of operations) {
      url += `&operation=${encodeURIComponent(op)}`;
    }
    return this.http.post<{ id: string }>(url, xml, {
      headers: { 'Content-Type': 'application/xml' },
    });
  }

  /**
   * Menghapus satu versi profil. Ditolak (409) bila masih ada simulator yang menunjuknya
   * atau profil lain yang mewarisinya — pesan errornya menyebutkan siapa.
   */
  deleteProfile(name: string, version: string) {
    return this.http.delete(
      `${this.base}/spec-profiles/${encodeURIComponent(name)}/${encodeURIComponent(version)}`
    );
  }

  /** Tempel trace hex dari host asli → buktikan profil membacanya dengan benar. */
  testTrace(name: string, version: string, hex: string) {
    return this.http.post<TraceResult>(
      `${this.base}/spec-profiles/${encodeURIComponent(name)}/${encodeURIComponent(version)}/test-trace`,
      hex,
      { headers: { 'Content-Type': 'text/plain' } }
    );
  }

  // ── scenario ─────────────────────────────────────────────────────────────
  scenarioNames(id: string, operation: string) {
    return this.http.get<string[]>(
      `${this.base}/simulators/${id}/scenarios?operation=${encodeURIComponent(operation)}`
    );
  }

  activeScenario(id: string, operation: string) {
    return this.http.get<{ name: string }>(
      `${this.base}/simulators/${id}/scenarios/active?operation=${encodeURIComponent(operation)}`
    );
  }

  setActiveScenario(id: string, operation: string, name: string) {
    return this.http.put(
      `${this.base}/simulators/${id}/scenarios/active?operation=${encodeURIComponent(operation)}`,
      { name }
    );
  }

  scenarioDefinition(id: string, operation: string, name: string) {
    return this.http.get(
      `${this.base}/simulators/${id}/scenarios/${encodeURIComponent(name)}/definition?operation=${encodeURIComponent(operation)}`,
      { responseType: 'text' }
    );
  }

  saveScenarioDefinition(id: string, operation: string, name: string, json: string) {
    return this.http.put(
      `${this.base}/simulators/${id}/scenarios/${encodeURIComponent(name)}/definition?operation=${encodeURIComponent(operation)}`,
      json,
      { headers: { 'Content-Type': 'application/json' } }
    );
  }

  resetScenarioDefinition(id: string, operation: string, name: string) {
    return this.http.delete(
      `${this.base}/simulators/${id}/scenarios/${encodeURIComponent(name)}/definition?operation=${encodeURIComponent(operation)}`
    );
  }

  // ── state & log ──────────────────────────────────────────────────────────
  accounts(id: string) {
    return this.http.get<IsoAccount[]>(`${this.base}/simulators/${id}/accounts`);
  }

  addAccount(id: string, accountNo: string, holderName: string, balance: string, phone: string) {
    return this.http.post(`${this.base}/simulators/${id}/accounts`, {
      accountNo,
      holderName,
      balance,
      phone,
    });
  }

  cards(id: string) {
    return this.http.get<IsoCard[]>(`${this.base}/simulators/${id}/cards`);
  }

  addCard(id: string, pan: string, accountNo: string) {
    return this.http.post(`${this.base}/simulators/${id}/cards`, { pan, accountNo });
  }

  deleteAccount(id: string, accountNo: string) {
    return this.http.delete(`${this.base}/simulators/${id}/accounts/${encodeURIComponent(accountNo)}`);
  }

  deleteCard(id: string, pan: string) {
    return this.http.delete(`${this.base}/simulators/${id}/cards/${encodeURIComponent(pan)}`);
  }

  seedDemo(id: string) {
    return this.http.post(`${this.base}/simulators/${id}/seed-demo`, {});
  }

  /**
   * URL SSE Live View — sama bentuknya dengan bank simulator (`…/logs/stream`), jadi
   * dashboard tak memperlakukan produk ini sebagai kasus khusus.
   */
  streamUrl(id: string): string {
    return `${this.base}/simulators/${id}/logs/stream`;
  }

  /** Satu halaman riwayat. Pesan baru datang lewat SSE, bukan dari sini. */
  logs(id: string, limit = 20, offset = 0) {
    return this.http.get<{ total: number; rows: IsoLog[] }>(
      `${this.base}/simulators/${id}/logs?limit=${limit}&offset=${offset}`
    );
  }

  /** Mengosongkan riwayat pesan di DATABASE (bukan sekadar tampilan). */
  clearLogs(id: string) {
    return this.http.delete<{ deleted: number }>(`${this.base}/simulators/${id}/logs`);
  }
}
