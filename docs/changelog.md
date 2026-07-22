# Changelog

Catatan perubahan Behavio, **dipisah per simulator** — sejak pemisahan produk
(design.md §3.4), Bank & QRIS adalah produk terpisah penuh (modul, schema PostgreSQL,
dan port sendiri-sendiri). Detail rancangan tetap di `docs/design.md`; file ini ringkasan
"apa yang berubah & kenapa" agar mudah ditelusuri tanpa membaca seluruh spec.

Label: **[BANK]** `product-bank` · **[QRIS]** `product-qris` · **[SHARED]** core-engine /
adapter-web (kena kedua simulator) · **[DASHBOARD]** frontend ·
**[ISO-8583]** `product-iso8583` (modul & schema terpisah penuh, transport TCP).

---

## 2026-07-22

### [ISO-8583] **Defect:** kelas bitmap di packager XML diabaikan
- **Gejala:** profil hasil unggah XML tak bisa membaca pesan host —
  `Pesan terpotong: butuh 4 byte pada posisi 54, tersisa 1` (atau `DE 5 tidak ada di
  kamus` bila kamusnya subset). Keduanya tak menyebut bitmap sama sekali.
- **Sebab:** parser membuang `<isofield id="1" class="…IFA_BITMAP"/>` karena bitmap
  ditangani codec. Akibatnya SEMUA profil dari XML memakai `bitmap: BINARY` bawaan,
  termasuk berkas yang jelas menulis `IFA_BITMAP` (16 karakter ASCII hex).
- **Kenapa gejalanya menyesatkan:** dengan kamus DE lengkap (berkas bank umumnya begitu),
  bit acak dari bitmap salah-baca tetap ketemu definisi DE, jadi parser melahap panjang
  ngawur sampai kehabisan byte — errornya muncul jauh dari sumbernya.
- **Perbaikan:** `IFA_BITMAP` → HEX, `IFB_BITMAP` → BINARY, EBCDIC ditolak; MTI ber-BCD
  juga ditolak alih-alih diabaikan. Transport lain tetap diwarisi induk.
- **Diuji:** error pemakai direproduksi persis dengan `IFB_BITMAP`, lalu XML yang sama
  ber-`IFA_BITMAP` membaca DE7/DE11/DE70 bersih. + 2 unit test. Boot 0 ERROR.
- **Perhatian:** profil XML yang diunggah sebelum perbaikan tetap BINARY (profil
  immutable) — unggah ulang sebagai versi baru lalu alihkan simulatornya.

### [DASHBOARD] Live View kembali ke muat ulang manual
- **Permintaan pemakai:** penyegaran otomatis 3 detik di tab Live View diganti tombol
  **Muat ulang**. Alasannya masuk akal: daftar yang bergeser sendiri justru mengganggu saat
  satu pesan sedang ditelusuri.
- Tab **Rekening & Kartu** tak berubah — ia memang hanya dimuat saat tab dibuka.
- Polling (`setInterval`) dan `OnDestroy` ikut dibuang, jadi tak ada timer yang tertinggal.

### [ISO-8583] Ganti profil spec tanpa membuat ulang simulator
- **Masalah:** profil hanya bisa ditentukan saat simulator dibuat. Untuk pindah profil,
  simulator harus dihapus — ikut membuang rekening, kartu, dan riwayat pesannya. Ini juga
  yang membuat penolakan hapus profil ("masih dipakai simulator X") jadi buntu.
- **Perbaikan:** `PUT /simulators/{id}/spec-profile` + menu **⋮ → Ganti profil spec** di
  dashboard.
- **Perilaku:** profil baru diverifikasi dulu; simulator yang `RUNNING` di-start ulang agar
  codec ikut berganti (koneksi TCP terbuka terputus — disebutkan di dialog konfirmasi);
  rekening/kartu/log tetap utuh.
- **Diuji:** pesan echo bitmap-HEX timeout di profil BINARY → ganti profil saat RUNNING →
  pesan yang sama dibalas `0810 … 00 301`; rekening tetap ada setelah 2x ganti; profil
  lama bisa dihapus setelah tak dipakai; profil tak dikenal ditolak. Boot 0 ERROR.

### [ISO-8583] Profil spec kini bisa dihapus
- **Masalah:** profil bisa ditambah tapi tak bisa dihapus (`405`) — unggahan percobaan
  menumpuk tanpa jalan keluar.
- **Perbaikan:** `DELETE /spec-profiles/{name}/{version}` + tombol hapus di tab Profil Spec.
  Immutability tetap utuh: yang dilarang adalah *mengubah* profil yang dipakai, bukan
  membuang yang tak dipakai.
- **Penjagaan:** ditolak `409` bila masih ditunjuk simulator, atau bila versi terakhir dari
  nama yang punya profil turunan — **beserta daftar pemakainya**, karena "ditolak" saja
  tak memberi tahu apa yang harus ditindak.
- **Diuji:** hapus tak terpakai → OK · dipakai simulator → 409 + nama simulatornya ·
  profil tak ada → 404 · setelah simulator dihapus → OK. Boot 0 ERROR.

### [ISO-8583] Alasan kegagalan tampil di Live View
- **Masalah nyata:** pesan echo `0800` tak pernah dibalas — klien hanya melihat *response
  timeout*, tanpa petunjuk apa pun di dashboard.
- **Sebabnya** bukan bug: bitmap dikirim sebagai **16 karakter ASCII hex**, sedangkan
  profil baseline memakai `bitmap: BINARY` (8 byte mentah). Codec membaca teks `"82200000"`
  sebagai bitmap → DE acak → unpack gagal → tak ada balasan.
- **Perbaikan pemakaian:** profil yang `extends` baseline dengan `transport.bitmap: "HEX"`
  membalas pesan yang sama dengan benar (`0810 … 00 301`).
- **Perbaikan produk:** server sebenarnya sudah tahu alasannya, tapi membuangnya ke log
  aplikasi. Kini disimpan di `request_logs.error` (changeset `006-log-error.sql`) dan
  ditampilkan di **Live View** — balasan kosong kini selalu disertai sebabnya.
- **Diuji:** reproduksi timeout → `error` terbaca lewat API log; profil bitmap HEX →
  dibalas normal. Boot 0 ERROR.

### [ISO-8583] Dukungan kelas packager `IFA_AMOUNT`
- **Masalah nyata:** unggah spec gagal — `Kelas packager belum didukung: 'IFA_AMOUNT'`.
- **Perbaikan:** dipetakan sebagai **`FieldType.AMOUNT` tersendiri**, bukan disamakan
  dengan `N`/`ANS`. Formatnya `C`/`D` + digit rata-kanan berpad '0' (`D00001500`); `N`
  akan mengepad **di depan tanda** dan `ANS` menempel **spasi di belakang digit** —
  panjangnya "benar" tapi isinya rusak.
- **Tanda wajib eksplisit:** nilai tanpa `C`/`D` ditolak saat pack. Default diam-diam
  bisa membalik arah debit/kredit di host yang membedakannya.
- **Catatan:** panjang di packager XML sudah memuat karakter tanda (`x+n8` = `len="9"`).
- **Diuji:** 3 unit test (round-trip, tanda hilang, kapasitas lewat) + uji trace atas
  profil XML yang memuat DE28. Boot 0 ERROR.

### [ISO-8583] Operasi Change PIN & Change Phone Number
- **Yang ditambah:** `change-pin` (DE3 `92`, PIN block baru di **DE53**) dan `change-phone`
  (DE3 `93`, nomor baru di **DE48**). Keduanya masuk sebagai **rute operasi di profil**,
  bukan cabang hardcode — host dengan processing code lain cukup `extends` baseline lalu
  menimpanya.
- **Skema:** `iso8583.accounts.phone`, `iso8583.cards.pin_block`, `cards.pin_changed_at`
  (changeset baru `005-pin-phone.sql`, bukan mengedit yang lama — checksum Liquibase).
- **Keputusan sadar:** PIN lama **tidak diverifikasi**. Verifikasi sungguhan butuh HSM/ZPK;
  PIN block disimpan mentah hanya sebagai bukti operasi sampai, **bukan kredensial**.
  Skenario "PIN salah" ditempuh lewat scenario (DE39=55).
- **Versi profil:** baseline naik ke `iso8583-1987` **v1.1**. v1.0 tetap utuh (profil
  immutable), jadi simulator lama tak berubah perilaku dan tak mendapat operasi ini.
- **Dashboard:** kolom Telepon di tabel rekening, status PIN di tabel kartu, dan isian
  telepon opsional saat membuat rekening.
- **Diuji:** socket TCP nyata — `00` (berhasil, `pin_block` terisi & telepon berubah),
  `30` (DE48/DE53 kosong), `14` (PAN asing). Boot 0 ERROR.
- **Jebakan yang ditemukan:** baris komentar di formatted-SQL Liquibase **tak boleh diawali
  kata "changeset"** — Liquibase 5 mengiranya direktif dan boot gagal.

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
  `:9000`, host benar pun tak membuat port simulator terjangkau. Petakan port simulator di
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
