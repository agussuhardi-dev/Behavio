# API Behavior Platform

> **Simulator API transaksi finansial (bank & pihak ketiga) yang kuat, diatur lewat dashboard, dan setia pada standar SNAP BI.**

API Behavior Platform adalah platform open-source untuk **mensimulasikan API pembayaran** (perbankan & PJP) dengan **perilaku bisnis yang realistis**, bukan sekadar respons statis.

Berbeda dari mock server biasa, platform ini fokus pada **simulasi perilaku (behavior)** — memodelkan validasi, signature, aturan bisnis, transisi state, delay, kegagalan, callback, dan respons dinamis **tanpa menulis kode aplikasi**.

Tujuannya: menyediakan simulator yang bisa dipakai untuk **testing aplikasi sendiri, sandbox partner, demo/training, dan uji beban** — menggantikan mock sementara dan mempercepat pengembangan integrasi pembayaran.

> 📄 Desain teknis lengkap ada di [`docs/design.md`](docs/design.md).

---

# Fokus Domain

Platform ini **fokus pada transaksi finansial** — perbankan dan pembayaran:

* **SNAP BI** (Standar Nasional Open API Pembayaran — ASPI/Bank Indonesia): Access Token, Transfer (intrabank/interbank), Virtual Account, Balance Inquiry.
* **QRIS** (MPM dynamic/static).

Domain di luar finansial (mis. logistik, e-commerce) **di luar cakupan** — untuk menjaga fokus dan kesetiaan pada standar.

Mesin di dalamnya tetap **generik/modular** (rule, state, template, webhook, fault, scenario) — sehingga perilaku diatur lewat konfigurasi, bukan koding.

---

# Kenapa API Behavior Platform?

Sebagian besar tool mocking hanya mengembalikan respons yang sudah ditentukan.

Sistem pembayaran nyata berperilaku jauh berbeda. Satu request bisa:

* memverifikasi signature (RSA/HMAC ala SNAP)
* memvalidasi input & header wajib
* memeriksa aturan bisnis (saldo, limit)
* memperbarui state internal (saldo, status transaksi)
* mendeteksi duplikat (idempotensi via `X-EXTERNAL-ID`)
* memicu webhook/callback asinkron
* mengembalikan `responseCode` dinamis
* mensimulasikan kegagalan produksi

Platform ini mereproduksi perilaku itu melalui **Behavior Engine** yang dapat dikonfigurasi — membuat simulasi jauh lebih dekat ke sistem produksi.

---

# Filosofi Inti

## Behavior First

Perilaku bisnis adalah fokus utama. Endpoint hanyalah pintu masuk.

```
Request → Behavior Engine → Response
```

## Configuration over Code

Semua dapat dikonfigurasi tanpa mengubah kode Java: endpoint, rule, state, workflow, variabel, response, delay, fault injection.

## Blueprint + Override Penuh

Platform dikirim dengan **Blueprint** preset yang **akurat sesuai spesifikasi resmi** (SNAP BI). Namun preset hanyalah **titik awal** — setiap elemen dapat **di-override** per-simulator tanpa koding:

* **Path/URL** (mis. `/v1.0/transfer-intrabank` → variasi bank)
* **Header** (tambah/ubah/hapus, termasuk header wajib)
* **Request schema** & **Response body**
* **responseCode / mapping** kode & pesan

> Implementasi antar bank berbeda (contoh: ASPI `/v1.0/transfer-intrabank` vs BRI `/intrabank/snap/v2.0/transfer-intrabank`). Bila ada dokumen eksternal bank tertentu, user cukup menyesuaikan override.

## Scenario Driven

Setiap endpoint punya satu/lebih scenario yang bisa **diganti live tanpa restart**:

* Sukses · Saldo Kurang · Unauthorized · Timeout · Internal Error · Business Rejection · Duplicate Request

## Stateful Simulation

Simulator **mengingat** dunia yang disimulasikan (saldo, status transaksi, token, idempotensi) dan menegakkan transisi state yang valid:

```
CREATED → PAID → SETTLED
PENDING → SUCCESS / FAILED
```

## Simulator per-Port

Tiap simulator = **server pada portnya sendiri** yang bisa **start/stop** (mirip WireMock/Mockoon). Path SNAP tetap asli, isolasi natural, dan mematikan port = simulasi bank yang benar-benar *down* (connection refused).

---

# Fitur Utama

## Simulasi REST API

Endpoint dinamis: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS.

## OpenAPI Import

Impor spesifikasi OpenAPI 3.x (YAML/JSON) untuk menghasilkan endpoint otomatis.

## Behavior Engine (Pipeline)

Jantung platform:

```
Terima Request
   │
Signature / Auth      (STRICT: verifikasi RSA/HMAC | SIMULATED)
   │
Validasi              (header wajib SNAP + schema body)
   │
Idempotensi           (X-EXTERNAL-ID → balas simpanan bila duplikat)
   │
Variable Resolution
   │
Rule Engine           (first-match)
   │
State Machine
   │
Workflow
   │
Fault Injection       (3 titik: tolak-depan / commit-then-drop / respons-rusak)
   │
Response Generator    (responseCode SNAP)
   │
Log + Live View (SSE) + Webhook (async, outbox)
   │
Kembalikan Response
```

## Signature / Auth (SNAP BI)

Dua mode:

* **STRICT** — verifikasi betulan: access token **RSA-SHA256** (`clientId|timestamp`), transaksional **HMAC-SHA512** (`method:path:token:sha256(body):timestamp`). Bisa menolak signature salah seperti sandbox bank asli.
* **SIMULATED** — abaikan/paksa hasil, fokus alur bisnis.

## Rule Engine

Rule mengevaluasi: header, query, path variable, request body, JWT claims, variabel. Model **hybrid**: builder visual (AST) + escape-hatch ekspresi (JEXL).

```
IF amount > account(from).balance THEN responseCode = 4001714 (Insufficient Funds)
```

## State Machine

Menegakkan alur bisnis realistis (transisi valid), mis. status transaksi & VA.

## Workflow Engine

Menjalankan beberapa langkah bisnis sebelum merespons (validasi → reserve → create payment → webhook → response).

## Dynamic Response Templates

```
{ "referenceNo": "{{uuid}}", "transactionDate": "{{now}}",
  "amount": { "value": "{{request.body.amount.value}}", "currency": "IDR" } }
```

## Fault Injection

Mensimulasikan kegagalan produksi di **3 titik**:

* **Titik A — tolak di depan** (saldo utuh): timeout, 500/503, connection reset (mematikan port).
* **Titik B — commit-then-drop** (saldo berubah, respons hilang): menguji idempotensi & rekonsiliasi.
* **Titik C — respons rusak**: malformed JSON, payload terpotong, delay lambat.

## Webhook Simulation

Callback tertunda dengan **persistent outbox + retry**: HMAC/JWT signature, custom header. Contoh: notifikasi pembayaran VA & QRIS.

## Recorder

Rekam setiap request/response: history, replay, search, filter.

## Monitoring (Live View)

Dashboard real-time via **SSE**: Requests/sec, response time, error rate, penggunaan scenario — streaming setiap request.

## Audit Trail

Setiap perubahan konfigurasi tercatat (scenario, rule, endpoint, environment).

---

# Technology Stack

## Backend

* Java 25 LTS
* Spring Boot 4
* **Spring MVC** (transaksi uang stateful lebih aman & sederhana)
* **SSE** (live view, via Event Bus — tak butuh reaktif)
* Gradle Kotlin DSL
* PostgreSQL
* Liquibase
* Lombok
* MapStruct
* Apache JEXL (evaluator ekspresi rule)

## Frontend

* Angular 21+
* Angular Material
* AG Grid
* Monaco Editor
* RxJS
* Signals

---

# Arsitektur Projek

```
Frontend (Angular)
        │
 REST Admin API / SSE
        │
Admin Server (:9000)  ──►  Port/Server Manager
        │                        │ buka/tutup port runtime
        │              ┌─────────┼─────────┐
        │            :9001     :9002     :9003     ← satu simulator = satu port
        ▼              └─────────┼─────────┘
────────────────────────────────────────────
Behavior Engine · Rule Engine · Workflow
State Machine · Signature/Auth · Response Generator
Fault Injection · Webhook (outbox)
────────────────────────────────────────────
        │
PostgreSQL
```

Dua permukaan API terpisah total: **Admin API** (`:9000`, mengatur simulator) dan **API Simulasi** (per-port, meniru SNAP).

---

# Model Data (ringkas)

* **Konfigurasi:** Simulator, Partner (kunci signature), Endpoint, Scenario, Rule, ResponseTemplate, WebhookConfig, FaultConfig.
* **State (isolasi penuh per-partner):** `accounts` & `transactions` (tabel kaku, integritas saldo), `access_tokens`, `idempotency`, `entities` (JSON: VA/QR), `request_logs`.

Strategi penyimpanan **hybrid**: entitas pembawa-uang pakai tabel kaku (saldo ≥ 0 dijaga DB), sisanya JSON generik.

---

# Status Projek

Fase saat ini:

> Desain & Dokumentasi (lihat [`docs/design.md`](docs/design.md))

Roadmap:

* **Fase 0** — Fondasi: skeleton Spring Boot + PostgreSQL + Liquibase + Angular shell + Port/Server Manager.
* **Fase 1 (MVP)** ⭐ — Vertical slice: Access Token B2B → Transfer Intrabank, 3 scenario, pipeline lengkap, live view SSE.
* **Fase 2** — Realisme: signature STRICT, fault 3 titik, webhook outbox.
* **Fase 3** — UX konfigurasi: rule builder (AST) + ekspresi, editor Monaco, browser AG Grid.
* **Fase 4** — Perluasan: Blueprint SNAP, Virtual Account, QRIS MPM, OpenAPI import, Recorder/Replay, Monitoring, Audit.

---

# Visi Jangka Panjang

Menjadi simulator API pembayaran open-source paling andal dan setia-standar untuk pengembangan integrasi finansial di Indonesia — memodelkan sistem bank & PJP nyata melalui **konfigurasi**, bukan kode aplikasi.
