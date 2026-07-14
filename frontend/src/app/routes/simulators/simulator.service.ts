import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Simulator {
  id: string;
  name: string;
  port: number;
  status: 'RUNNING' | 'STOPPED';
}

export interface Scenario {
  name: string;
  desc: string;
  icon: string;
  tone: 'ok' | 'warn' | 'fault';
}

/** Scenario preset (Fase 1–2) beserta penjelasan ramah end-user. */
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

  setScenario(id: string, name: string) {
    return this.http.put(`${this.base}/${id}/active-scenario`, { name });
  }

  /** Ambil definisi JSON scenario (custom bila ada, selain itu preset blueprint). */
  getDefinition(id: string, scenario: string) {
    return this.http.get(`${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition`, {
      responseType: 'text',
    });
  }

  /** Simpan definisi custom (override request cond + response). */
  saveDefinition(id: string, scenario: string, json: string) {
    return this.http.put(`${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition`, json, {
      headers: { 'Content-Type': 'text/plain' },
    });
  }

  /** Kembalikan scenario ke preset (hapus override). */
  resetDefinition(id: string, scenario: string) {
    return this.http.delete(`${this.base}/${id}/scenarios/${encodeURIComponent(scenario)}/definition`);
  }

  /** URL SSE live view (dilewatkan proxy dev ke :8080). */
  streamUrl(id: string): string {
    return `${this.base}/${id}/logs/stream`;
  }
}
