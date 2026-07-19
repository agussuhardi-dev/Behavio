# Changelog

Catatan perubahan Behavio, **dipisah per simulator** — sejak pemisahan produk
(design.md §3.4), Bank & QRIS adalah produk terpisah penuh (modul, schema PostgreSQL,
dan port sendiri-sendiri). Detail rancangan tetap di `docs/design.md`; file ini ringkasan
"apa yang berubah & kenapa" agar mudah ditelusuri tanpa membaca seluruh spec.

Label: **[BANK]** `product-bank` · **[QRIS]** `product-qris` · **[SHARED]** core-engine /
adapter-web (kena kedua simulator) · **[DASHBOARD]** frontend.

---

## 2026-07-15

### [QRIS] Webhook Payment Notify (service 52) — sesuai ASPI
- **Masalah:** `merchantId` & `paidTime` dikirim di **top-level** body notifikasi, padahal
  keduanya bukan field service 52 menurut ASPI.
- **Perbaikan:** dipindah ke `additionalInfo` (yang ASPI definisikan untuk *"custom use
  not provided by SNAP"*). Field top-level kini hanya yang ada di daftar ASPI.
  `latestTransactionStatus` mengikuti status aktif QR (mis. `03` pending), bukan selalu
  `00`. `QrisService.notify`.
- **Catatan proses:** dugaan lama bahwa daftar field ASPI "tercampur transfer" ternyata
  **salah** — dibuktikan dengan mengambil HTML mentah portal ASPI & parse sendiri, bukan
  ringkasan. Pelajaran: kalau ringkasan terasa janggal, ambil sumber mentah.
- **Ref:** design.md **A3.7**.

### [BANK] Balance Inquiry (11) & Transaction History (12) — saldo/riwayat selalu kosong
- **Masalah:** saldo (`amount`/`availableBalance`/`ledgerBalance`) dan seluruh baris
  riwayat selalu terender `""`. Blueprint memakai `{{amountValue}}` — variabel **request**
  — padahal saldo & transaksi hanya ada di **state**. Endpoint yang gunanya melaporkan
  saldo tak pernah bisa melaporkannya.
- **Perbaikan:**
  - Balance: `enrichAccountVars` mengikat `balanceValue`/`balanceCurrency` dari rekening.
    Variabel sengaja **terpisah** dari `amountValue` agar "nominal request" & "saldo
    rekening" tak bisa tertukar lagi.
  - History: blueprint memakai `@each` (lihat SHARED di bawah) + `enrichCollections` →
    `transactionRows` yang membaca `state.findTransactions()`. Rentang dari
    `fromDateTime`/`toDateTime` (default 30 hari), paging `pageSize`/`pageNumber`.
- **Verifikasi HTTP:** saldo `1000000.00`; riwayat kosong → `[]` (bukan baris hampa);
  2 transfer → 2 baris nyata + paging; saldo konsisten setelah transfer.
- **Ref:** design.md **§8.2.1**.

### [BANK] Hapus kode mati `AccountService` & `TransactionHistoryService`
- **Masalah:** dua kelas di `product-bank/.../web/` dengan **nol pemanggil** tapi masih
  dibuatkan bean — semua operasi bank sebenarnya dirutekan ke engine. Isinya logika yang
  *terlihat* benar, sehingga sempat menyesatkan investigasi bug saldo di atas.
- **Perbaikan:** dihapus total — 2 file + bean `bankAccountService`/
  `bankTransactionHistoryService` + variabel lokal tak terpakai + overload
  `result(...Result)` di `BankProductConfig`. `VirtualAccountService` & `AccessTokenService`
  tidak disentuh.
- **Verifikasi HTTP pasca-hapus:** balance-inquiry `2001100`, account-inquiry `2001500`,
  history `2001200` — semua tetap jalan lewat engine.
- **Ref:** design.md **§8.2.1** (blok "Kode mati — DIHAPUS TOTAL").

### [SHARED] `@each` — pengulangan baris di `ResponseRenderer` (core-engine)
- **Apa:** elemen array bertanda `"@each": "<koleksi>"` menjadi *template baris* yang
  diulang sekali per item koleksi. Tiap item mem-overlay variabel luar; koleksi kosong →
  array kosong (bukan satu baris hampa). Item skalar dirujuk `{{@this}}`.
- **Kenapa di sini:** template response berbentuk struktur JSON (Map/List), bukan teks,
  jadi blok gaya Handlebars tak cocok. Hidup di core-engine → **Bank & QRIS** sama-sama
  bisa memakainya; daftar koleksi yang mengisinya tetap per-produk.
- **Override tetap berkuasa:** `@each` lolos round-trip `ScenarioCodec`, jadi "Edit
  Response" di dashboard tetap bisa mengubahnya.
- **Test:** `ResponseRendererEachTest` (7 kasus). Regresi 7 endpoint QRIS: aman.
- **Ref:** design.md **§8.2.1**.

### [SHARED][DASHBOARD] Contoh curl & OpenAPI `servers[].url` tak lagi hardcode `localhost`
- **Masalah:** `localhost` adalah string harfiah di 19 template curl frontend dan di
  `OpenApiExporter`. Contoh curl hanya berguna di mesin yang menjalankan Behavio — padahal
  gunanya justru ditempel ke Postman/klien di mesin lain. `forward-headers-strategy` saja
  **tak menyembuhkan**, karena tak ada yang menanyakan host ke Spring.
- **Perbaikan:** `PublicHost` (adapter-web) meresolusi host dengan urutan **`DEPLOY_HOST`
  → host request (`X-Forwarded-Host`) → `localhost`**, diekspos via
  `GET /api/admin/v1/config`. Frontend `core/api/public-host.ts` memakainya; fallback
  `location.hostname` bila `/config` gagal. Berlaku untuk kedua dashboard (Bank & QRIS)
  dan export OpenAPI.
- **Batas:** hanya **host** yang diperbaiki, bukan **port**. Port simulator dilayani
  `HttpServer` JDK tersendiri (tak lewat Spring) — kalau reverse proxy hanya meneruskan
  `:8080`, host benar pun tak membuat port simulator terjangkau. Petakan port simulator di
  proxy, atau set `DEPLOY_HOST` ke alamat yang menjangkaunya.
- **Setelan:** `behavio.public-host` (`application.yml`) ← env `DEPLOY_HOST`.
- **Ref:** design.md **§6.4**.

### [DASHBOARD] Label tombol "URL & Webhook"
- **Masalah:** panel registrasi webhook (`app-webhook-panel`) lengkap & berfungsi, tapi
  tersembunyi di balik tombol berlabel "URL Endpoint" yang tak menyebut webhook —
  dilaporkan seolah fiturnya tidak ada.
- **Perbaikan:** label diganti "URL & Webhook" + tooltip, di halaman Bank & QRIS.
- **Ref:** design.md **§9.1**.

---

## Belum dikerjakan / catatan

- **Cash Withdrawal / Tarik Tunai:** ASPI **tidak** punya service ini. Padanan terdekat =
  **Transfer to OTC** (service 44/45/46, path `/{version}/emoney/otc-*`). Belum dibangun —
  menunggu keputusan apakah mengikuti OTC (sesuai ASPI) atau endpoint custom.
