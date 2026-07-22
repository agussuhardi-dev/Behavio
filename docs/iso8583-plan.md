# Rencana: Module Simulator ISO-8583 (backend)

> **Status: RENCANA — lingkup BACKEND saja.** Dashboard/tab UI **belum** termasuk
> (keputusan 2026-07-22: "fokus ke BE saja dulu"). Setujui dokumen ini dulu, baru kode.

---

## 1. Tujuan & peran

Behavio berperan sebagai **HOST / issuer** — persis seperti bank simulator, tapi lewat
**TCP + ISO-8583**, bukan HTTP. Aplikasi Anda berperan sebagai terminal/acquirer yang
mengirim `0200`, Behavio membalas `0210`.

Kegunaan utama: **menguji integrasi ke host Bank Shinhan** tanpa perlu host aslinya.

## 2. Spec = PROFIL yang di-upload (keputusan user 2026-07-22)

**Spec host Shinhan TIDAK publik** (di bawah NDA, sudah dicari). Rancangan awal menaruh
kamus DE di dalam kode — itu **salah arah**: tiap host baru menuntut ubah kode.

**Bentuk final: multi-profile.** Spec adalah **data yang di-upload**, bukan kode:

- Satu **profil spec** = satu host/jaringan (Shinhan, ATM Bersama, PRIMA, …).
- Berisi kamus DE + transport/encoding + routing operasi.
- Tiap simulator **memilih** profil mana yang dipakai.
- **ISO 8583:1987 disediakan sebagai profil BAWAAN** — titik awal untuk di-clone lalu
  disesuaikan, bukan satu-satunya pilihan.

Konsekuensinya penting: **menambah host baru tidak perlu ubah kode sama sekali** — cukup
unggah profil. Ini padanan ISO dari import/export OpenAPI yang sudah ada di produk HTTP.

### Aturan multi-profile (disetujui 2026-07-22)

1. **Profil spec ≠ simulator.** Dua entitas terpisah, relasi **banyak simulator → satu
   spec**. Satu spec "Shinhan v1.0" dipakai beberapa simulator (normal/error/lambat)
   tanpa menyalin kamus DE berulang.
2. **Profil bisa `extends`.** Spec bank di dunia nyata = *"ISO 8583 standar, kecuali N
   field ini"*, jadi profil turunan cukup mendeklarasikan **yang berbeda**. Ini pola yang
   sama dengan blueprint→override di produk HTTP.
3. **Versi immutable.** Unggah perubahan = versi BARU, tak menimpa. Simulator tetap
   menunjuk versi yang sudah diuji — tanpa ini, tes yang kemarin hijau bisa mendadak
   merah tanpa jejak penyebab.
4. **Validasi keras saat unggah** + **uji trace**: user boleh menempel satu trace hex
   dari host asli; Behavio meng-unpack-nya dengan profil baru dan menampilkan DE hasil
   parse. Spec yang tak bisa diverifikasi dari dokumen jadi **terbukti**, bukan
   diasumsikan — ini justru inti nilainya untuk kasus Shinhan.

Sengaja **belum** dibuat: editor spec visual, dukungan semua kelas jPOS di muka, dan
auto-migrasi simulator ke versi baru.

### Format unggahan: jPOS packager XML (utama) + JSON (alternatif)

Bank umumnya menyerahkan spec sebagai **jPOS packager XML** — itu format de-facto. Maka
unggahan menerima **dua format**:

| Format | Kapan dipakai |
|---|---|
| **jPOS packager XML** | Format yang biasanya diberikan bank — unggah apa adanya |
| **JSON** (di bawah) | Untuk disunting tangan / dibuat dari nol |

```xml
<isopackager>
  <isofield id="2"  length="19" name="PAN"             class="org.jpos.iso.IFA_LLNUM"/>
  <isofield id="3"  length="6"  name="PROCESSING CODE" class="org.jpos.iso.IFA_NUMERIC"/>
  <isofield id="39" length="2"  name="RESPONSE CODE"   class="org.jpos.iso.IFA_CHAR"/>
</isopackager>
```

> **PENTING — tetap TIDAK memakai jPOS.** Yang didukung hanya **format berkasnya**;
> XML-nya diparse sendiri. Tak ada dependensi jPOS, jadi **AGPL v3 tetap terhindar**.

**Pemetaan kelas → `FieldSpec`** (`IF[encoding]_[tipe][prefix]`):

| Awalan | Encoding | | Akhiran | Panjang |
|---|---|---|---|---|
| `IFA_` | ASCII | | *(tanpa)* | tetap |
| `IFB_` | BCD/biner | | `LL…` | 2 digit |
| `IFE_` | EBCDIC | | `LLL…` | 3 digit |

Contoh: `IFA_LLNUM` → ASCII, numerik, LLVAR · `IFA_NUMERIC` → ASCII, numerik, tetap ·
`IFB_BINARY` → biner, tetap.

> **Kelas yang tak dikenal DITOLAK, bukan ditebak.** Dokumentasi jPOS sendiri tidak
> selalu konsisten soal lebar prefiks beberapa kelas biner. Menebak = pesan salah format
> di kabel — kegagalan yang menyamar jadi "host menolak" dan mahal dilacak. Unggahan
> gagal dengan pesan menyebut kelasnya, lalu kelas itu ditambahkan ke tabel pemetaan
> setelah dipastikan dari spec nyata.

### Bentuk berkas profil (JSON)

```jsonc
{
  "name": "Bank Shinhan Host", "version": "1.0",
  "transport": {
    "lengthPrefixBytes": 2,        // 2 = 2-byte header · 4 = 4-byte
    "lengthPrefixEncoding": "BINARY",  // BINARY | ASCII
    "charset": "ASCII",            // ASCII | EBCDIC
    "bitmap": "BINARY"             // BINARY (8 byte) | HEX (16 char)
  },
  "fields": [
    { "de": 2,  "name": "PAN",             "type": "n",   "length": 19,  "lengthPrefix": 2 },
    { "de": 3,  "name": "Processing Code", "type": "n",   "length": 6,   "lengthPrefix": 0 },
    { "de": 39, "name": "Response Code",   "type": "an",  "length": 2,   "lengthPrefix": 0 }
  ],
  "operations": [
    { "name": "balance-inquiry", "mti": "0200", "processingCode": "30" },
    { "name": "transfer",        "mti": "0200", "processingCode": "40" },
    { "name": "network",         "mti": "0800" }
  ]
}
```

Validasi saat unggah: DE 1–128, `lengthPrefix` ∈ {0,2,3}, panjang > 0, tak ada DE ganda,
operasi tak bertabrakan (MTI+processingCode unik). **Profil ditolak utuh bila tak valid** —
lebih baik gagal saat unggah daripada saat host asli sudah menunggu balasan.

## 3. Keputusan & asumsi

| # | Hal | Keputusan |
|---|---|---|
| 1 | Peran | **Host/issuer** (menerima 0200, membalas 0210) |
| 2 | Kemandirian | Module `iso8583` **mandiri penuh** — nol dependency ke `simulator`/`qris` |
| 3 | Schema | `iso8583` sendiri |
| 4 | **Rekening** | ⚠️ **ASUMSI: TERPISAH** dari bank simulator. Saldo di ISO ≠ saldo di bank. Turunan langsung dari "tidak berhubungan sama sekali". **Belum dikonfirmasi user.** |
| 5 | PIN & MAC | **Tidak dikriptografi sungguhan** (butuh HSM/ZPK). "PIN salah" disimulasikan lewat scenario → DE39=`55` |
| 6 | Port | Dialokasikan `platform.port_registry` (satu-satunya yang bersama, demi keunikan port lintas produk). Rentang usulan **9201+** |
| 7 | Dashboard | **Di luar lingkup** untuk sekarang |

## 4. Kamus DE baseline (terverifikasi dari referensi standar)

| DE | Nama | Format | Dipakai untuk |
|---|---|---|---|
| 2 | PAN | `n LLVAR ..19` | identifikasi kartu |
| 3 | Processing code | `n 6` | **routing operasi** |
| 4 | Amount | `n 12` | nominal |
| 7 | Transmission date/time | `n 10` | |
| 11 | STAN | `n 6` | korelasi req/resp |
| 12/13 | Local time/date | `n 6` / `n 4` | |
| 32 | Acquiring institution ID | `n LLVAR ..11` | |
| 35 | Track 2 | `z LLVAR ..37` | |
| 37 | RRN | `an 12` | |
| 38 | Auth ID response | `an 6` | |
| **39** | **Response code** | `an 2` | **hasil transaksi** |
| 41 | Terminal ID | `ans 8` | |
| 42 | Merchant ID | `ans 15` | |
| 49 | Currency | `an 3` | |
| 52 | PIN block | `b 64` | (tidak diverifikasi) |
| **54** | **Additional amounts** | `an LLLVAR ..120` | **saldo** |
| 70 | Network mgmt code | `n 3` | echo/sign-on |
| 90 | Original data elements | `n 42` | reversal |
| **102/103** | **Account ID 1 / 2** | `ans LLVAR ..28` | **rekening asal/tujuan** |

**DE3** = tipe transaksi(2) + rekening asal(2) + rekening tujuan(2).

## 5. Operasi yang didukung

| Operasi | MTI | DE3 | Request | Response |
|---|---|---|---|---|
| Cek saldo | `0200`→`0210` | `30xxxx` | DE2, DE41, DE102 | DE39, **DE54 (saldo)** |
| Transfer | `0200`→`0210` | `40xxxx` | DE2, DE4, DE102→DE103 | DE39, DE54 |
| Tarik tunai | `0200`→`0210` | `01xxxx` | DE2, DE4, DE102 | DE39, DE54 |
| Echo / sign-on | `0800`→`0810` | — | DE7, DE11, DE70 | DE39, DE70 |
| Reversal | `0400`→`0410` | — | DE90 | DE39 |

**DE39 baseline:** `00` sukses · `51` saldo tak cukup · `55` PIN salah · `14` kartu tak
valid · `54` kartu kedaluwarsa · `91` issuer down · `96` system malfunction.

## 6. Transport (default, semuanya bisa dikonfigurasi)

- **TCP** persisten, satu port per simulator.
- **Frame:** prefiks panjang **2-byte biner** (network byte order).
- **Encoding field:** ASCII · **bitmap** 8-byte biner (sekunder bila DE1 aktif).

## 7. Bentuk module

```
main-app ──> simulator (bank)  [HTTP]
         ──> qris             [HTTP]
         ──> iso8583          [TCP]   ← baru
```

**Keputusan penting — memakai ulang mesin konfigurasi platform.** Tabel platform
(`simulators`/`partners`/`endpoints`/`scenarios`/`request_logs`) dipakai apa adanya,
dengan "endpoint" dipetakan ke operasi ISO lewat kunci sintetis:

```
method = "ISO"      path = "0200/30"   (MTI/tipe-transaksi)
```

**Untungnya:** CRUD simulator, penyimpanan & **override scenario ("Edit Response")**,
Live View, dan Admin API **jalan tanpa ditulis ulang**. **Harganya:** kolom
`method`/`path` dipakai di luar makna aslinya — dicatat di sini supaya tak
membingungkan pembaca berikutnya.

Tabel khusus ISO (schema `iso8583`):

| Tabel | Isi |
|---|---|
| **`spec_profiles`** | **profil spec yang di-upload** — nama, versi, definisi (jsonb), asal berkas (XML/JSON) |
| `cards` | PAN, nomor rekening terkait, status, PIN offset (dummy) |
| `accounts` | nomor rekening, nama, saldo, mata uang — **terpisah dari `bank.accounts`** |
| `transactions` | jejak transaksi (STAN, RRN, MTI, DE3, amount, DE39) |

Tiap simulator menunjuk satu `spec_profile`. Profil bawaan (ISO 8583:1987) di-seed saat
migrasi, siap di-clone.

## 8. Yang baru vs yang dipakai ulang

| Bagian | Status |
|---|---|
| Codec ISO-8583 (MTI, bitmap, LLVAR/LLLVAR) | **BARU** — ditulis sendiri |
| Transport TCP + framing | **BARU** |
| Model pesan (`IsoMessage`) & template response ber-DE | **BARU** |
| Mesin scenario/rule/outcome, fault injection | **pakai ulang** (salinan sendiri) |
| Siklus hidup simulator, port registry, Live View, Admin API | **pakai ulang** |

> **Lisensi:** **tidak memakai jPOS** — lisensinya AGPL v3 dan menular bila Behavio
> di-host untuk pihak lain. Packer ditulis sendiri untuk subset DE di atas, konsisten
> dengan `core-engine` yang sengaja bebas dependensi.

## 9. Langkah eksekusi (bertahap, hijau tiap langkah)

1. **Skeleton module** `iso8583` + schema + changelog. → build hijau. *(sudah dimulai:
   module, `FieldSpec`, `FieldType`, `IsoMessage`, kamus baseline)*
2. **Profil spec**: tabel `spec_profiles`, parser **jPOS packager XML** + JSON, validasi,
   seed profil bawaan, Admin API unggah/daftar/pilih → **unit test** parser (termasuk
   kasus kelas tak dikenal harus DITOLAK).
3. **Codec** (pack/unpack: MTI, bitmap primer+sekunder, LLVAR/LLLVAR, ASCII/biner),
   digerakkan profil → **unit test** round-trip dengan trace contoh.
3. **Transport TCP** + framing 2-byte → uji koneksi & echo `0800`/`0810`.
4. **Handler operasi** (saldo, transfer, tarik tunai) di atas state `iso8583`
   → uji socket nyata.
5. **Scenario & override** (DE39 salah, saldo tak cukup, timeout, putus koneksi)
   → uji fault injection.
6. **Live View** + request_logs → verifikasi tercatat.
7. *(Nanti, di luar lingkup)* Tab dashboard.

**Titik verifikasi tiap langkah:** build hijau · unit test lulus · uji **socket nyata**
(bukan hanya unit test) · log tanpa ERROR.

## 10. Yang perlu dikonfirmasi sebelum eksekusi

1. **Rekening terpisah** (§3 poin 4) — benar terpisah dari bank simulator?
2. Ada dokumen/trace Shinhan walau sebagian? Akan sangat mempersempit default.
3. Rentang port `9201+` — cocok?

---

## 11. Cara membuat / menambah profil spec (panduan pakai)

Tiga cara, semuanya diverifikasi jalan. Lewat dashboard: tab **ISO-8583 → Profil Spec →
Unggah profil spec**. Lewat API:

### Cara 1 — unggah packager XML (paling lazim; bentuk yang diberikan bank)

```bash
curl -X POST "http://localhost:9000/api/admin/v1/iso8583/spec-profiles\
?name=shinhan&version=1.0\
&operation=balance-inquiry:0200:30\
&operation=transfer:0200:40\
&operation=network-management:0800" \
  -H 'Content-Type: application/xml' --data-binary @packager.xml
```

Berkas packager hanya memuat daftar field, jadi identitas & rute operasi lewat query param
(`operation=nama:mti[:processingCode]`).

### Cara 2 — `extends` (DISARANKAN untuk spec bank)

Spec bank hampir selalu "ISO 8583 standar, kecuali N field ini" — cukup deklarasikan
yang berbeda:

```bash
curl -X POST ".../spec-profiles" -H 'Content-Type: application/json' -d '{
  "name":"shinhan","version":"1.0","extends":"iso8583-1987",
  "fields":[{"de":54,"name":"SALDO khusus","type":"ans","length":40,"lengthPrefix":3}],
  "operations":[{"name":"balance-inquiry","mti":"0200","processingCode":"30"}]
}'
```

Hasilnya tetap 31 DE — 30 diwarisi, DE54 ditimpa.

### Cara 3 — JSON penuh (profil dari nol)

Sertakan `transport`, `fields`, `operations`. Dipakai bila host sangat berbeda dari standar.

### Alur kerja sebenarnya untuk host yang spec-nya tak lengkap

Inilah gunanya uji trace — dan ini alur yang dipakai untuk Bank Shinhan:

1. Unggah profil versi awal (tebakan terbaik, biasanya `extends: iso8583-1987`).
2. Ambil **satu trace hex nyata** dari host (atau dari Live View simulator).
3. `POST .../spec-profiles/{name}/{version}/test-trace` dengan hex itu.
4. Kalau gagal, errornya **menunjuk sebabnya** — contoh nyata:
   `"Pesan memuat DE 41 yang tidak ada di kamus profil"`.
5. Tambahkan DE yang kurang, unggah sebagai **versi baru** (profil immutable).
6. Ulangi sampai `ok: true`. Saat itu profil **terbukti**, bukan diasumsikan.

> **Profil immutable.** Mengunggah `name`+`version` yang sama ditolak. Naikkan versinya —
> simulator lama tetap menunjuk versi yang sudah diuji, jadi tes yang kemarin hijau tak
> mendadak merah.

### Memakai profil di simulator

```bash
curl -X POST ".../iso8583/simulators" -H 'Content-Type: application/json' \
  -d '{"name":"Host Shinhan","port":9201,
       "specProfileName":"shinhan","specProfileVersion":"1.0"}'
```

Simulator menunjuk **versi tertentu** — bukan "terbaru" — supaya perilakunya tak berubah
diam-diam saat ada unggahan profil baru.

### 11.1 Port simulator & `network_mode: host` (keputusan 2026-07-22)

Berawal dari keluhan nyata `Connection refused` ke `10.1.20.3:9201`.

**Akar masalah:** simulator mengalokasikan port saat **runtime**, sedangkan pemetaan port
Docker bersifat **statis saat container dibuat**. Port di luar rentang yang terlanjur
dipetakan tak pernah dipublikasikan — aplikasi hidup normal, tapi klien ditolak. Gejalanya
menyesatkan karena tampak seperti aplikasi mati.

**Keputusan:** backend memakai **`network_mode: host`**. Port apa pun yang dibuka aplikasi
langsung terjangkau; `SIM_PORT_START/END` tak lagi dipakai untuk pemetaan, dan menambah
blok port produk baru tak menuntut recreate container.

**Harga yang disadari:** isolasi jaringan hilang (container melihat semua interface host)
dan backend bersaing langsung memperebutkan port host.

#### Konsekuensi ke nginx — dua jebakan, keduanya sudah diuji

Backend keluar dari jaringan bridge, jadi nama service `backend` tak bisa di-resolve lagi.

1. **JANGAN `127.0.0.1`.** Frontend tetap di bridge, jadi loopback di sana menunjuk
   container nginx sendiri — bukan host. Dipakai `host.docker.internal`, dipetakan lewat
   `extra_hosts: host-gateway`.
2. **JANGAN `proxy_pass $variabel`.** Bentuk variabel membuat nginx me-resolve saat request
   lewat direktif `resolver` (DNS Docker), yang **tidak membaca `/etc/hosts`** — padahal di
   situlah `extra_hosts` menulis. Gejalanya `recv() failed ... while resolving` lalu 499.
   Terbukti di uji: `wget` dari dalam container berhasil, nginx gagal. Solusinya bentuk
   **literal**, yang di-resolve sekali saat startup lewat `/etc/hosts`.

Diverifikasi end-to-end dengan nginx + backend host-mode sungguhan: `/api/` 200 (prefiks
terjaga), jalur SSE 200, SPA 200, dan saat backend dimatikan nginx **tetap start** dengan
`/api/` → 502 (bukan crash-loop — inilah alasan bentuk variabel dulu dipakai, dan entri
`/etc/hosts` yang statis membuatnya tak lagi perlu).

Alokasi port: bank **9001+** · QRIS **9101+** · ISO-8583 **9201+**.
