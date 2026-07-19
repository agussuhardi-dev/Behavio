import { HttpClient } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';

/**
 * Host yang dipakai contoh `curl` di dashboard.
 *
 * Dulu setiap contoh curl menulis `http://localhost:<port>` harfiah, jadi ia hanya
 * berguna di mesin yang menjalankan Behavio — padahal gunanya justru untuk ditempel ke
 * Postman/klien di mesin LAIN. Menambahkan `forward-headers-strategy` di Spring tak
 * menolong: setelan itu hanya berlaku kalau ada yang bertanya host ke Spring, sementara
 * string ini dikompilasi ke bundle browser.
 *
 * Urutan: `DEPLOY_HOST` dari backend (satu-satunya yang tahu alamat publik saat port
 * simulator dipetakan lewat NAT/proxy) → host yang sedang dipakai browser → `localhost`.
 * Backend sudah menerapkan urutan yang sama; `location.hostname` di sini hanya jaring
 * pengaman bila /config gagal diambil.
 */
@Injectable({ providedIn: 'root' })
export class PublicHost {
  private readonly http = inject(HttpClient);

  /** Dipakai template curl; terisi ulang begitu /config terjawab. */
  readonly host = signal<string>(PublicHost.browserHost());

  private loaded = false;

  private static browserHost(): string {
    return typeof window !== 'undefined' && window.location.hostname
      ? window.location.hostname
      : 'localhost';
  }

  /** Idempoten — aman dipanggil dari tiap halaman yang butuh. */
  load(): void {
    if (this.loaded) return;
    this.loaded = true;
    this.http.get<{ publicHost?: string }>('/api/admin/v1/config').subscribe({
      next: cfg => {
        if (cfg?.publicHost) this.host.set(cfg.publicHost);
      },
      error: () => {
        /* pertahankan host browser — tetap jauh lebih berguna daripada localhost */
      },
    });
  }
}
