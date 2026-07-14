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

### Belum dibangun (berikutnya)
Builder visual rule (saat ini editor JSON teks — "advanced mode"), penerapan editor
ke endpoint VA & QRIS, verifikasi token Bearer pada transaksional (STRICT), edit
partner/akun dari dashboard (kini hanya via baseline provisioning), Recorder/Replay
UI, editor Monaco (ganti textarea), Workspace sebagai pengelompokan opsional lintas
banyak profil bank (belum diperlukan karena isolasi sudah lewat port+simulator_id).

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
| Query status | POST | `/v1.0/qr/qr-mpm-query` | 51 | `2005100` |
| **Payment Notify** (acquirer→merchant callback) | POST | `{merchantUrl}/v1.0/qr/qr-mpm-notify` | 52 | `2005200` |

### A3.2 Generate QR — request/response
```jsonc
// request
{
  "partnerReferenceNo": "…",                     // ≤32
  "amount": { "value": "10000.00", "currency": "IDR" },
  "merchantId": "…", "terminalId": "…",
  "validityPeriod": "ISO-8601",                  // masa berlaku QR
  "additionalInfo": { }
}
// response
{
  "responseCode": "2004700", "responseMessage": "Successful",
  "referenceNo": "…", "partnerReferenceNo": "…",
  "qrContent": "<EMV QR string>", "qrUrl": "…",
  "additionalInfo": { "nmid": "…", "merchantId": "…" }
}
```

### A3.3 Dynamic vs Static
- **Dynamic:** QR di-generate tiap transaksi, **nominal sudah tertanam**. Sekali
  pakai. (fokus alur `qr-mpm-generate`)
- **Static:** satu QR tetap dipajang, **nominal diisi customer** saat bayar.

### A3.4 Query status & Payment Notify
- `qr-mpm-query` (poll): request `originalReferenceNo`,
  `originalPartnerReferenceNo`, `serviceCode`; response `latestTransactionStatus`
  (`00` success · `03` pending · `06` failed), `transactionStatusDesc`, `amount`.
- `qr-mpm-notify` (acquirer memberi tahu merchant saat QR dibayar):
  `originalReferenceNo`, `originalPartnerReferenceNo`, `amount`,
  `latestTransactionStatus`, `merchantId`, `paidTime`. Di simulator = **Webhook**.

> Catatan: detail field `qr-mpm-query`/`qr-mpm-notify` mengikuti pola standar
> SNAP; **verifikasi final ke dokumen ASPI/bank** saat implementasi Blueprint
> (sebagian portal memblok akses otomatis saat riset ini).

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
