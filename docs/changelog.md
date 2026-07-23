# Changelog

Catatan perubahan Behavio, **dipisah per simulator** — sejak pemisahan produk
(design.md §3.4) Bank & QRIS adalah produk terpisah penuh, dan sejak 2026-07-22
**ISO-8583** menyusul sebagai produk ketiga di atas socket TCP (design.md §3.5). Masing-
masing punya modul, schema PostgreSQL, dan port sendiri.

Detail rancangan tetap di `docs/design.md` (§14 = status implementasi) dan
`docs/architecture.md` (§4 = cara menambah produk, §5 = keterbatasan yang diketahui);
khusus ISO-8583 ada `docs/iso8583-plan.md`. File ini ringkasan "apa yang berubah & kenapa"
agar mudah ditelusuri tanpa membaca seluruh spec.

Label: **[BANK]** `product-bank` · **[QRIS]** `product-qris` · **[SHARED]** core-engine /
adapter-web (kena kedua simulator) · **[DASHBOARD]** frontend ·
**[ISO-8583]** `product-iso8583` (modul & schema terpisah penuh, transport TCP) ·
**[DEPLOY]** skrip & compose di `deploy/`.

---

## 2026-07-23

### [DASHBOARD] Live View menampilkan DE3 & alasan routing
- **Dilaporkan pemakai:** *"saya request `400000` tapi di log tercatat
  `transfer-on-us-inquiry`"*.
- **Routing terbukti benar** — diuji: `400000` → `transfer-on-us`, `330000` →
  `transfer-on-us-inquiry`, `010000` (di luar profil `shinhan-klien`) → tak ada rute,
  `DE39=30`. Kecocokan memakai processing code 6 digit penuh, jadi tak mungkin tertukar.
- **Masalah sebenarnya: pemakai tak bisa memeriksanya sendiri.** Baris log hanya memuat
  nama operasi hasil routing; nilai yang MENENTUKAN routing (DE3) tersembunyi di dalam hex.
- **Perbaikan:** `processing_code` disimpan di `request_logs` (changeset
  `007-log-processing-code.sql`), ikut disiarkan lewat SSE, dan tampil sebagai lencana di
  baris Live View. Detail barisnya menampilkan alasannya utuh:
  `MTI 0200 + DE3 400000 → transfer-on-us`, atau `tak ada rute di profil — dibalas DE39=30`.
- Klien Shinhan mengirim DE3 dari `transactionType.value()`
  (`EdcTransactionServiceImpl:273`), jadi operasi yang tercatat = jenis transaksi yang
  benar-benar dikirim. Alur inquiry→eksekusi mengirim **dua** pesan; yang terlihat lebih
  dulu di Live View adalah yang terbaru.

### [ISO-8583] Profil bawaan kedua: `shinhan-klien` (persis enum klien)
- **Permintaan pemakai** setelah pemeriksaan §13.4: sediakan profil yang cakupannya
  **persis** enum `TransactionType` klien.
- **`shinhan-klien` v1.0** ditanam saat boot berdampingan dengan `shinhan-default` v1.1 —
  17 processing code, tanpa `010000`/`341000`/`500000` yang hanya ada di simulator
  referensi.
- **Diturunkan, bukan disalin:** `clientProfile()` = `profile()` dikurangi ketiga kode itu,
  supaya kedua daftar tak bisa melenceng diam-diam.
- **Nama baru, bukan `shinhan-default` v1.2** — profil turunan memakai versi TERBARU dari
  nama induknya, jadi v1.2 akan mengubah perilaku turunan yang sudah dipakai tanpa suara.
- **Diuji:** 17 kode cocok persis dengan enum klien (nol kurang, nol lebih); simulator
  dialihkan ke `shinhan-termsys` v3.0 (packager XML klien + induk `shinhan-klien`) →
  `310000` dibalas `00`, `010000` dibalas `30`. Boot 0 ERROR.

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

### [ISO-8583] Disesuaikan dengan simulator referensi klien (`bankshinhan-simulator`)
- **Sumber:** kode simulator Shinhan milik pemakai — acuan paling otoritatif sejauh ini.
- **Operasi yang HILANG di kami, kini ditambahkan** (profil `shinhan-default` **v1.1**):
  `withdraw` `010000` · `sale` `500000` · `create-pin` `341000`.
- **Reversal MTI `0420`** — klien mengirim advice `0420`/`0430`, bukan `0400`/`0410`.
  Tanpa rute ini pesan reversal tak dikenali dan dibalas `DE39=30`. `0400` dipertahankan
  untuk profil generik.
- **Saldo (DE54) ikut dibalas** pada `change-pin`, `create-pin`, `change-phone`,
  `reset-password-ib`, dan inquiry transfer — simulator referensi melakukan hal yang sama.
  Yang dilaporkan saldo PEMEGANG KARTU (dari PAN/Track 2), bukan rekening tujuan; melaporkan
  saldo rekening tujuan ke terminal yang cuma menanyakan nama bukan perilaku host mana pun.
- **Diuji lewat socket:** `withdraw` debit 50,00 · `sale` debit 25,00 · `create-pin` `00` ·
  **reversal `0420` → `0430`** `00` dan saldo kembali +50,00. Saldo akhir cocok.
  Boot 0 ERROR.
- **Field balasan disamakan** (§13.3): **DE54 kini angka polos** seperti referensi — bukan
  lagi additional-amounts ISO 20 karakter, karena klien mem-parse format polos; plus DE14,
  DE38, DE60, DE102, DE62 (router), dan DE48 (key exchange).
- **Nilainya diturunkan, bukan diacak:** DE38 dari STAN, DE60 dari tanggal, DE14 dari
  Track 2. Balasan yang tak bisa diulang membuat bug sulit direproduksi dan DE38 acak tak
  bisa dicocokkan dengan log. Perilaku state tetap alami — saldo benar-benar berpindah.
- **Field hanya diisi bila profil mengenalnya**, jika tidak pack gagal dan host tampak "tak
  membalas". DE14 ikut ditambahkan ke kamus baseline.
- **Diuji:** balasan saldo memuat DE14 `4912` · DE38 `003001` · DE54 `499915500`
  (= 4.999.155,00, cocok dengan saldo) · DE60 `000023` · DE102 `1111111111`; router → DE62
  `AJ`; key exchange → DE48 kunci. Boot 0 ERROR.

### [DASHBOARD] PIN block ditampilkan (dan kenapa PIN-nya sendiri tak bisa)
- **Ditanya pemakai:** *"bagaimana kalau lupa PIN — bukankah harusnya tampil di dashboard?"*
- **PIN-nya tak pernah kami punya.** Yang dikirim terminal adalah **PIN block** terenkripsi
  di bawah working key; mengembalikannya jadi angka PIN menuntut HSM/ZPK. Menampilkan
  "PIN: 1234" berarti mengarangnya.
- **Yang penting:** PIN **tidak pernah diverifikasi** simulator, jadi lupa PIN bukan
  penghalang — nilai apa pun di DE52/DE53 diterima. Ini kini ditulis di panel informasi
  request supaya tak perlu ditebak.
- **Yang ditampilkan:** PIN block terakhir yang diterima + kapan diubah, di kolom PIN tabel
  kartu dan di panel informasi request, lengkap dengan tombol salin untuk dikirim ulang.
  Kolomnya tak lagi cuma "ter-set"/"belum".
- **Diuji:** `GET /cards` kini mengembalikan `pinBlock` + `pinChangedAt`. Boot 0 ERROR.

### [ISO-8583] **Defect:** scenario penolakan tetap memindahkan dana
- **Dilaporkan pemakai:** *"bukankah perubahan saldo harus menyesuaikan response?"* — dan
  memang benar.
- **Gejala:** scenario `Saldo Tidak Cukup` membalas `DE39=51`, tapi saldo tetap terdebit
  dan transaksinya tetap tercatat. `PIN Salah` membalas `55`, tapi PIN tetap berganti.
- **Sebab — keputusan kami sendiri yang salah:** scenario sengaja ditimpakan **setelah**
  logika alami, dengan alasan "agar penolakan bisa diuji tanpa memalsukan state". Alasan
  itu keliru: tak ada host yang memindahkan dana pada transaksi yang ditolaknya, dan klien
  yang diuji terhadap simulator seperti itu lulus untuk alasan yang salah — jalur
  kompensasi/reversal-nya tak pernah benar-benar teruji.
- **Perbaikan:** scenario yang memaksa DE39 ≠ `00` diperiksa **sebelum** logika alami
  dijalankan, dan logika itu dilewati sepenuhnya — tak ada debit, kredit, perubahan PIN
  atau telepon, dan tak ada baris transaksi. DE54 tetap dilampirkan berisi saldo yang
  **belum** berubah, sebagaimana host asli menjawab transaksi tertolak.
- **Penolakan ALAMI tak berubah** (saldo kurang, rekening tak ada): memang sudah tak
  memindahkan dana.
- **Diuji:** transfer 100,00 · Normal → `00`, 999.250→999.150 & 1.550→1.650, transaksi
  tercatat · `Saldo Tidak Cukup` → `51`, kedua saldo **utuh**, **tak ada** baris transaksi,
  DE54 melaporkan saldo yang benar · change-PIN + `PIN Salah` → `55`, `pin_block` tetap
  nilai lama. Boot 0 ERROR.

### [DASHBOARD] Tab ISO-8583 diingat setelah refresh
- Halaman selalu kembali ke tab pertama saat di-refresh, padahal simulator yang dipilih
  sudah diingat sejak lama (`behavio.iso.lastSimId`). Kini tabnya ikut
  (`behavio.iso.lastTab`).
- **Disimpan sebagai LABEL, bukan indeks.** Indeks bergeser diam-diam begitu ada tab baru
  disisipkan dan pemakai mendarat di tab yang salah tanpa gejala — jebakan yang sudah
  pernah mengenai kode ini saat tab "Koneksi" ditambahkan. Label yang tak dikenal cukup
  jatuh ke tab pertama.
- `activeTab` diisi saat `ngOnInit`, sebelum `reload()`, karena pemuatan data per-tab
  membacanya dan berjalan lebih dulu daripada event pertama `mat-tab-group`.
- **Diverifikasi:** daftar label di kode dibandingkan dengan `<mat-tab label=…>` di
  template — sama persis dan urutannya sama.

### [DASHBOARD] Header halaman ISO-8583 disamakan dengan produk lain
- Halaman ISO langsung membuka dengan bar pemilih simulator, sementara Bank
  (`.sim-hero`) dan QRIS (`.qris-hero`) punya header pengantar. Kini ISO memakai pola yang
  sama (`.iso-hero`) — gradien, ukuran, dan struktur judul/teks identik.
- **Isinya informatif, bukan hiasan:** menyebut bahwa produk ini berperan sebagai host di
  atas **socket TCP, bukan HTTP** — beda paling mendasar dari dua produk lain dan yang
  paling sering disalahpahami; bahwa bentuk pesan ditentukan **profil spec** yang diunggah;
  dan bahwa datanya terpisah penuh (schema `iso8583`, rekening & port sendiri).
- Judul "Simulator ISO-8583" yang tadinya di bar pemilih dihapus agar tak muncul dua kali;
  ikonnya tetap.

### [DASHBOARD] Panduan Skenario ISO-8583 di bawah halaman
- Menyusul pola QRIS (`Panduan Skenario QRIS`) dan Bank (`Panduan Skenario Transfer`):
  15 scenario bawaan ditampilkan lengkap dengan ikon, nada (ok/warn/fault), dan penjelasan
  singkat — termasuk **kode DE39**-nya.
- **Tiga scenario transport dijelaskan terpisah** karena tak punya padanan di produk HTTP:
  pada TCP, "Tanpa Balasan", "Koneksi Diputus", dan "Timeout" adalah tiga kegagalan berbeda
  yang klien harus tangani berbeda pula — dan justru itu yang paling jarang diuji.
- Ditegaskan di panduannya: scenario ditimpakan **setelah** logika alami, jadi mutasi saldo
  tetap terjadi apa adanya dan yang berubah hanya apa yang dilaporkan.
- **Diverifikasi cocok dengan backend:** 15 nama di `ISO_SCENARIOS` dibandingkan dengan
  `GET .../scenarios` — tak ada selisih di kedua arah.

### [DASHBOARD] Tombol salin tak berfungsi di halaman non-HTTPS
- **Gejala:** tombol salin di tab Koneksi (dan semua tombol salin lain) tak melakukan
  apa-apa — tanpa error, tanpa umpan balik.
- **Sebab:** `navigator.clipboard` hanya tersedia di **secure context** (HTTPS atau
  localhost). Dashboard juga diakses lewat `http://<ip>:81`, dan di sana objek itu
  `undefined` — sehingga `navigator.clipboard?.writeText(...)` **diam-diam tidak melakukan
  apa pun**. Tanda tanya opsional itulah yang menelan kegagalannya. Lewat tunnel Cloudflare
  (HTTPS) tombolnya bekerja, jadi gejalanya tampak acak.
- **Perbaikan:** `ClipboardService` baru dengan jalur cadangan
  `document.execCommand('copy')` atas textarea sementara — usang, tapi satu-satunya yang
  bekerja di konteks tak aman. "Usang tapi jalan" mengalahkan "modern tapi diam".
- **Tak lagi gagal senyap:** bila kedua jalur gagal, pemakai diberi tahu (pesan error di
  ISO, snackbar di QRIS & Bank).
- **Berlaku untuk ketiga produk:** ISO-8583, QRIS, dan Bank sama-sama memakai pola lama
  yang cacat itu; ketiganya kini lewat service yang sama.

### [DASHBOARD] Tab Rekening & Kartu tak ikut segar saat ada lalu lintas
- **Gejala:** setelah change-PIN berhasil, kolom PIN tetap tertulis **"belum"** — tampak
  seperti operasinya gagal padahal berhasil (API dan database sudah `pinSet: true`).
- **Sebab:** tab itu hanya dimuat saat tab dibuka atau simulator dipilih. Pesan yang tiba
  lewat socket mengubah state (saldo, PIN, telepon) tanpa ada yang memberitahu dashboard.
  SSE yang ditambahkan sebelumnya hanya menyegarkan daftar log.
- **Perbaikan:** tiap event SSE ikut menjadwalkan muat ulang rekening & kartu — pola yang
  sama dipakai bank simulator (`reloadVa`/`reloadBank` pada tiap RequestEvent).
- **Dengan jeda 600 ms:** satu klien bisa mengirim puluhan pesan beruntun; memuat ulang di
  setiap pesan berarti puluhan request yang hasilnya langsung ditimpa.

### [ISO-8583] Arah transfer `42x000` ditentukan DATA, bukan nama operasi
- **Gejala:** `0200` DE3=`421000` dibalas `DE39=52` padahal rekeningnya jelas ada.
- **Sebab — asumsi kami:** `421000`/`422000` dianggap SELALU "dana masuk dari bank lain",
  jadi hanya DE103 yang dicari sebagai rekening lokal. Pesan nyata membuktikan kode yang
  sama juga dipakai untuk dana **keluar**: DE102=`1111111111` (rekening lokal, pengirim),
  DE103=`2222222222` + DE127=`014` (bank tujuan). Yang dicari salah sisi → `52`.
- **Perbaikan:** arah dibaca dari data. Simulator hanya memegang rekening satu bank, jadi
  sisi mana yang ADA di sini sudah cukup menentukan: pengirim lokal → **debit** (keluar);
  kalau tidak, penerima lokal → **kredit** (masuk); tak satu pun → `52`. Ini menghapus
  seluruh kelas salah-tebak arah, bukan menambal satu kasus.
- **Sisi seberang tak pernah disentuh** — ia di bank lain; memindahkan saldo rekening lokal
  yang kebetulan bernomor sama akan menciptakan uang.
- **Diuji:** pesan asli pemakai → `00`, saldo 5.000.000 → 4.999.200 (debit 800) ·
  arah masuk (pengirim asing, penerima lokal) → `00`, 1.350 → 1.550 · kedua sisi asing →
  `52` · saldo kurang → `51`. Boot 0 ERROR.

### [ISO-8583] **Defect:** change-PIN menuntut PIN baru di DE53, klien mengirim di DE48
- **Gejala:** `0200` DE3=`340000` dari klien nyata dibalas `DE39=30`, padahal pesannya sah
  dan ter-parse bersih (19 DE).
- **Sebab:** handler hanya membaca **DE53** untuk PIN block baru. Klien Shinhan
  menaruhnya di **DE48** (`EBA90CC188FA5C66`, 16 hex — bentuk yang sama dengan DE52 berisi
  PIN lama). Dua konvensi ini sama-sama dipakai di lapangan; kami cuma mengenal satu.
- **Perbaikan:** DE53 dulu, lalu DE48 — pola yang sama seperti perbaikan inquiry DE103→DE102.
- **Diuji:** pesan asli pemakai diputar ulang → dari `30` menjadi `00`; `pin_block`
  tersimpan `EBA90CC188FA5C66` dan `pin_changed_at` terisi. Boot 0 ERROR.
- **Tetap berlaku:** PIN lama (DE52) TIDAK diverifikasi — butuh HSM/ZPK. "PIN lama salah"
  diuji lewat scenario (DE39=55).

### [DEPLOY] Cloudflare Tunnel: token hilang dari `.env` + penjaga agar tak terulang
- **Sebab langsung:** `CLOUDFLARE_TUNNEL_TOKEN` di `.env` **kosong**. Tokennya hanya
  tertinggal di `.env.backup-backend-lama` — kemungkinan hilang saat `.env` ditulis ulang
  pada migrasi ke backend-split. Dipulihkan (187 char, terbaca sebagai token cloudflared
  yang sah: account + tunnel id + secret).
- **Kenapa senyap:** compose menerima `${VAR}` kosong, cloudflared jalan dengan
  `--token ` lalu mati-hidup selamanya, dan `deploy.sh` tetap melaporkan **"Deploy
  selesai!"**. Tak ada satu pun tanda kecuali log container.
- **Penjaga 1 — compose:** `${CLOUDFLARE_TUNNEL_TOKEN:?...}`. Token kosong kini membuat
  `docker compose` menolak start dengan pesan yang menyebutkan sebabnya.
- **Penjaga 2 — deploy.sh:** dicek SEBELUM build & transfer, jadi gagalnya di detik
  pertama, bukan setelah JAR terkirim.
- **Penjaga 3 — verifikasi pasca-deploy:** setelah `docker compose up`, log cloudflared
  diperiksa untuk `Registered tunnel connection`; bila tak ada, 20 baris terakhir dicetak
  alih-alih melaporkan sukses.
- **Didokumentasikan di compose:** origin tunnel yang benar adalah `http://frontend:80` —
  **bukan** `localhost` (di dalam container itu cloudflared sendiri) dan bukan `backend`
  (memakai `network_mode: host`, tak ada di jaringan bridge). Semua `/api/` termasuk SSE
  lewat nginx frontend.
- **Susulan — token ternyata RUSAK, bukan cuma hilang:** nilainya tersimpan dengan `---`
  menempel di ujung (187 char, bukan 184). cloudflared menolaknya —
  *"Provided Tunnel token is not valid"* lalu exit 255 berulang. Menyesatkan karena
  `base64.b64decode` bawaan **mengabaikan** karakter di luar alfabet, jadi token itu
  tampak sah saat diperiksa sekilas; hanya decode ketat (`validate=True`) yang menangkapnya.
  Panjang yang tak habis dibagi 4 adalah tanda pertama.
- **Penjaga 4 — bentuk token:** deploy.sh menolak token yang memuat karakter di luar
  base64, dan yang tak terbaca sebagai JSON `{a,t,s}` saat di-decode **ketat**.
- **Diuji:** token kosong → compose *dan* deploy.sh menolak · token ber-`---` → ditolak
  dengan pesan yang menyebutkan sebabnya · token bersih (184 char) → lolos semua penjaga
  dan `docker compose config` valid.

### [ISO-8583] **Defect:** inquiry transfer on-us menuntut DE103, klien mengirim DE102
- **Gejala:** pesan `0200` DE3=`330000` dari klien nyata dibalas `DE39=30` (format error),
  padahal pesannya sah dan ter-parse bersih (18 DE, termasuk DE55 EMV).
- **Sebab:** `transfer-on-us-inquiry` hanya membaca **DE103**. Pada inquiry cuma ada SATU
  rekening yang ditanyakan — milik penerima — dan klien menaruhnya di **DE102**; DE103
  baru terpakai saat eksekusi. Varian off-us sudah benar sejak awal, yang on-us terlewat.
- **Perbaikan:** DE103 dulu, lalu DE102 — sama seperti varian off-us.
- **Diuji:** pesan asli pemakai (398 byte) diputar ulang ke socket → dari `30` menjadi
  `00` + DE48 `"1111111111 TSM CAFE"` (nama penerima). Boot 0 ERROR.

### [ISO-8583] Panel informasi request + Track 2 (DE35) kini dipakai
- **Dashboard:** tiap kartu di tab Rekening & Kartu punya panel informasi request —
  PAN, contoh Track 2, rekening sumber/tujuan, nominal, PIN block, telepon, STAN, DE70,
  dan **processing code seluruh operasi profil**, semuanya siap salin.
- **Track 2 tak wajib.** Rekening sumber dicari berurutan `DE102 → DE2 → DE35`.
- **Perbaikan nyata:** DE35 sebelumnya **tak pernah dibaca**, jadi terminal kartu-hadir
  yang mengirim hanya track tanpa DE2 selalu dibalas `DE39=14` tanpa petunjuk apa pun.
  Pemisah `=` maupun `D` sama-sama diterima (keduanya ditemui di lapangan).
- **Sengaja tidak diperiksa:** kedaluwarsa & service code di dalam track — simulator tak
  menyimpan tanggal kedaluwarsa, jadi memeriksanya hanya berpura-pura. "Kartu kedaluwarsa"
  tetap lewat scenario (DE39=54).
- **Diuji:** balance-inquiry dengan **hanya DE35** (pemisah `=` dan `D`) → `00` + saldo
  benar; PAN asing di track → `14`. Boot 0 ERROR.

### [ISO-8583] **Defect:** status simulator berbohong setelah restart aplikasi
- **Gejala:** setelah backend restart, dashboard tetap menampilkan `RUNNING` padahal tak
  ada listener TCP sama sekali — klien ditolak *connection refused* sambil melihat status
  hijau. Gejalanya menyerupai kerusakan jaringan, padahal cukup ditekan Start.
- **Sebab:** `RUNNING` disimpan di database, sedangkan yang membuka port adalah listener di
  memori. Restart menghapus yang kedua, tak menyentuh yang pertama.
- **Perbaikan:** saat boot, semua simulator dikembalikan ke `STOPPED` dan dijalankan ulang
  **manual**. Menyalakannya otomatis dipertimbangkan lalu ditolak: port bisa sudah dipakai
  proses lain setelah restart, dan gagal diam-diam saat boot lebih buruk daripada tombol
  Start yang jelas.
- **Diuji:** simulator RUNNING + listener `:3000` aktif → restart → status `STOPPED`, tak
  ada listener, log mencatat `1 simulator dikembalikan ke STOPPED`; Start manual
  menghidupkan `:3000` lagi. Boot 0 ERROR.
- **Belum disentuh:** produk bank & QRIS kemungkinan punya celah yang sama — di luar
  lingkup permintaan ini.

---

## 2026-07-22

### [ISO-8583] **Defect:** profil bawaan simulator baru menunjuk versi yang tak ada
- **Gejala:** membuat simulator tanpa menyebut profil → `Profil 'iso8583-1987' versi '1.0'
  tidak ada`. Versi bawaannya dihardcode `1.0`, padahal baseline sudah naik ke `1.1` dan
  `1.0` bisa dihapus — pembuatan simulator gagal total.
- **Perbaikan:** bawaan kini `shinhan-default` v1.0, **19 operasi lengkap**, dan nama/versinya
  diambil dari konstanta seeder-nya, bukan string yang ditulis ulang dan bisa melenceng.
- **Dashboard:** dropdown profil saat membuat simulator memilih `shinhan-default` otomatis,
  bukan sekadar profil pertama dalam daftar (yang bisa saja baseline generik separuh isi).
- **Diuji:** `POST /simulators` tanpa `specProfileName` → tercipta di `shinhan-default` v1.0;
  ketujuh operasi yang dicek (termasuk yang baru) masing-masing punya **15 scenario**. Boot
  0 ERROR.

### [ISO-8583] Operasi bawaan Shinhan lengkap
- **Sumber:** `TransactionType` milik klien — processing code nyata, bukan tebakan.
- **Ditanam sebagai profil `shinhan-default` v1.0** (mewarisi kamus DE `iso8583-1987`):
  19 operasi — saldo `310000`, change PIN `340000`, change phone `700000`, reset password IB
  `710000`, transfer on-us `330000`/`400000`, ke bank lain `351000`/`411000`, masuk
  tabungan `361000`/`421000` & giro `362000`/`422000`, lewat `371000`/`431000` &
  `372000`/`432000`, router `900000`, reversal, network-management.
- **Kode 6 digit penuh, bukan awalan 2 digit:** `700000` dan `710000` sama-sama berawalan
  "70" — awalan pendek membuat salah satunya tak pernah terpanggil.
- **Arah dana dibedakan sungguhan:** on-us memindahkan dua sisi; keluar hanya mendebit;
  masuk hanya mengkredit; numpang-lewat tak menyentuh saldo dan **tidak dicatat** sebagai
  transaksi (kalau dicatat, reversal-nya memindahkan dana yang tak pernah berpindah).
- **Profil tanpa operasi kini DITOLAK** saat unggah, dengan pesan yang menyebutkan jalan
  keluarnya (`operation=` atau `parent=shinhan-default`) — sebelumnya tersimpan diam-diam
  lalu membalas DE39=30 untuk segalanya.
- **Diuji:** 17 kasus lewat socket nyata memakai packager.xml asli klien — saldo akhir
  cocok di tiap arah (A 1.000.000 → 999.250, B 0 → 1.350), inquiry mengembalikan nama
  penerima, `51` saat saldo kurang, `52` saat rekening tujuan tak ada, PIN & telepon
  berubah. 15 scenario otomatis tersedia untuk tiap operasi baru. Boot 0 ERROR.

### [ISO-8583] Live View: hapus riwayat + paging
- **Hapus riwayat:** `DELETE /simulators/{id}/logs` + tombol **Hapus riwayat**. Menghapus
  di **database**, bukan sekadar mengosongkan tampilan — itu yang dimaksud orang setelah
  sesi uji yang berantakan. Rekening, kartu, dan profil tak tersentuh; dialog konfirmasi
  menyebutkan itu.
- **Paging:** `GET /logs?limit=&offset=` kini mengembalikan `{total, rows}` + `mat-paginator`
  (10/20/50/100). Riwayat ISO cepat menumpuk — 25 pesan uji saja sudah melewati satu layar.
- **Urutan deterministik:** `ORDER BY created_at DESC, id DESC`. Dua pesan bisa tercatat
  pada mikrodetik yang sama, dan urutan yang tak pasti membuat baris melompat antar-halaman
  tepat saat satu pesan sedang ditelusuri.
- **SSE + paging hidup berdampingan:** pesan baru disisipkan hanya saat berada di halaman 1;
  di halaman lain ia jadi lencana **"N pesan baru"** yang mengembalikan ke halaman 1. Tanpa
  ini, halaman yang sedang dibaca akan bergeser sendiri — gangguan yang sama seperti polling.
- **Diuji:** 25 pesan → 3 halaman (10/10/9), STAN berurut turun tanpa tumpang tindih;
  `DELETE` menghapus 29 baris, total jadi 0, rekening tetap 2. Boot 0 ERROR.

### [ISO-8583] Live View jadi benar-benar live (SSE) — menyamai bank simulator
- **Masalah:** Live View ISO memakai tombol muat ulang, sedangkan halaman `/simulators`
  (bank) sudah **push lewat SSE**. Dua produk, dua perilaku, tanpa alasan.
- **Perbaikan:** `IsoSseBroadcaster` + `GET /simulators/{id}/logs/stream`, bentuk URL sama
  persis dengan bank agar dashboard tak memperlakukan ISO sebagai kasus khusus. Frontend
  memakai `EventSource`; tombol muat ulang diganti indikator sambungan + tombol bersihkan.
- **Bonus yang ikut benar:** `operation` dan `response_code` di `iso8583.request_logs`
  selama ini SELALU null — kini terisi, dan ikut tampil di baris Live View sehingga bisa
  dibaca sekilas tanpa mem-parse hex di kepala.
- **Diuji:** stream SSE nyata menerima dua `event:exchange` seketika saat pesan tiba
  (`operation: network-management`, `responseCode: 00`), dan baris yang sama tersimpan di
  `request_logs` dengan kedua kolom terisi. Boot 0 ERROR.
- **Catatan:** riwayat tetap dimuat dari database saat simulator dipilih — bank memulai
  dengan daftar kosong, ISO tidak, karena hex pesan lama justru yang dipakai Uji Trace.

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

- **Cash Withdrawal / Tarik Tunai (produk BANK):** ASPI **tidak** punya service ini.
  Padanan terdekat = **Transfer to OTC** (service 44/45/46, path
  `/{version}/emoney/otc-*`). Belum dibangun — menunggu keputusan apakah mengikuti OTC
  (sesuai ASPI) atau endpoint custom. *(Tarik tunai di produk ISO-8583 sudah ada:
  `withdraw` DE3 `010000` — beda produk, beda kanal.)*
- **Belum di-deploy:** seluruh pekerjaan ISO-8583 & perbaikan dashboard 22–23 Juli masih
  di kerja lokal. `10.1.20.3` masih menjalankan build lama, sehingga profil
  `shinhan-default` belum ada di sana dan bitmap dari packager XML masih terbaca BINARY.
- **Bank & QRIS belum diperiksa** untuk dua celah yang ditemukan di ISO: status simulator
  yang tetap `RUNNING` setelah restart aplikasi, dan panel state yang tak ikut segar saat
  ada lalu lintas. Tombol salin sudah diperbaiki untuk ketiga produk.
- **`TransactionType` — sudah diperiksa (2026-07-23), tidak ada yang kurang.**
  Perbandingan tiga sumber: klien `bankshinhan-termsys` **17** processing code · simulator
  referensi **20** · profil `shinhan-default` v1.1 **20**.
  - Dikirim klien tapi tak ada di profil kami: **tidak ada** ✓
  - Kelebihan kami: `010000` WITHDRAW · `341000` CREATE_PIN · `500000` SALE — ketiganya
    dari simulator referensi, tak ada di enum klien.
  - **Dibiarkan.** Rute dicocokkan per processing code, jadi kode yang tak pernah dikirim
    tak pernah aktif — nol biaya. Membuangnya menuntut profil v1.2 + realihkan simulator,
    tanpa keuntungan fungsional.
  - **Yang masih terbuka** bukan daftar kodenya, melainkan **arah** `421000`/`422000`:
    komentar enum menyebut "bank lain → shinhan", pesan nyata justru dana keluar. Ditangani
    lewat arah-dari-data di `transferExternal()`; menunggu konfirmasi pemakai.
- **Unit test ISO-8583** baru menutup codec & parser profil. Operasi host, scenario, dan
  reversal sejauh ini diverifikasi lewat socket nyata, belum lewat test otomatis.
