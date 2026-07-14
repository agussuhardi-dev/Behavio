# Dokumen Desain — API Behavior Platform (Bank & Payment Simulator)

> Status: **Desain (hasil diskusi awal)** · Terakhir diperbarui: 2026-07-13
>
> Dokumen ini merangkum keputusan desain hasil diskusi. Menjadi acuan sebelum
> implementasi. Catatan: beberapa keputusan **merevisi** `readme.md` (lihat
> bagian *Perubahan terhadap README*).

---

## 1. Ringkasan Produk

Sebuah **simulator API transaksi finansial** yang bisa diatur lewat dashboard,
berperilaku semirip sistem asli (bank & PJP), bukan sekadar mock statis.

- **Domain:** perbankan & pembayaran — **SNAP BI** (Standar Nasional Open API
  Pembayaran) dan **QRIS** (dynamic/static). **Tidak** melebar ke logistik/
  e-commerce.
- **Bentuk produk:** tool developer **self-hosted lokal** (mirip WireMock/
  Mockoon), bukan SaaS multi-tenant.
- **Prinsip:** *configuration over code* — perilaku endpoint diatur lewat
  konfigurasi/dashboard, bukan menulis kode Java.
- **Stateful:** wajib. Simulator mengingat saldo rekening, status transaksi,
  deteksi duplikat (idempotensi), token aktif.

### Use case
1. Testing aplikasi sendiri (yang memanggil API bank/pihak ketiga).
2. Sandbox untuk partner (developer lain integrasi ke sistem kita).
3. Demo & training.
4. Load / performance test (mengganti pihak ketiga saat uji beban).

---

## 2. Konsep Inti (Mesin Generik)

Produk = bank simulator; **mesin di dalamnya generik/modular** — dirakit dari
komponen netral yang dapat dipakai ulang, bukan logika transfer yang di-hardcode.

Komponen mesin: **Rule Engine, State Store, Response Template, Webhook, Fault
Injection, Scenario, Signature/Auth**.

### Prinsip: Blueprint dapat di-override penuh
Simulator dikirim dengan **Blueprint** preset yang **akurat sesuai spesifikasi
resmi** (mis. SNAP BI dari ASPI/BI). **Namun preset hanyalah titik awal** — user
dapat meng-override **semua elemen** per-simulator, tanpa koding:

- **Path/URL** endpoint (mis. `/v1.0/transfer-intrabank` → variasi bank)
- **Header** (tambah/ubah/hapus, termasuk header wajib)
- **Request schema** (tambah/ubah/hapus field)
- **Response body** (bentuk & isi)
- **responseCode / mapping** kode & pesan

> **Alasan nyata:** implementasi antar bank berbeda. Contoh: ASPI memakai
> `/v1.0/transfer-intrabank`, sedangkan **BRI** memakai
> `/intrabank/snap/v2.0/transfer-intrabank`. Field & versi pun bisa bervariasi.
> Bila ada dokumen eksternal bank tertentu, user cukup menyesuaikan override —
> *configuration over code* sepenuhnya. Lihat Lampiran A untuk spec preset.

### Dua bidang data (jangan dicampur)
- **Bidang Konfigurasi** — "cetak biru": endpoint, scenario, rule, response.
  Jarang berubah, diatur dari dashboard.
- **Bidang State/Runtime** — "dunia yang disimulasikan": saldo, transaksi,
  token. Berubah tiap request.

---

## 2A. Arsitektur & Design Patterns

### Klarifikasi istilah
**Spring MVC ≠ pola arsitektur.** Keduanya di lapisan berbeda dan saling
melengkapi:
- **Spring MVC** = lapisan **web** (cara request HTTP ditangani). Keputusan
  "MVC (bukan WebFlux)" murni soal model threading (blocking vs reaktif).
- **Hexagonal/Clean/Layered** = **arsitektur aplikasi** (cara seluruh kode
  diorganisir).

→ Spring MVC dipakai sebagai **salah satu adapter inbound** di dalam arsitektur
Hexagonal.

### Arsitektur makro: Hexagonal (Ports & Adapters)
Inti produk = **Behavior Engine** yang harus (a) bebas framework & I/O agar
dapat diuji terisolasi, (b) punya banyak implementasi yang dapat ditukar, (c)
melayani banyak pintu masuk. Ini kasus ideal untuk Hexagonal.

```
   INBOUND ADAPTERS                CORE (DOMAIN)               OUTBOUND ADAPTERS
 ┌────────────────────┐     ┌───────────────────────┐     ┌──────────────────────┐
 │ Spring MVC (per-port)│───►│                       │────►│ PostgreSQL (state)    │
 │ Admin REST API       │ in │   BEHAVIOR ENGINE      │ out │ Webhook sender(outbox)│
 │ SSE live view        │───►│  (rule, state machine,│────►│ Signature verifier     │
 │ OpenAPI importer     │port│   workflow, fault)    │port │  (RSA/HMAC)           │
 └────────────────────┘     │  — murni, tanpa I/O   │     │ Event bus (SSE)       │
                            └───────────────────────┘     │ JEXL evaluator        │
                                                          └──────────────────────┘
```

Manfaat konkret:
- **Engine diuji tanpa Spring/DB** (fake state port) — krusial karena logika
  transfer/idempotensi harus super-andal (ini uang).
- **Tukar implementasi tanpa sentuh core** — signature STRICT↔SIMULATED,
  evaluator JEXL→mini, in-memory→Postgres = ganti adapter saja.
- **Multi pintu masuk** — per-port HTTP kini; gRPC/ISO-8583 nanti = tambah
  adapter inbound.

### Pola desain di dalam engine (GoF & integrasi)
| Bagian | Pola |
|---|---|
| Pipeline eksekusi (signature→validasi→rule→…) | **Chain of Responsibility / Pipeline** |
| Mode signature, jenis fault, evaluator | **Strategy** |
| Evaluasi Condition AST | **Interpreter** |
| State machine transaksi/VA | **State** |
| Webhook tertunda + retry | **Outbox** |
| Bikin Blueprint/Response | **Builder / Factory** |
| Isolasi per-partner di query | **Repository** + filter tenant |

### Kenapa bukan yang lain
- **Layered polos** — domain mudah bocor ke framework/DB; sulit menguji engine
  terisolasi. Kurang cocok untuk core yang harus andal & pluggable.
- **Clean Architecture** — pada dasarnya Hexagonal + aturan dependensi lebih
  ketat; sering overhead berlebih untuk tim kecil. Hexagonal beri ~90% manfaat,
  lebih ringan.
- **Microservices** — soal deployment, bukan struktur kode; belum relevan (satu
  aplikasi self-hosted).

### Sketsa struktur modul (Gradle)
```
:core-engine     domain murni (rule, state machine, workflow, pipeline, ports)
                 — TANPA dependensi Spring/JPA
:adapter-web     Spring MVC (per-port) + Admin API + SSE   (inbound)
:adapter-persistence  JPA/Postgres + Liquibase             (outbound)
:adapter-signature    RSA/HMAC verifier                    (outbound)
:adapter-webhook      outbox sender + retry                (outbound)
:app             perakitan (wiring) + konfigurasi Spring Boot
```
> Aturan dependensi: `:core-engine` tak bergantung pada adapter mana pun;
> adapter bergantung pada port yang didefinisikan di core. Framework menempel
> di tepi, bukan di jantung.

---

## 3. Model Data

### 3.1 Hirarki & Tenancy
```
Workspace (opsional — pengelompokan)
  └─ Simulator  (satu bank/PJP; punya port sendiri + lifecycle)
       ├─ KONFIGURASI
       │    Partner, Endpoint, Scenario, Rule, ResponseTemplate,
       │    WebhookConfig, FaultConfig
       └─ STATE  (selalu terikat partner_id — isolasi penuh per-partner)
            accounts, transactions, access_tokens, idempotency,
            entities (JSON), request_logs
```

- **Isolasi penuh per-partner:** tiap partner punya dunia rekening & transaksi
  sendiri yang tak tercampur. Setiap baris state membawa
  `(simulator_id, partner_id)`.
- **Workspace:** opsional (routing sudah ditangani port). Berguna sebagai
  pengelompokan bila diperlukan.

### 3.2 Strategi penyimpanan — Hybrid
- **Tabel kaku (relational)** untuk entitas pembawa uang — integritas dijaga DB:
  - `accounts` — `balance NUMERIC CHECK (balance >= 0)`, per-partner
  - `transactions` — `status`, `amount`, `reference_no` unik per simulator
  - `access_tokens` — token B2B + expiry
  - `idempotency` — `(simulator_id, partner_id, external_id)` UNIQUE
  - `request_logs` — Recorder/Replay
- **Entitas generik (JSON/JSONB)** untuk sisanya (tanpa migrasi tiap nambah):
  - `entities(id, simulator_id, partner_id, type, data JSONB, status, created_at)`
  - `type` = `virtual_account | qr | ...`

> Alasan hybrid: ini **uang** — integritas saldo tak bisa ditawar (bug saldo
> minus = simulator tak dipercaya). Fleksibilitas JSON tetap didapat untuk
> entitas non-uang.

### 3.3 Simulator (dengan lifecycle port)
```
Simulator: id, name, port, status (RUNNING|STOPPED),
           signatureMode (STRICT|SIMULATED), base config, keys...
```

---

## 4. Mesin Eksekusi (Pipeline)

Alur satu request API simulasi (contoh `POST /openapi/v1.0/transfer-intrabank`):

```
Request
  0. ROUTING          → tentukan Simulator (dari PORT) + Endpoint
  1. AUTH/SIGNATURE    → STRICT: verifikasi Bearer + HMAC-SHA512
                          gagal → 401 (mis. 4017300 Invalid Signature)
                         SIMULATED: lewati / paksa hasil dari scenario
  2. VALIDATION        → header wajib SNAP (X-TIMESTAMP, X-PARTNER-ID,
                          X-EXTERNAL-ID, CHANNEL-ID) + schema body
  3. IDEMPOTENCY       → cari (simulator, partner, X-EXTERNAL-ID);
                          KETEMU → balas respons TERSIMPAN, STOP
  4. CONTEXT BUILD     → gabung request + state (muat Account) + variabel
  5. SCENARIO PICK     → scenario aktif endpoint
  6. RULE ENGINE       → Rule[] berurutan, FIRST-MATCH
       └─ [FAULT titik A] → tolak di depan (saldo UTUH)
  7. ACTIONS (ATOMIK)  → saldo -=, buat txn, simpan idempotency
                          [dibungkus 1 DB transaction — wajib]
       └─ [FAULT titik B] → commit-then-drop (saldo BERUBAH, respons hilang)
  8. RESPONSE GEN      → render template (responseCode SNAP, mis. 2001800)
       └─ [FAULT titik C] → respons rusak / lambat
  9. LOG + EMIT EVENT  → RequestLog + siarkan ke Event Bus → SSE → dashboard
 10. WEBHOOK (async)   → Persistent outbox → worker kirim + retry
```

### 4.1 Atomicity (wajib)
Langkah 7 (debit saldo + buat transaksi + simpan idempotency) **harus atomik** —
dibungkus satu DB transaction. Karena ini uang, tak ada opsi lain.

### 4.2 Fault Injection — 3 titik
- **Titik A — tolak di depan:** saldo utuh (503/timeout/connection reset).
  Bentuk paling autentik: **matikan port simulator** → connection refused.
- **Titik B — commit-then-drop:** saldo berubah, respons ke klien hilang.
  Menguji idempotensi & rekonsiliasi klien — skenario produksi termahal.
- **Titik C — respons rusak:** JSON malformed, body terpotong, delay lambat.

### 4.3 Idempotensi (SNAP)
`X-EXTERNAL-ID` = kunci idempotensi. Request ulang dengan ID sama → balas
respons tersimpan, **tidak diproses ulang** (saldo tak dipotong dua kali).

---

## 5. Signature / Auth (SNAP BI)

Dua mode:
- **STRICT** — verifikasi betulan:
  - Access token B2B: signature **RSA-SHA256** atas `clientKey|timestamp`.
  - Transaksional: **HMAC-SHA512** atas
    `METHOD:path:accessToken:sha256(body):timestamp` dengan client secret.
  - Nilai jual: bisa berkata "signature kamu salah" seperti sandbox bank asli.
- **SIMULATED** — abaikan/paksa hasil (fokus alur bisnis).

`Partner` menyimpan **public key** (verifikasi RSA) & **client secret** (HMAC).

**responseCode SNAP** = 7 digit `[HTTP status 3][service code 2][case code 2]`,
mis. `2001800` sukses, `4031800` insufficient/limit, `4017300` invalid signature.

---

## 6. Kontrak API

Dua permukaan API **terpisah total**:

### 6.1 API Simulasi (dinamis, per-port)
- Berpura-pura jadi bank. Path meniru SNAP: `/openapi/v1.0/...`.
- **Routing lewat PORT** — satu simulator = satu port yang bisa start/stop.
  Path SNAP tetap **asli** (tanpa prefix). Partner dikenali dari header
  `X-PARTNER-ID` (bukan URL).
- Dilayani oleh mesin eksekusi (bagian 4).

```
localhost:9001/openapi/v1.0/transfer-intrabank   → Bank Simulasi A
localhost:9002/openapi/v1.0/transfer-intrabank   → Bank Simulasi B
```

### 6.2 API Admin (statis)
Port tetap `:8080`. **Tanpa auth dulu** (lokal). Dipakai dashboard.
```
/api/admin/v1/
  simulators                          GET, POST
  simulators/{id}                     GET, PUT, DELETE
  simulators/{id}/start | /stop       POST         ← buka/tutup port
  simulators/{id}/partners            kelola partner + kunci
  simulators/{id}/endpoints           kelola endpoint
  endpoints/{ep}/scenarios            kelola scenario
  endpoints/{ep}/active-scenario  PUT              ← sakelar utama testing
  scenarios/{sc}/rules                kelola rule (hybrid)
  simulators/{id}/accounts            seed & lihat saldo
  simulators/{id}/transactions        telusuri transaksi
  simulators/{id}/logs  +  /stream (SSE)           ← live view
  simulators/{id}/webhooks/outbox     pantau callback
```

### 6.3 Port/Server Manager (komponen baru)
Mengelola buka-tutup port secara **runtime** (embedded server instance terpisah
per port, semua mengarah ke satu mesin eksekusi). Menangani:
- Penetapan port **manual + saran otomatis** + deteksi konflik.
- Start/stop; setelah restart aplikasi → semua simulator mulai **STOPPED**
  (dinyalakan manual).

---

## 7. Dashboard (Angular)

```
┌─────────────────────────────────────────────────────────────┐
│ Simulator: "Bank Simulasi BCA"  :9001 ●   [Scenario: Normal ▾]│ ← sakelar
├──────────┬──────────────────────────────────────────────────┤
│ Endpoints│  Panel utama:                                     │
│ Scenarios│   • Rule editor (builder visual + tab ekspresi)   │
│ Rules    │   • Response editor (Monaco)                      │
│ Partners │   • Account/Transaction browser (AG Grid)         │
│ Accounts ├──────────────────────────────────────────────────┤
│ Trans.   │  LIVE VIEW (SSE) — request mengalir real-time:    │
│ Webhooks │  10:00:01 POST /transfer 200 2001800 ✔ 45ms       │
│ Logs ●   │  10:00:03 POST /transfer 403 4031800 ✖ 12ms       │
│ Settings │                                                    │
└──────────┴──────────────────────────────────────────────────┘
```
Elemen kunci: **sakelar Scenario** (ganti perilaku 1 klik tanpa restart) &
**Live View SSE**.

### Template UI (keputusan)
Dashboard memakai **full Angular Material** dengan template **ng-matero**
(https://github.com/ng-matero/ng-matero) sebagai kerangka admin (layout, tema,
navigasi, komponen). Shell Angular dasar (Fase 0) akan diganti/di-adopsi ke
ng-matero. Integrasi ke Admin API: list & start/stop simulator, sakelar scenario,
Live View SSE, browser Account/Transaction (AG Grid), editor rule/response (Monaco).

---

## 8. Model Konfigurasi Rule (jantung engine)

```
Scenario
  ├─ rules: Rule[]        ← berurutan, FIRST-MATCH (seperti switch)
  └─ fallback: Response   ← bila tak ada rule cocok

Rule
  ├─ when:  Condition     ← bagian IF (hybrid)
  └─ then:  Outcome       ← actions + response + fault + webhook
```

### 8.1 Condition — hybrid (AST + escape-hatch)
Kondisi disimpan sebagai **pohon terstruktur (AST)** agar bisa dirender jadi
builder visual:
```jsonc
// terstruktur (builder visual)
{ "type": "compare",
  "left":  { "type": "state", "query": "account(request.body.from).balance" },
  "op": "<",
  "right": { "type": "field", "path": "request.body.amount" } }

// grup majemuk
{ "type": "group", "op": "AND", "children": [ {…}, {…} ] }

// escape-hatch ekspresi (mode lanjutan; tak diedit di builder)
{ "type": "expression", "raw": "account(from).balance < amount && amount > 25000000" }
```
**Strategi hybrid = AST + escape-hatch:** builder visual jalur utama (±90%
kasus), node ekspresi menambal kasus sulit. **Tanpa** transpile dua arah.

### 8.2 Outcome — bagian THEN
```jsonc
"then": {
  "actions": [
    { "type": "debit", "target": "account(from)", "amount": "{{amount}}" },
    { "type": "create_transaction", "fields": { "status": "PENDING" } }
  ],
  "response": { "httpStatus": 200, "responseCode": "2001800",
                "body": { "referenceNo": "{{txn.ref}}", "status": "PENDING" } },
  "fault":   null,                       // atau { "point": "B", "type": "timeout" }
  "webhook": { "after": "2s", "body": { "status": "SUCCESS" } }
}
```

### 8.3 Evaluator ekspresi
- **Mulai:** Apache **JEXL** (ringan, mudah dibatasi, aman-dari-kecelakaan).
- **Nanti:** migrasi ke **evaluator mini khusus finansial** (hanya operator &
  fungsi yang diizinkan: `>`, `<`, `==`, `&&`, `account()`, `txn where...`).
- Catatan keamanan: penulis rule = pemilik (tepercaya, tool lokal). Cukup aman
  dari kecelakaan; tak perlu benteng anti-penyerang.

---

## 9. Webhook

- **Persistent outbox**: webhook tertunda disimpan ke DB; worker mengambil,
  mengirim, dan **retry** bila gagal. Tahan restart.
- Dukungan: delayed callback, retry strategy, HMAC/JWT signature, custom header.

---

## 10. Live View / Monitoring

- Request handling = **Spring MVC** (bukan WebFlux) — transaksi uang aman &
  sederhana.
- Live view **tidak** butuh reaktif. Tiap request setelah diproses meng-emit
  `RequestEvent` → **Event Bus in-app** → **SSE** → dashboard.

---

## 11. Roadmap / Fase

### Fase 0 — Fondasi
Skeleton Spring Boot MVC + PostgreSQL + Liquibase + Angular shell. Model data
inti + migrasi. Admin API + **Port/Server Manager** (buka-tutup port).

### Fase 1 — MVP Vertical Slice ⭐
Simulator CRUD + start/stop port. Endpoint + Scenario + Rule (terstruktur).
Pipeline eksekusi lengkap (routing→idempotensi→rule→actions atomik→response).
Account/Transaction + seed. **Access Token (simulated) → Transfer Intrabank**
dengan 3 scenario: Normal / Saldo Kurang / Limit. Live view SSE + RequestLog.
→ *Simulator sudah benar-benar bisa dipakai testing.*

### Fase 2 — Realisme
Signature STRICT (RSA token + HMAC transaksi). Fault injection 3 titik +
connection-refused (stop port). Webhook outbox + retry (callback status transfer).

### Fase 3 — UX Konfigurasi
Rule builder visual (AST) + mode ekspresi JEXL. Response editor (Monaco).
Browser Account/Transaction (AG Grid). Sakelar scenario matang.

### Fase 4 — Perluasan
Packaging Blueprint SNAP. Virtual Account. QRIS MPM (dynamic/static).
OpenAPI import. Recorder/Replay, Monitoring, Audit trail.

---

## 12. Perubahan terhadap README

| Topik | README | Keputusan desain | Alasan |
|---|---|---|---|
| Request handling | Spring WebFlux / Reactor Netty | **Spring MVC** | Transaksi uang stateful lebih aman & sederhana; bottleneck bukan koneksi idle |
| Live view | (implisit reaktif) | **SSE + Event Bus** (di atas MVC) | Live view tak butuh reaktif |
| Domain | "enterprise apa saja" | **Fokus finansial** (SNAP BI + QRIS) | Fokus & standar global |
| Bentuk produk | platform | **tool self-hosted per-port** | Isolasi natural + realistis (connection-refused) |

---

## 13. Papan Keputusan (ringkas)

| Aspek | Keputusan |
|---|---|
| Produk | Tool developer self-hosted, domain finansial (SNAP BI + QRIS) |
| Mesin | Generik/modular, configuration over code |
| Arsitektur | **Hexagonal (Ports & Adapters)**; core-engine bebas framework; MVC = adapter inbound |
| Pola engine | Chain/Pipeline, Strategy, Interpreter, State, Outbox, Repository |
| Request handling | Spring MVC |
| Live view | SSE via Event Bus |
| State storage | Hybrid (uang = tabel kaku, sisanya = JSON) |
| Tenancy | Isolasi penuh per-partner; Workspace opsional |
| Routing | Port per simulator (start/stop); restart → STOPPED |
| Port assign | Manual + saran otomatis + deteksi konflik |
| Admin/Dashboard | Port :8080, tanpa auth dulu |
| Signature | Dua mode (STRICT RSA/HMAC + SIMULATED) |
| Fault | 3 titik (A/B/C) + connection-refused |
| Webhook | Persistent outbox + retry |
| Rule model | Scenario→Rule (first-match)+fallback; Condition AST + escape-hatch |
| Evaluator | JEXL dulu → evaluator mini nanti |
| Slice pertama | SNAP: Access Token B2B → Transfer Intrabank |
| Blueprint | Preset akurat (SNAP BI), **dapat di-override penuh** per-simulator |

---

## 14. Status Implementasi (yang sudah dibangun)

> Sumber kebenaran progres. Diperbarui seiring implementasi (jangan hanya di memory).

### Fase 0 — Fondasi ✅
Gradle multi-module Hexagonal (`core-engine`, `adapter-persistence`, `adapter-web`,
`adapter-signature`, `adapter-webhook`, `app`), JDK 25 (Gradle toolchain), Spring
Boot 4.0.7 MVC, PostgreSQL 17 + Liquibase (11 tabel inti), frontend terpisah.

### Fase 1 — MVP Transfer Intrabank ✅
Pipeline `DefaultBehaviorEngine` (partner→idempotensi→scenario→rule first-match→
actions atomik→response→event). Per-port server (**JDK HttpServer**, start/stop =
connection-refused). Admin API (list/start/stop/active-scenario). Live View SSE +
RequestLog. Seed demo (sim :9001, partner PARTNER001, rekening, 3 scenario).
Terverifikasi: transfer sukses/saldo-kurang/limit, idempotensi, partner-auth.

### Fase 2 — Signature STRICT + Fault + Webhook + Access Token ✅
- **Signature STRICT**: verifikasi HMAC-SHA512 transaksional (mode per-simulator) →
  `4017300` bila salah; terima signature valid.
- **Fault injection** (via scenario): Bank Down (503 saldo utuh), Timeout (delay),
  **Commit-Then-Drop** (debit lalu drop koneksi → retry idempoten), Malformed.
  Directive fisik diterapkan adapter web pasca-commit.
- **Webhook outbox** (`webhook_outbox` table): `OutboxWebhookSender` enqueue dalam
  transaksi request; `WebhookWorker` @Scheduled kirim + retry backoff linear
  (PENDING→SENT/FAILED). Scenario "Async Callback" (URL dari header X-CALLBACK-URL,
  delay 2s). Test-sink `POST /api/admin/v1/webhook-sink`.
- **Access Token B2B**: `POST /v1.0/access-token/b2b` (routing khusus di server
  per-port) → `AccessTokenService` (STRICT verifikasi RSA asimetris) → `2007300` +
  accessToken tersimpan di `access_tokens`.

#### Skema `webhook_outbox`
`id, simulator_id, url, headers jsonb, body, status(PENDING|SENT|FAILED), attempts,
max_attempts, next_attempt_at, last_error, created_at, sent_at`.

### Fase 3 (parsial) — Editor request/response dari dashboard ✅
Prinsip **"request & response dapat di-modify dari dashboard"** (§2 override, §8)
kini **diimplementasikan** untuk Transfer Intrabank:
- Kolom `scenarios.definition JSONB` — NULL = pakai preset blueprint; berisi JSON =
  definisi custom.
- `ScenarioCodec` (adapter-persistence): round-trip **JSON ↔ Scenario** (condition
  AST, operand, action, response, fault, webhook) — presisi & dapat diedit.
- Engine (`JpaConfigRepository.findActiveScenario`): pakai definisi custom bila ada,
  selain itu serialisasi preset blueprint sebagai titik awal.
- **Admin API**: `GET .../scenarios` (daftar), `GET|PUT|DELETE
  .../scenarios/{name}/definition` (ambil efektif / simpan override / reset).
- **Dashboard**: tombol "Edit request/response" per simulator → memuat definisi JSON
  scenario aktif (preset sebagai awal), edit di textarea, Simpan (divalidasi) / Reset.
- Terverifikasi: override response → transfer memakai body baru (mis. field custom &
  pesan diubah); Reset → kembali ke preset.

Format JSON definisi (ringkas):
```jsonc
{ "rules": [ { "name": "...",
    "when": { "kind":"compare", "left":{"kind":"accountBalance","field":"sourceAccountNo"},
              "op":"LT", "right":{"kind":"field","path":"amount"} },
    "then": { "actions":[], "response": { "httpStatus":400, "responseCode":"4001714",
              "responseMessage":"Insufficient Funds", "body": { … } } } } ],
  "fallback": { "actions":[ {"kind":"debit","accountNoField":"sourceAccountNo","amountField":"amount"},
                            {"kind":"credit",…}, {"kind":"createTransaction","status":"SUCCESS"} ],
                "response": { "httpStatus":200, "responseCode":"2001800", "body": { … } },
                "fault": null, "webhook": null } }
```

### Fase 3 (parsial) — Simulator (profil bank) CRUD + Clone ✅
Menjawab kebutuhan **"beda bank = beda profil, kadang berbeda meskipun sedikit"**.
`Simulator` memang sudah menjadi unit "profil bank" (port sendiri, config sendiri,
state terisolasi penuh) sejak Fase 0 — yang tadinya belum ada: cara **membuatnya**
dari dashboard.

- `SimulatorAdmin.create/cloneSimulator/delete` (port baru + `provisionBaseline`):
  provisioning otomatis partner (PARTNER001), 2 akun baseline, endpoint transfer-
  intrabank, dan **8 scenario** siap pakai.
- **Clone**: menyalin kunci partner, **definisi override scenario custom** (hasil
  editor request/response), dan akun sumber sebagai starting state — lalu berjalan
  independen (state terpisah sepenuhnya setelah itu).
- **Delete**: menutup port lalu menghapus simulator; FK `ON DELETE CASCADE` membawa
  serta semua config & state terkait.
- Admin API: `POST /simulators` (create), `POST /simulators/{id}/clone`,
  `DELETE /simulators/{id}` — validasi port bentrok → `409`.
- Dashboard: tombol **"Tambah Profil Bank"** (dialog form: nama, port, signature
  mode) + menu per-kartu **"Duplikat profil ini" / "Hapus simulator"**.
- **Bug ditemukan & diperbaiki** saat verifikasi: status `RUNNING` di DB bisa
  nyangkut dari sesi sebelumnya padahal port sudah tertutup (Port Manager mulai
  kosong di memori tiap boot) — melanggar §6.3 ("restart → semua STOPPED").
  Ditambahkan `ResetStatusOnBoot` (CommandLineRunner @Order(10)) yang memaksa semua
  simulator ke `STOPPED` saat aplikasi start, sebelum seeding.
- Terverifikasi end-to-end: 2–3 profil bank berjalan **simultan** di port berbeda
  dengan saldo & transaksi **terisolasi total**; clone membawa override tapi state
  independen sejak saat clone; delete menutup port & membersihkan data.

### Fase 4 (parsial) — Virtual Account ✅ (2026-07-14)
Endpoint SNAP VA (Lampiran A2) — fokus "selesaikan sisi bank dulu" (QRIS menyusul).
Berbeda dari Transfer Intrabank: **bukan** alur scenario/rule (tak butuh kondisi
bisnis kompleks per-request), melainkan CRUD stateful sederhana + aksi trigger
notifikasi dari dashboard.

- **Domain** (core-engine): `VirtualAccount`, `VirtualAccountStatus` (ACTIVE/PAID/
  EXPIRED). Port `VirtualAccountRepository`.
- **Persistence**: `VirtualAccountRepositoryJdbc` — disimpan di tabel generik
  `entities` (type='virtual_account', JSONB), sesuai strategi hybrid storage §3.2
  (bukan pembawa uang langsung, tak perlu tabel kaku).
- **Endpoint** (`VirtualAccountService`, adapter-web, pola sama dengan
  `AccessTokenService`): `POST /v1.0/transfer-va/create-va` (`2002700`),
  `POST /v1.0/transfer-va/status` (`2002600`), `DELETE /v1.0/transfer-va/delete-va`
  (`2002500`). Header & signature simetris (HMAC-SHA512) **reuse pola transfer-
  intrabank** — STRICT terverifikasi bekerja sama persis.
- **responseCode kegagalan** — diverifikasi via web (dokumentasi Faspay) bahwa SNAP
  memakai kode **generik** (service code "00") untuk error lintas-endpoint, BUKAN
  per-service seperti dugaan awal: `4000002` (field wajib), `4010000`
  (unauthorized), `4040012` (VA tidak ditemukan — "Invalid Bill/Virtual Account"),
  `4090001` (duplikat).
- **Payment Notification** (A2.3): **bukan** endpoint masuk dari partner, melainkan
  webhook yang KAMI (simulator) kirim ke `X-CALLBACK-URL` yang dicatat saat
  create-va. Dipicu manual dari dashboard ("aksi VA dibayar", bukan otomatis) via
  Admin API `POST .../virtual-accounts/{vaNo}/pay` → `VirtualAccountService
  .markPaid()` → ubah status PAID + `webhookSender.schedule(...)` (pakai outbox
  Fase 2 yang sama) dengan body persis field A2.3 (`paymentRequestId`,
  `paidAmount`, `latestTransactionStatus:"00"`, dll).
- **Admin API**: `GET .../virtual-accounts` (list lintas partner untuk monitoring),
  `POST .../virtual-accounts/{vaNo}/pay`.
- **Dashboard**: panel "Virtual Account" per simulator (toggle) — tabel VA (no,
  nama, jumlah, status) + tombol "Tandai Dibayar" (nonaktif bila sudah PAID atau
  tak ada callback URL tersimpan) + contoh curl cara membuat VA uji.
- Terverifikasi end-to-end HTTP: create (+ tolak field hilang, tolak duplikat),
  inquiry (+ 404 saat tak ada), status ACTIVE→PAID setelah mark-paid, webhook
  Payment Notification diterima sink dengan field persis spec, delete (+ inquiry
  setelah delete → 404), signature STRICT (tanpa/valid/salah) — semua sesuai.
- **Belum**: Update VA (endpoint di tabel A2.1 tapi tanpa service/case code resmi
  di sumber — sengaja dilewati dulu), expiry otomatis (VA tak auto-EXPIRED saat
  `expiredDate` lewat, murni field pasif untuk saat ini).

### Fase 4 (lanjutan) — Penyelesaian sisi bank ✅ (2026-07-14)
Menutup dua celah nyata sebelum lanjut ke QRIS: (1) STRICT baru memverifikasi
signature, belum memvalidasi bahwa token benar-benar diterbitkan & belum
kedaluwarsa; (2) tak ada cara menambah partner/rekening dari dashboard — hanya via
baseline provisioning saat simulator dibuat.

**Validasi expiry token Bearer:**
- `AccessTokenStore.isValid(simId, partnerId, token)` — cek token ada, milik
  partner ini, `expires_at > now()`. Impl `AccessTokenStoreJdbc`.
- Alasan pentingnya: **HMAC signature saja tidak cukup** — signature hanya
  menjamin integritas data (klien konsisten memakai token+secret yang sama), BUKAN
  bahwa token itu sah. Token acak yang tak pernah diterbitkan tetap bisa
  menghasilkan HMAC yang "valid" secara matematis.
- Diwire ke `DefaultBehaviorEngine` (transfer-intrabank, constructor 6-arg baru
  dengan `AccessTokenStore`) dan `VirtualAccountService` (VA) — keduanya kini cek
  token dulu (`4017301`/`4010001`) baru signature (`4017300`/`4010000`).
- **Terverifikasi**: token acak+signature "valid" → ditolak. Alur asli lengkap
  (RSA keypair asli → `access-token/b2b` → HMAC pakai token asli → transfer)
  → sukses, saldo terpotong benar.

**Manajemen Partner & Akun dari dashboard:**
- Port `AccountAdmin` (core) + `AccountAdminJdbc` (JdbcClient): CRUD partner
  (partnerId, publicKeyPem RSA, clientSecret HMAC) & account (accountNo,
  holderName, balance — dijaga ≥0 oleh constraint DB), termasuk **ubah saldo**
  (top-up untuk keperluan uji).
- Admin API: `GET/POST/DELETE .../simulators/{id}/partners[/{rowId}]`,
  `GET/POST/DELETE .../accounts[/{id}]`, `PUT .../accounts/{id}/balance`.
- Dashboard: panel "Partner & Rekening" (toggle) — daftar partner (badge HMAC/RSA)
  + form tambah; daftar rekening (saldo *inline editable*) + form tambah.
- **Terverifikasi end-to-end**: partner baru + RSA public key dibuat via Admin API
  → rekening baru dibuat & di-top-up via Admin API → dipakai alur token+transfer
  ASLI (bukan seed) sampai sukses dengan saldo terpotong tepat → partner & akun
  dihapus bersih. Dashboard (proxy `:4200`) menampilkan data yang sama.

### Fase 4 (lanjutan) — QRIS MPM lengkap: static/dynamic + Refund ✅ (2026-07-14)
Berbeda dari VA (logika tetap) — Generate QR dievaluasi lewat **Scenario/Rule yang
sama persis dengan Transfer Intrabank**, sehingga responsnya **dapat di-custom dari
dashboard** (bukan cuma logika tetap), memenuhi permintaan eksplisit "sesuai SNAP
dan bisa di-custom juga".

**Generalisasi infrastruktur (prasyarat)**: `ScenarioConfigPort`/`ScenarioConfigJpa`/
`SimulatorAdmin.setActiveScenario` diparameterisasi dengan `product` (method+path,
lihat `ProductEndpoints`) — sebelumnya hardcoded khusus transfer-intrabank. QRIS
kini pakai infrastruktur editor yang identik (`GET/PUT/DELETE
.../scenarios/{name}/definition?product=qris`). Transfer-intrabank diverifikasi
**tidak regresi** setelah generalisasi (8 scenario tetap utuh, tes lulus).

**EMV QR asli** (`EmvQrBuilder`, core-engine, pure Java): TLV (Tag-Length-Value)
+ **CRC16-CCITT-FALSE sungguhan** (bukan string tiruan) — diverifikasi lewat
vektor uji standar independen ("123456789"→`0x29B1`). Field 54 (amount) hanya
disertakan untuk QR **dynamic** (Point of Initiation "12"); QR **static** ("11")
tidak menyertakannya — nominal diisi saat bayar.

**Domain & persistence**: `QrisTransaction` (+`QrisType` STATIC/DYNAMIC,
`QrisStatus` ACTIVE/PAID/REFUNDED/EXPIRED). `QrisRepositoryJdbc` → tabel generik
`entities` (type='qris'), sesuai hybrid storage.

**Endpoint** (`QrisService`, adapter-web):
- `POST /v1.0/qr/qr-mpm-generate` (`2004700`) — evaluasi scenario aktif (Normal/
  Merchant Diblokir/Service Down), `qrContent`+`referenceNo` computed var seperti
  `referenceNo` di transfer. Auth sama persis transfer (X-PARTNER-ID + STRICT
  HMAC+expiry token).
- `POST /v1.0/qr/qr-mpm-query` (`2005100`) — status ACTIVE→`03` pending,
  PAID→`00` success, REFUNDED→`04`.
- `POST /v1.0/qr/qr-mpm-refund` (`2007800`, **terverifikasi via DOKU+Duitku**) —
  hanya **full refund** didukung (simplifikasi terdokumentasi); validasi status
  PAID & amount cocok.
- **Payment Notify** (A3.4): sama pola VA — webhook yang KAMI kirim, dipicu aksi
  dashboard "Tandai Dibayar" (`POST .../qris/{referenceNo}/pay`); static WAJIB
  isi nominal saat bayar, dynamic pakai nominal tertanam.
- **responseCode kegagalan generik** (service "00", terverifikasi Faspay):
  `4040001` Transaction Not Found, `4040013` Invalid Amount.

**Dashboard — HALAMAN TERPISAH** (`/qris`, bukan panel di kartu Simulator):
pilih profil (dropdown) + **Tambah/Duplikat/Hapus/Nyala-Mati profil langsung dari
halaman ini** (reuse `SimulatorFormDialog`), dropdown+editor scenario QRIS, daftar
QR (status, Tandai Dibayar dengan input nominal utk static, catatan refund via
curl), 3 contoh curl (generate dynamic/static, refund) dengan **tombol Salin**.
Halaman Simulators dilebarkan (`max-width` 1200→1760px, kartu minmax 360→460px)
menyusul QRIS dipindah keluar.

**Bug UX ditemukan & diperbaiki** (dari testing langsung, kedua halaman): pesan
error editor (`editorError`) sebelumnya dirender **di dalam** blok
`@if(editing)`, sehingga kegagalan (mis. backend tak terjangkau) membuat klik
"Edit request/response" terasa tidak bereaksi sama sekali (panel tak terbuka,
error pun tak tampak). Diperbaiki: error dipindah agar selalu terlihat +
indikator loading saat memuat + pesan error lebih spesifik (beda pesan utk
`status:0` vs error HTTP lain). Juga ditambah **contoh request** di dalam editor
(field apa yang dipakai kondisi) untuk kedua halaman.

**Terverifikasi end-to-end HTTP lengkap**: generate dynamic (qrContent EMV benar,
field 54 ada), generate static (field 54 absen), validasi amount≤0 ditolak,
Merchant Diblokir & Service Down, mark-paid dynamic & static + **webhook Payment
Notify diterima sink** dengan field persis A3.4, query status 3 keadaan, refund
sukses + double-refund ditolak + amount-mismatch ditolak, **override response
kustom dari dashboard** (custom field & pesan langsung dipakai, qrContent tetap
terhitung benar, reset kembali preset), STRICT signature+expiry-token (token
tak-pernah-diterbitkan ditolak walau signature "valid" matematis).

### QRIS — penyelesaian akhir ✅ (2026-07-14, sesi lanjutan "tolong selesaikan qris")
Menutup 3 celah yang sebelumnya tercatat "belum dibangun":

- **Cancel/Expire** (`POST /v1.0/qr/qr-expire`, `responseCode` sukses `2005000`
  — diverifikasi via web, sourced dari implementasi bank nyata). Hanya QR
  **ACTIVE** (belum dibayar) yang bisa dikedaluwarsakan → status `EXPIRED`.
  Query setelahnya balas `latestTransactionStatus:"07"`.
- **Partial refund**: `QrisTransaction.applyRefund()` kini **akumulatif**
  (`refundedAmount` bertambah tiap refund) — status baru berubah jadi
  `REFUNDED` saat kumulatif mencapai `paidAmount` (lunas); sebelum itu tetap
  `PAID`. Validasi: `refundAmount` harus ≤ sisa (`refundableAmount()`).
- **Sinkronisasi scenario aktif di dashboard**: sebelumnya dropdown scenario
  selalu berasumsi `'Normal'` di client tanpa mengecek server — kalau
  scenario diganti di sesi/tab lain, dashboard menampilkan info salah. Port
  baru `ScenarioConfigPort.activeScenarioName()` + Admin API
  `GET .../scenarios/active?product=`; dashboard QRIS fetch nilai asli saat
  memilih simulator.

### QRIS — alignment SNAP MPM spec ✅ (2026-07-14)
Diselaraskan dengan spesifikasi ASPI SNAP BI MPM (portal developer ASPI):

- **Cancel Payment** (`POST /v1.0/qr/qr-mpm-cancel`, service 77, sukses `2007700`)
  — sebelumnya `qr-expire` dengan path & service code tidak sesuai spec.
  Request: `originalReferenceNo`, `originalPartnerReferenceNo`, `originalExternalId`,
  `merchantId` (M), `reason` (M). Response: `cancelTime`, `transactionDate`.
  Backward compat: `qris-expire` tetap berfungsi via alias di routing.
- **Generate response** diperkaya field MPM spec: `qrUrl`, `redirectUrl`,
  `merchantName`, `storeId`, `terminalId` di top-level response + `additionalInfo`.
- **Query response** diperkaya: `originalExternalId`, `serviceCode`, `paidTime`,
  `feeAmount`, `terminalId`.
- **Refund** tambah field spec: `originalExternalId`, `reason` di request/response;
  `refundNo` mandatory, `refundTime`.
- **2 endpoint baru**:
  - `POST /v1.0/qr/qr-mpm-decode` (service 48, `2004800`) — balas dummy
    `merchantInfos` (merchantPAN + acquirerName), `transactionAmount`,
    `merchantCategory`, `merchantLocation`.
  - `POST /v1.0/qr/qr-mpm-payment` (service 50, `2005000`) — Host-to-Host
    payment, validasi `partnerReferenceNo`+`amount`, return `referenceNo`,
    `transactionDate`, `amount`, `feeAmount`, `verificationId`.
- **Domain**: `QrisTransaction.paidAt` (Instant) untuk `paidTime` di query.
- **Liquibase** migrasi `005-qris-cancel.sql` — rename old endpoints.
- **Frontend**: 6 contoh curl (generate dynamic/static, refund, cancel, decode,
  payment); QRIS page tetap halaman terpisah `/qris`.

**Bug keamanan/integritas ditemukan & diperbaiki saat verifikasi**: aksi
dashboard "Tandai Dibayar" (`markPaid`) **tidak mengecek status QR** sebelum
mengeksekusi — terbukti nyata: QR yang sudah `EXPIRED` bisa "dibayar" lagi
(status balik ke `PAID`, `query` balas sukses `00`), sebuah kondisi tak
konsisten. UI dashboard sudah menyembunyikan tombol untuk status non-ACTIVE,
tapi endpoint backend sendiri tidak menegakkan invariant itu (rawan lewat
panggilan API langsung). Diperbaiki: `markPaid` kini menolak bila status
bukan `ACTIVE`, sama seperti `adminExpire`.

**Terverifikasi HTTP end-to-end**: sinkron scenario aktif (ganti di server,
dashboard baca nilai benar), expire (+tolak expire ganda, +tolak bayar QR
expired setelah fix), partial refund 2x hingga lunas (+tolak refund melebihi
sisa), regresi nol (transfer & VA tetap sukses setelah semua perubahan).

**Belum dibangun (berikutnya)**: builder visual rule (kini editor JSON teks/
"advanced mode"), Recorder/Replay UI, editor Monaco (ganti textarea), Workspace
pengelompokan opsional, Live View SSE Simulators tanpa error handler
(ditemukan, ditunda).

---

### URL/path endpoint yang dapat di-custom per-simulator ✅ (2026-07-14)
Menutup celah yang tercatat di atas: bank berbeda kerap memakai path/versi
berbeda dari standar ASPI (mis. BRI: `/intrabank/snap/v2.0/transfer-intrabank`
alih-alih `/v1.0/transfer-intrabank`). Sebelumnya routing 100% hardcoded
(`path.endsWith(...)`) — mengubah path di UI tidak akan pernah memengaruhi
server sungguhan. Sekarang **data-driven**, path disimpan per-simulator di DB.

- **`SnapOperations`** (core-engine): katalog murni-data 9 operasi SNAP (key,
  method, defaultPath ASPI, label) — satu sumber kebenaran untuk path default.
- **`EndpointRegistry`** (port) + **`EndpointRegistryJdbc`** (adapter-persistence):
  `resolveOperation(simulatorId, method, path)` mencari baris `endpoints` yang
  cocok; `updatePath`/`resetPath` untuk CRUD dari dashboard. Kolom baru
  `endpoints.operation` (migrasi `004-endpoint-operation.sql`, unique index
  parsial `(simulator_id, operation) WHERE operation IS NOT NULL`).
- **Self-healing/lazy-provisioning**: bila `resolveOperation` tak menemukan
  baris cocok tapi path yang diminta match salah satu `defaultPath` di
  `SnapOperations`, baris langsung di-provision otomatis — menjaga kompatibilitas
  mundur untuk simulator yang dibuat sebelum fitur ini ada (tanpa perlu migrasi
  data manual per simulator).
- **`SimulatorServerManager`**: 8 pengecekan `path.endsWith(...)` diganti satu
  dispatch `switch` berbasis `operation` hasil `resolveOperation`. Path yang
  tak terdaftar kini benar-benar 404 (`4040400`) — sebelumnya silently jatuh
  ke transfer engine, sebuah bug laten yang baru ketahuan lewat perubahan ini.
- **Provisioning** (`JpaSimulatorAdmin.provisionBaseline`, `DemoSeeder`):
  simulator baru langsung mendapat 9 baris `endpoints` dengan `operation`
  terisi (bukan cuma 2 seperti sebelumnya).
- **Admin API** (`EndpointAdminController`): `GET/PUT/DELETE
  /api/admin/v1/simulators/{id}/endpoints[/​{operation}]` — list, update path,
  reset ke default ASPI.
- **Dashboard**: komponen reusable `EndpointUrlPanel` (standalone, Angular
  signal `input()`), diinstansiasi **independen** di dua halaman terpisah
  (bukan penggabungan Simulators+QRIS — tetap dua halaman, satu komponen
  dipakai ulang dengan filter `operations` berbeda): Simulators
  (`access-token`, `transfer`, `va-*`) & QRIS (`qris-*`). Tiap baris tampil
  path saat ini, tag "CUSTOM" bila beda dari default, tombol Simpan/Reset.

**2 bug ditemukan & diperbaiki saat verifikasi end-to-end:**

1. **QRIS custom path memutus resolusi scenario.** `QrisService.generate()`
   memanggil `config.findActiveScenario(simulatorId, METHOD, path)` tapi
   memakai konstanta hardcoded `QrisMpmBlueprint.PATH`, bukan parameter
   `path` yang sesungguhnya masuk. Akibatnya begitu path QRIS di-custom,
   lookup scenario tetap mencari path lama → `404 "No active scenario"`.
   Diperbaiki jadi memakai `path` asli. **Bug kedua di lapisan yang sama**
   ditemukan sesudahnya: `JpaConfigRepository.findActiveScenario()` menentukan
   `product` ("qris" vs "transfer") dengan membandingkan `path` terhadap
   `QrisMpmBlueprint.PATH` juga — perbandingan string yang sama-sama gagal
   untuk path custom. Diperbaiki: `product` sekarang diturunkan dari kolom
   `EndpointEntity.operation` (`"qris".startsWith` → produk "qris"), dengan
   fallback ke perbandingan path lama hanya bila `operation` belum terisi
   (data pra-migrasi yang belum tersentuh self-healing).
2. **Validasi & deteksi tabrakan path selalu balas 500 mentah, bukan 400/409
   rapi — bug sistemik, bukan cuma di kode baru.** `EndpointRegistryJdbc`
   (dan repository JDBC lain seperti `AccountAdminJdbc`) memakai anotasi
   `@Repository`, yang membuat Spring otomatis memasang
   `PersistenceExceptionTranslationInterceptor` (AOP) — `IllegalArgumentException`
   yang dilempar dari bean semacam itu **diterjemahkan otomatis** menjadi
   `InvalidDataAccessApiUsageException` **sebelum** sampai ke controller mana
   pun, sehingga `catch (IllegalArgumentException e)` lokal di 4 controller
   (`EndpointAdminController`, `AccountAdminController`, `ScenarioAdminController`,
   `SimulatorAdminController`) tidak pernah match. Dikonfirmasi bukan isolasi:
   fitur deteksi partner duplikat yang sudah lama ada (`AccountAdminController`)
   juga kena. **Perbaikan**: `ApiExceptionHandler` (`@RestControllerAdvice`
   global) menangkap kedua tipe exception sekaligus → 400 dengan pesan asli.
   Try/catch lokal yang kini redundan dihapus dari 3 controller; 2 titik di
   `AccountAdminController` (create partner/account) yang sengaja balas
   **409** (bukan 400, karena ini kasus konflik/duplikat) dipertahankan tapi
   diperluas menangkap `InvalidDataAccessApiUsageException` juga, supaya
   status 409-nya benar-benar tercapai.

**Terverifikasi HTTP end-to-end**: path custom transfer (gaya BRI) — path
lama 404, path baru sukses transfer, reset kembali ke default berfungsi;
validasi path tanpa `/` → 400 bersih; tabrakan path antar-operasi → 400
bersih; QRIS generate dengan path custom → sukses (bug #1 di atas, sebelum
fix 404); reset QRIS ke default → sukses lagi; partner duplikat → 409 bersih
(bukan 500, bug #2 di atas); regresi nol pada access-token & transfer path
default setelah semua perubahan.

---

## Lampiran A — Spec SNAP BI (preset alur pertama)

> Referensi untuk Blueprint **Access Token B2B + Transfer Intrabank**. Nilai
> preset; **dapat di-override** (lihat bag. 2 "Prinsip override").
> Sumber: portal ASPI/BI & implementasi bank (lihat Lampiran B).

### A.1 Access Token B2B (asymmetric signature)
```
POST /v1.0/access-token/b2b
Headers:
  X-CLIENT-KEY : <clientId partner>
  X-TIMESTAMP  : ISO-8601 (mis. 2026-07-13T10:00:00+07:00)
  X-SIGNATURE  : SHA256withRSA( stringToSign, privateKey partner )
Body: { "grantType": "client_credentials", "additionalInfo": {} }

stringToSign = <clientId> + "|" + <X-TIMESTAMP>

Response: { "accessToken": "...", "tokenType": "Bearer", "expiresIn": "900" }
```
- Mode STRICT verifikasi RSA pakai **public key** partner.
- Mode SIMULATED: terbitkan token tanpa verifikasi.

### A.2 Transfer Intrabank (symmetric signature)
```
POST /v1.0/transfer-intrabank          (path standar ASPI; bank bisa variasi)
Headers wajib:
  Authorization : Bearer <accessToken>
  X-TIMESTAMP   : ISO-8601
  X-PARTNER-ID  : alfanumerik ≤36
  X-EXTERNAL-ID : numerik ≤36        (kunci idempotensi)
  CHANNEL-ID    : alfanumerik ≤5
  X-SIGNATURE   : HMAC-SHA512( stringToSign, clientSecret )
  Content-Type  : application/json

stringToSign = HTTPMethod +":"+ RelativeUrl +":"+ accessToken +":"
             + Lowercase(HexSHA256(minify(body))) +":"+ X-TIMESTAMP
```

Request body:
```jsonc
{
  "partnerReferenceNo": "…",              // ≤64
  "amount": { "value": "16.2 decimal", "currency": "IDR" },
  "beneficiaryAccountNo": "…",            // numerik ≤34
  "sourceAccountNo": "…",                 // numerik ≤19
  "feeType": "OUR|BEN|SHA",
  "remark": "…",                          // ≤50
  "transactionDate": "ISO-8601",
  "additionalInfo": { }
}
```
Response body: `responseCode`, `responseMessage`, `referenceNo`,
`partnerReferenceNo`, `amount{value,currency}`, `beneficiaryAccountNo`,
`sourceAccountNo`, `transactionDate`, `additionalInfo`.

### A.3 Account Inquiry Internal (pendamping — cek nama rekening tujuan)
```
POST /v1.0/account-inquiry-internal
Body: { "beneficiaryAccountNo": "…", "additionalInfo": {} }
Response: + beneficiaryAccountName, beneficiaryAccountStatus,
            beneficiaryAccountType (D|S), currency
```

### A.4 Format responseCode = `[HTTP status 3][service code 2][case code 2]`
| Code | Arti |
|---|---|
| `2001800` | Transfer sukses |
| `2001500` | Account inquiry sukses |
| `4001701` | Invalid Field Format |
| `4001702` | Invalid Mandatory Field |
| `4001714` | Insufficient Funds |
| `4011700` | Unauthorized |

### A.5 Catatan variasi antar bank (alasan override wajib)
- Path & versi: ASPI `/v1.0/transfer-intrabank` vs BRI
  `/intrabank/snap/v2.0/transfer-intrabank`.
- Sebagian field & `additionalInfo` bervariasi antar penerbit.

---

## Lampiran A2 — Spec SNAP BI: Virtual Account (VA)

> Untuk Blueprint VA (Fase 4). Path standar ASPI `/v1.0/transfer-va/...`;
> service code & path bank bisa variasi. Semua header & signature = pola SNAP
> yang sama (lihat A.1/A.2).

### A2.1 Endpoint VA
| Fungsi | Method | Path (standar) | Service | responseCode sukses |
|---|---|---|---|---|
| Create VA | POST | `/v1.0/transfer-va/create-va` | 27 | `2002700` |
| Inquiry/Status VA | POST | `/v1.0/transfer-va/status` | 26 | `2002600` |
| Update VA | PUT | `/v1.0/transfer-va/update-va` | — | — |
| Delete VA | DELETE | `/v1.0/transfer-va/delete-va` | 25 | `2002500` |
| **Payment Notification** (bank→merchant callback) | POST | `{merchantUrl}/v1.0/transfer-va/payment` | 25 | merchant balas `2002500` |

### A2.2 Create VA — request
```jsonc
{
  "partnerServiceId": "  088899",       // 8 digit, left-pad spasi
  "customerNo": "12345678901234567890", // ≤20 digit
  "virtualAccountNo": "  08889912345678901234567890", // partnerServiceId+customerNo, ≤28
  "virtualAccountName": "Joko",
  "virtualAccountEmail": "…", "virtualAccountPhone": "62…",
  "totalAmount": { "value": "100000.00", "currency": "IDR" },
  "virtualAccountTrxType": "C",          // C=Closed, O=Open, V=Variable
  "expiredDate": "ISO-8601",
  "trxId": "…",
  "additionalInfo": { "channel": "…" }
}
```
Response: `responseCode`, `responseMessage`, `virtualAccountData{…}`.

### A2.3 Payment Notification (inti alur — bank memberi tahu merchant saat VA dibayar)
Bank memanggil endpoint **milik merchant**:
```jsonc
POST {merchantUrl}/v1.0/transfer-va/payment
{
  "partnerServiceId": "…", "customerNo": "…", "virtualAccountNo": "…",
  "paymentRequestId": "…",                       // ≤128
  "trxId": "…",
  "paidAmount": { "value": "100000.00", "currency": "IDR" },
  "trxDateTime": "ISO-8601",
  "referenceNo": "…",                            // ada pada VA statis
  "additionalInfo": {
    "paymentDate": "…", "channelCode": "…", "merchantId": "…",
    "latestTransactionStatus": "00",             // lihat A2.4
    "transactionStatusDesc": "success"
  }
}
```
Merchant membalas `responseCode: 2002500` + echo `virtualAccountData`.
> Di simulator, ini diimplementasikan sebagai **Webhook** (persistent outbox)
> yang dipicu saat VA "dibayar" (aksi dari dashboard / rule).

### A2.4 latestTransactionStatus (VA)
`00` success · `01` paying · `03` pending · `04` refunded · `05` canceled ·
`06` failed · `07` expired/not found.

---

## Lampiran A3 — Spec SNAP BI: QRIS MPM (dynamic/static)

> Untuk Blueprint QRIS (Fase 4). MPM = *Merchant Presented Mode* (merchant
> tunjukkan QR, customer scan).

### A3.1 Endpoint QRIS MPM
| Fungsi | Method | Path (standar) | Service | responseCode sukses |
|---|---|---|---|---|
| Generate QR | POST | `/v1.0/qr/qr-mpm-generate` | 47 | `2004700` |
| Decode QR | POST | `/v1.0/qr/qr-mpm-decode` | 48 | `2004800` |
| Payment H2H | POST | `/v1.0/qr/qr-mpm-payment` | 50 | `2005000` |
| Query status | POST | `/v1.0/qr/qr-mpm-query` | 51 | `2005100` |
| **Payment Notify** (acquirer→merchant callback) | POST | `{merchantUrl}/v1.0/qr/qr-mpm-notify` | 52 | `2005200` |
| Cancel Payment | POST | `/v1.0/qr/qr-mpm-cancel` | 77 | `2007700` |
| Refund Payment | POST | `/v1.0/qr/qr-mpm-refund` | 78 | `2007800` |

### A3.2 Generate QR — request/response
> **Terverifikasi ke ASPI SNAP Developer Site (MPM) + BRIAPI, 2026-07-14.**
> Aturan umum yang berlaku untuk SEMUA endpoint QRIS di bawah:
> - Nominal **selalu objek bersarang** `{ "value": "…", "currency": "…" }` —
>   `value` format `16.2` (2 desimal wajib untuk IDR, mis. `"25000.00"`),
>   `currency` ISO-4217. **Bukan** field flat seperti `amountValue`.
> - Timestamp = **ISO-8601 ber-offset** (25 char), mis. `2025-10-02T05:51:05+07:00`
>   — bukan `Instant.toString()` UTC (`…Z` + nanodetik).
> - Field opsional yang kosong dikirim sebagai **string kosong**, bukan `null`.

```jsonc
// request
{
  "partnerReferenceNo": "…",                     // ≤32
  "amount": { "value": "10000.00", "currency": "IDR" },
  "merchantId": "…", "terminalId": "…",
  "validityPeriod": "ISO-8601",                  // masa berlaku QR
  "additionalInfo": { }
}
// response — service 47
{
  "responseCode": "2004700", "responseMessage": "Successful",
  "referenceNo": "…",            // ≤64, ID transaksi dari acquirer
  "partnerReferenceNo": "…",     // ≤64, echo dari request
  "qrContent": "<EMV QR string>",// ≤512 — INTI generate; wajib ada saat sukses
  "qrUrl": "…",                  // ≤256, URL unduh gambar QR (opsional)
  "redirectUrl": "…",            // ≤512 (opsional)
  "merchantName": "…",           // ≤25
  "storeId": "…",                // ≤64
  "terminalId": "…",             // ≤16
  "additionalInfo": { "merchantId": "…" }
}
```
> Catatan: `amount` TIDAK dikembalikan di response generate (hanya di request);
> nominal sudah tertanam di `qrContent` untuk QR dynamic.

### A3.3 Dynamic vs Static
- **Dynamic:** QR di-generate tiap transaksi, **nominal sudah tertanam**. Sekali
  pakai. (fokus alur `qr-mpm-generate`)
- **Static:** satu QR tetap dipajang, **nominal diisi customer** saat bayar.

### A3.4 Query status & Payment Notify
- `qr-mpm-query` (poll): request `originalReferenceNo`,
  `originalPartnerReferenceNo`, `serviceCode`; response `latestTransactionStatus`
  (`00` success · `03` pending · `06` failed · `07` expired), `transactionStatusDesc`, `amount`.
- `qr-mpm-notify` (acquirer memberi tahu merchant saat QR dibayar):
  `originalReferenceNo`, `originalPartnerReferenceNo`, `amount`,
  `latestTransactionStatus`, `merchantId`, `paidTime`. Di simulator = **Webhook**.

```jsonc
// response qr-mpm-query — service 51 (terverifikasi ASPI 2026-07-14)
{
  "responseCode": "2005100", "responseMessage": "Successful",
  "originalReferenceNo": "…", "originalPartnerReferenceNo": "…",
  "originalExternalId": "…",
  "serviceCode": "47",                    // 2 char
  "latestTransactionStatus": "00",        // 2 char, 00–07
  "transactionStatusDesc": "…",           // ≤50
  "paidTime": "2025-10-02T17:44:19+07:00",
  "amount":    { "value": "25000.00", "currency": "IDR" },
  "feeAmount": { "value": "0.00",     "currency": "IDR" },
  "terminalId": "…"
}
```

### A3.5 Refund — response (service 78)
```jsonc
// response qr-mpm-refund (terverifikasi ASPI 2026-07-14)
{
  "responseCode": "2007800", "responseMessage": "Successful",
  "originalPartnerReferenceNo": "…", "originalReferenceNo": "…",
  "originalExternalId": "…",
  "refundNo": "…",                        // ≤64, dari acquirer
  "partnerRefundNo": "…",                 // ≤64, echo dari request
  "refundAmount": { "value": "25000.00", "currency": "IDR" },
  "refundTime": "2025-10-02T05:51:05+07:00"
}
```
> `reason` adalah field **request**, BUKAN bagian response refund.
> Refund parsial didukung: status jadi `REFUNDED` hanya saat kumulatif = nominal dibayar.

> Sumber verifikasi: [MPM — ASPI SNAP Developer Site](https://apidevportal.aspi-indonesia.or.id/api-services/transfer-kredit/mpm),
> [QRIS MPM Dynamic — BRIAPI](https://developers.bri.co.id/en/docs/qris-merchant-presented-mode-mpm-dynamic),
> [QRIS — DOKU](https://developers.doku.com/accept-payments/direct-api/snap/integration-guide/qris).

---

## Lampiran B — Sumber

- [SNAP — Bank Indonesia](https://www.bi.go.id/id/layanan/standar/snap/default.aspx)
- [Standar Open API Pembayaran Indonesia (SNAP) — ASPI](https://aspi-indonesia.or.id/standar-dan-layanan/standar-open-api-pembayaran-indonesia-snap/)
- [SNAP Developer Site — ASPI (api-services)](https://apidevportal.aspi-indonesia.or.id/api-services/transfer-kredit/trigger-transfer)
- [Intrabank Transfer — BRIAPI](https://developers.bri.co.id/en/snap-bi/api-account-inquiry-internal-intrabank-transfer-v11)
- [Memahami Dasar Cara Integrasi SNAP BI — Medium](https://thearkas.medium.com/memahami-dasar-cara-integrasi-snap-bi-17a61b65047e)
- [snap-bi-signer-js — GitHub](https://github.com/TheArKaID/snap-bi-signer-js)
- [BCA Virtual Account (SNAP) — DOKU](https://developers.doku.com/accept-payments/direct-api/snap/integration-guide/virtual-account/bca-virtual-account)
- [SNAP Virtual Account — Faspay](https://docs.faspay.co.id/merchant-integration/api-reference-1/snap/snap-virtual-account)
- [QRIS MPM Dynamic — BRIAPI](https://developers.bri.co.id/en/docs/qris-merchant-presented-mode-mpm-dynamic)
- [SNAP Generate QRIS — Faspay](https://docs.faspay.co.id/merchant-integration/api-reference-1/route-payment/snap-generate-qris)
- [MPM — SNAP Developer Site (ASPI)](https://apidevportal.aspi-indonesia.or.id/api-services/transfer-kredit/mpm)
