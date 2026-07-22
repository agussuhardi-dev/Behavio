import { Injectable } from '@angular/core';

/**
 * Menyalin teks ke clipboard — dengan jalur cadangan untuk halaman NON-HTTPS.
 *
 * <p><b>Kenapa tidak cukup `navigator.clipboard`:</b> API itu hanya tersedia di
 * <i>secure context</i> (HTTPS atau localhost). Dashboard ini juga diakses lewat
 * `http://<ip>:81` — di sana `navigator.clipboard` bernilai `undefined`, sehingga
 * `navigator.clipboard?.writeText(...)` <b>diam-diam tidak melakukan apa pun</b>: tak ada
 * error, tak ada teks tersalin, dan tombolnya tampak rusak tanpa sebab.
 *
 * <p>Jalur cadangannya `document.execCommand('copy')` atas sebuah textarea sementara.
 * API itu memang usang, tapi ia satu-satunya yang bekerja di konteks tak aman — dan
 * "usang tapi jalan" lebih berguna daripada "modern tapi diam".
 */
@Injectable({ providedIn: 'root' })
export class ClipboardService {

  /** @returns true bila teks benar-benar tersalin. */
  async copy(text: string): Promise<boolean> {
    if (!text) {
      return false;
    }
    if (navigator.clipboard && window.isSecureContext) {
      try {
        await navigator.clipboard.writeText(text);
        return true;
      } catch {
        // Izin ditolak / dokumen tak fokus — turun ke jalur cadangan, jangan menyerah.
      }
    }
    return this.legacyCopy(text);
  }

  private legacyCopy(text: string): boolean {
    const ta = document.createElement('textarea');
    ta.value = text;
    // Di luar layar TAPI tetap terpasang: execCommand mengabaikan elemen yang
    // display:none, dan halaman tak boleh melompat saat elemen ini difokuskan.
    ta.setAttribute('readonly', '');
    ta.style.position = 'fixed';
    ta.style.top = '-1000px';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    try {
      ta.select();
      ta.setSelectionRange(0, ta.value.length);
      return document.execCommand('copy');
    } catch {
      return false;
    } finally {
      document.body.removeChild(ta);
    }
  }
}
