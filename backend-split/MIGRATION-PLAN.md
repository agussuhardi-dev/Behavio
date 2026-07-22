# Rencana Migrasi — Pemisahan Penuh per Simulator

> # ✅ MIGRASI SELESAI (switchover 2026-07-19)
>
> **`backend/` lama sudah DIHAPUS.** `backend-split/` kini satu-satunya backend.
> Dokumen ini disimpan sebagai catatan bagaimana migrasi dilakukan.
>
> **Switchover yang dilakukan:**
> - 7 file test dipindah (4 generik + 2 bank → `simulator`; 1 EMV QR → `qris`).
>   **44 test jalan, 0 gagal** — sama persis dengan `backend/` lama.
> - `deploy/deploy.sh` diarahkan: `BACKEND_DIR` → `backend-split`, modul `:app` →
>   `:main-app`, jar → `main-app/build/libs/behavio.jar`.
> - Nama bootJar dikembalikan ke `behavio.jar` (bukan `behavio-bank.jar`) — main-app
>   merakit SEMUA produk, dan deploy.sh memang mencari nama itu.
> - `.gitignore` digeneralisasi (`**/build/`, `**/.gradle/`) + 357 file artefak build
>   (67 MB) di-untrack.
>
> **Verifikasi sebelum hapus:** setiap kelas `backend/` dicek punya padanan — hanya
> `CoreBeansConfig` yang tidak, dan itu memang digantikan `PlatformBeansConfig` per produk.
> Sesudah hapus: `clean build` hijau, 44 test lulus, boot hijau 0 ERROR, HTTP bank
> (`2001100`, VA 404 `4040012`) & qris (`2004700`) normal.
>
> **⚠️ Disimpan manual:** `backend/.env` memuat `CLOUDFLARE_TUNNEL_TOKEN` yang **berbeda**
> dari root `.env` (kunci lain identik). Diamankan sebagai `.env.backup-backend-lama`
> (ter-gitignore). Pastikan token mana yang benar, lalu hapus berkas cadangan itu.
>
> ### Progres
> - ✅ **Bank + QRIS SELESAI & TERGABUNG 2026-07-19.** Tiga module berdiri:
>   `simulator` (Bank), `qris`, `main-app` (launcher). Build hijau; **boot main-app dengan
>   KEDUA produk hijau, 0 ERROR**; **HTTP terverifikasi**:
>   - Bank: balance `2001100` (saldo utuh), transfer `2001700`, history `2001200` (`@each`).
>   - QRIS: generate `2004700`, query `2005100`.
>   - Isolasi: akses silang produk → **404** (registry & schema terpisah).
> - **Bukti mandiri:** `simulator` & `qris` **nol `project()` dependency** & **nol
>   referensi silang** (`id.behavio.qris` ⊄ simulator, sebaliknya juga). Tiap produk bawa
>   salinan `id.behavio.<produk>.platform.*` sendiri.
>
> ### Cara bentrok "dua salinan generik dalam satu app" diselesaikan
> - **Nama bean @Component bentrok** (mis. `ProductRegistry`) → `main-app` pakai
>   `FullyQualifiedAnnotationBeanNameGenerator` → nama bean = FQN, unik per package.
> - **Path admin `{product}` bentrok** → constraint regex per module:
>   `{product:bank}` (simulator) & `{product:qris}` (qris) = pola berbeda, tak bentrok,
>   `require(product)` tetap jalan tanpa diubah.
> - **Controller lintas-produk** (health `/ping`,`/config`, `webhook-sink`,
>   `ApiExceptionHandler`) → dipindah ke `main-app` sekali; `PublicHost` tetap di tiap
>   produk (dipakai export OpenAPI-nya).
> - **`WebhookWorker`** → tiap produk memfilter `List<SchemaTables>` ke schema-nya sendiri.
> - **Liquibase** → `platform` changelog milik `main-app`; `bank`/`qris` di jar module
>   masing-masing; master `db.changelog-main.yaml` merakit semua (path & checksum identik).
>
> ### Utang teknis yang DICATAT (belum dikerjakan)
> 1. **Test belum disalin** ke `simulator/` & `qris/` (mis. `ResponseRendererEachTest`,
>    `BehaviorEnginePipelineTest`, `OpenApiRoundTripTest`). Hanya `src/main` dipindah.
>    Perlu disalin + repackage sebelum switchover.
> 2. **Frontend belum diarahkan** ke backend-split (masih pakai `backend/` lama). Path
>    admin identik (`/api/admin/v1/{bank,qris}/...`) jadi harusnya nol perubahan kode FE —
>    perlu smoke test.
> 3. **Switchover** `backend/` lama → `backend-split/` belum dilakukan (keputusan terpisah).
>
> Nama `backend-split/` sementara — silakan ganti.

---

## 1. Tujuan

Bank & QRIS menjadi **module mandiri penuh**: logika, kode, dan schema database
sendiri-sendiri, **tanpa modul bersama untuk logika produk**. Sasaran utama: **debug satu
simulator tanpa menyentuh/merusak yang lain**.

**Trade-off yang diterima secara sadar** (sudah dikonfirmasi): kode generik (engine,
admin API, persistence) **diduplikasi** ke tiap produk. Konsekuensinya perbaikan bug di
mesin generik dikerjakan dua kali. Ini harga yang dibayar demi kemandirian penuh.

---

## 2. Keputusan yang sudah disepakati

| # | Keputusan |
|---|---|
| 1 | Tetap **1 projek, 1 repo, 1 frontend** — bukan pisah repo. |
| 2 | Backend jadi **3 module**: `main-app`, `simulator` (Bank), `qris`. |
| 3 | `simulator` (Bank) & `qris` **mandiri total** — tak berbagi kode produk; masing-masing bawa salinan engine/adapter sendiri. |
| 4 | `main-app` = launcher tipis yang merakit keduanya. |
| 5 | Duplikasi kode & schema **diterima**. |
| 6 | Dibangun di **direktori baru** (`backend-split/`); yang lama utuh sampai switchover. |

---

## 3. Keadaan sekarang (fakta terverifikasi)

**8 module** (`backend/`), graf dependensi:

```
core-engine (murni, 1271 baris)
   ▲
   ├── adapter-persistence (1380)   ┐
   ├── adapter-web (2024)           │ dipakai KEDUA produk
   ├── adapter-signature (100)      │
   ├── adapter-webhook (163)        ┘
   ▲
   ├── product-bank (3392) ──┐
   ├── product-qris (1926) ──┤
   └── app (52) ─────────────┴── merakit semua
```

Yang **sudah** terpisah: schema DB (`bank`/`qris`/`platform`), Liquibase changelog
(`db/changelog/{platform,bank,qris}/`), port per-simulator, blueprint/service per produk.

Yang **masih** bersama: 5 module generik (~4.938 baris) — inilah yang akan diduplikasi.

---

## 4. Target arsitektur

**3 module**, graf dependensi **tanpa siklus** (justru karena produk mandiri penuh):

```
simulator (Bank)   qris
    ▲                ▲
    └──── main-app ──┘        main-app bergantung pada keduanya;
                              keduanya tak bergantung pada apa pun
```

### Isi tiap module

| Module | Isi |
|---|---|
| **simulator** (Bank) | Salinan `core-engine` + adapter yang dipakai + `product-bank` (blueprint transfer/VA/account, service, EMV tak ada di sini) + changelog `bank/` + schema `bank`. Mandiri: `implementation` kosong (self-contained). |
| **qris** | Salinan `core-engine` + adapter yang dipakai + `product-qris` (MPM, EMV QR) + changelog `qris/` + schema `qris`. Mandiri. |
| **main-app** | Bootstrap Spring Boot; `DataSource`; schema `platform` (`port_registry`); health/config; import `@Configuration` kedua produk. `implementation(project(":simulator"))` + `implementation(project(":qris"))`. |

**Frontend**: tetap 1 app Angular, bicara ke `main-app` di `:8080` lewat
`/api/admin/v1/{bank,qris}/...` — **tak perlu diubah**.

### ⚠️ Satu titik yang TAK bisa 100% terpisah: `platform.port_registry`

Karena tetap **satu app di satu host**, port tiap simulator harus unik lintas produk
(bank 9001+, qris 9101+) — kalau tidak, bentrok port. Dua opsi (**perlu keputusan**,
lihat §7):
- **(A, rekomendasi)** `port_registry` tetap milik `main-app` (schema `platform`). Satu
  titik bersama yang kecil & memang lintas-produk secara alami.
- **(B)** Duplikasi penuh: tiap produk punya registry sendiri, keunikan port dijaga
  konvensi rentang. Lebih "murni" tapi bentrok port jadi mungkin.

---

## 5. Estimasi biaya

Duplikasi ~4.900 baris generik ke **tiap** produk. Setelah migrasi, tiap perbaikan mesin
generik (contoh sesi ini: `@each`, host `localhost`, jsonb) dikerjakan **2×**. Diterima.

---

## 6. Langkah eksekusi (bertahap, build hijau tiap langkah)

Tiap langkah berdiri sendiri & diverifikasi sebelum lanjut. Kalau satu langkah gagal,
langkah sebelumnya tetap utuh.

1. **Skeleton Gradle** di `backend-split/`: `settings.gradle.kts` + 3 module kosong +
   toolchain JDK 25. → `./gradlew build` hijau (kosong).
2. **`main-app` skeleton**: bootstrap Spring Boot + DataSource + schema `platform`
   (`port_registry`) + `/api/admin/v1/ping`,`/config`. → boot hijau, `/ping` UP.
3. **Module `qris`**: salin core-engine + subset adapter + `product-qris` + changelog
   `qris/`; rakit `@Configuration` mandiri; `main-app` import. → boot hijau + **uji HTTP**
   generate/query/refund/webhook 52 (bandingkan dengan `backend/` lama).
4. **Module `simulator` (Bank)**: sama untuk bank + changelog `bank/`. → boot hijau +
   **uji HTTP** transfer/balance/history/VA/account-inquiry.
5. **Frontend**: arahkan ke `main-app` baru; smoke test kedua dashboard (build + tembak
   semua URL yang dipanggil dashboard).
6. **Regresi penuh**: semua endpoint bank + qris via HTTP, dibandingkan output lama.
   Sisir null dengan request minimal (pola yang sudah terbukti).
7. **Switchover** (keputusan terpisah, nanti): `backend/` lama dipensiunkan / diarsipkan.

**Titik verifikasi wajib tiap langkah:** `./gradlew build` hijau · boot tanpa ERROR di log
· endpoint terkait balas rc yang benar · `git status` bersih dari file tak sengaja.

---

## 7. Yang harus dikonfirmasi sebelum eksekusi

1. **Nama direktori** `backend-split/` — pakai ini atau ganti?
2. **`port_registry`** — opsi (A) tetap di main-app *(rekomendasi)* atau (B) duplikasi?
3. **Kode yang benar-benar invarian** (`adapter-signature` 100 baris, `adapter-webhook`
   163) — ikut diduplikasi demi kemurnian, atau tetap bersama di `main-app` supaya tak
   dobel-rawat? *(Rekomendasi: duplikasi semua sesuai tujuan kemandirian; tapi ini titik
   yang wajar dikecualikan kalau Anda mau pragmatis.)*
4. **Cakupan langkah 1** — mulai dari skeleton kosong *(rekomendasi)*, atau langsung
   salin satu produk penuh?

---

## 8. Checklist verifikasi akhir (definition of done)

- [ ] `backend-split/` build hijau; `backend/` lama tetap build hijau (belum disentuh).
- [ ] `simulator` & `qris` tak punya `implementation(project(...))` ke satu sama lain.
- [ ] Ubah 1 baris di engine `qris` → build `simulator` tetap hijau (bukti mandiri).
- [ ] Semua endpoint bank & qris balas rc identik dengan `backend/` lama.
- [ ] Webhook 52, `@each` history, host `DEPLOY_HOST` tetap berfungsi di struktur baru.
- [ ] 1 frontend melayani kedua produk tanpa perubahan kode.
- [ ] Nol null-leak (sisir request minimal).
