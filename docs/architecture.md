# Arsitektur & Desain Database — Behavio

> Status: **cerminan kode per 2026-07-14** · Diverifikasi langsung dari struktur DB yang
> berjalan (`information_schema`) dan dari classpath Gradle, bukan dari ingatan.
>
> **Hubungan dengan `design.md`:** `design.md` adalah *catatan keputusan* — **kenapa**
> sesuatu dipilih, lengkap dengan alternatif yang ditolak. Dokumen ini *peta struktur* —
> **seperti apa bentuknya sekarang**. Kalau keduanya berbeda, kode yang benar; laporkan
> agar dokumen diperbaiki.

---

## 1. Gambaran singkat

Behavio = simulator API transaksi finansial (SNAP BI + QRIS), self-hosted, diatur lewat
dashboard. Satu aplikasi Spring Boot melayani **beberapa produk** yang terpisah penuh.

Dua sumbu yang penting dibedakan sejak awal:

| Sumbu | Isi | Contoh |
|---|---|---|
| **Produk** | apa yang disimulasikan (domain + schema DB + port sendiri) | `bank`, `qris` |
| **Mesin** | bagaimana simulator bekerja (rule, scenario, server, Admin API) | dipakai bersama semua produk |

Prinsip yang memegang seluruh desain: **pisahkan produk, jangan fork mesin.**
Mesin ditulis **sekali**, di-instansiasi **sekali per produk**. Menambah produk baru =
satu modul + satu `ProductCatalog`; mesin tidak disentuh.

---

## 2. Desain Aplikasi

### 2.1 Peta modul (Gradle)

```mermaid
graph LR
    APP["<b>app</b><br/><i>perakitan Spring Boot</i>"]

    subgraph PRODUK["PRODUK — tak ada panah di antara keduanya"]
        PB["<b>product-bank</b><br/><i>schema bank · :9001</i>"]
        PQ["<b>product-qris</b><br/><i>schema qris · :9101</i>"]
    end

    subgraph MESIN["MESIN — ditulis sekali, dipakai semua produk"]
        AP["<b>adapter-persistence</b><br/><i>mesin schema (JdbcClient)</i>"]
        AW["<b>adapter-web</b><br/><i>server per-port + Admin API</i>"]
        AK["<b>adapter-webhook</b><br/><i>outbox + worker</i>"]
        AS["<b>adapter-signature</b><br/><i>RSA/HMAC (JDK murni)</i>"]
    end

    CE["<b>core-engine</b><br/><i>domain murni + ports + SPI</i><br/>0 dependency produksi"]

    APP --> PB
    APP --> PQ
    APP --> AS
    PB --> AP & AW & AK
    PQ --> AP & AW & AK
    AP --> CE
    AW --> CE
    AK --> CE
    AS --> CE

    classDef engine fill:#e8f0fe,stroke:#4285f4
    classDef prod fill:#e6f4ea,stroke:#34a853
    classDef app fill:#fef7e0,stroke:#fbbc04
    classDef core fill:#d2e3fc,stroke:#1967d2,stroke-width:2px
    class AP,AW,AS,AK engine
    class PB,PQ prod
    class APP app
    class CE core
```

> Panah = arah dependensi. **Tak ada panah antara `product-bank` dan `product-qris`** —
> itu bukan kelalaian gambar, itu inti desainnya, dan Gradle yang menegakkannya
> (`gradlew :product-qris:dependencies` tidak memuat `:product-bank`).
> Semua panah bermuara ke `core-engine`, dan `core-engine` tak menunjuk siapa pun.

### 2.2 Aturan dependensi (ditegakkan compiler, bukan konvensi)

1. **`core-engine` bebas framework.** `build.gradle.kts`-nya punya **nol** dependency
   produksi. `import org.springframework…` di engine bukan "dilarang" — ia *mustahil*.
2. **Produk tak saling melihat.** Classpath `:product-qris` tidak memuat `:product-bank`
   (dibuktikan `gradlew :product-qris:dependencies`). Import silang = build merah.
3. **`adapter-persistence` sengaja TANPA JPA.** `@Table(schema=…)` itu statis, jadi JPA
   akan memaksa entity diduplikasi per-schema dan mesinnya ikut jadi dua salinan. Mesin
   konfigurasi memakai `JdbcClient` dengan schema sebagai **parameter**. JPA hanya dipakai
   di `product-bank` untuk state uang.
4. Adapter bergantung pada port yang didefinisikan core — bukan sebaliknya.

### 2.3 SPI produk — satu-satunya tempat mesin "tahu" soal produk

```mermaid
classDiagram
    class ProductCatalog {
        <<interface>>
        +key() String
        +operations() List~Operation~
        +blueprint(op, scenario) Scenario
        +actionCodec() ActionCodec
    }
    class OperationHandler {
        <<interface>>
        +handle(Request) Result
    }
    class ActionCodec {
        <<interface>>
        +parse(kind, attrs) Action
        +encode(Action) Encoded
    }
    ProductCatalog <|.. BankCatalog
    ProductCatalog <|.. QrisCatalog
    ActionCodec <|.. BankActionCodec
    ActionCodec <|.. NONE : QRIS tak memutasi saldo
```

Menggantikan tiga peta terpisah sebelum pemisahan (`SnapOperations`, `Blueprints`,
`ProductEndpoints`) yang masing-masing mencampur bank & QRIS dan harus dijaga sinkron.

### 2.4 Perakitan runtime

Tiap produk merakit mesin generik dengan schema, katalog, dan handler-nya sendiri:

```mermaid
graph LR
    subgraph BANK["BankProductConfig"]
        BT["SchemaTables('bank')"] --> BR["SchemaSimulatorAdmin<br/>SchemaConfigRepository<br/>SchemaEndpointRegistry<br/>…"]
        BC["BankCatalog"] --> BR
        BR --> BSM["SimulatorServerManager('bank')<br/>handlers: transfer, va-*, …"]
    end
    subgraph QRIS["QrisProductConfig"]
        QT["SchemaTables('qris')"] --> QR["Schema* (kelas yang SAMA)"]
        QC["QrisCatalog"] --> QR
        QR --> QSM["SimulatorServerManager('qris')<br/>handlers: qris-*"]
    end
    BSM --> DB[("PostgreSQL")]
    QSM --> DB
```

> **Kenapa bean dirakit eksplisit, bukan autowire by-type:** tipe seperti
> `ConfigRepository` kini ada **dua** di aplikasi (satu per produk). Autowire by-type
> tidak tahu mana yang dimaksud, dan salah pilih berarti profil bank membaca schema QRIS
> tanpa error apa pun — hanya datanya yang salah kamar.

### 2.5 Alur satu request simulasi

```mermaid
sequenceDiagram
    autonumber
    participant C as Klien (curl/app)
    participant S as SimulatorServerManager<br/>(per produk, per port)
    participant R as EndpointRegistry
    participant H as OperationHandler
    participant E as Engine / Service
    participant DB as PostgreSQL (schema produk)
    participant SSE as Live View (SSE)

    C->>S: POST /v1.0/transfer-intrabank
    S->>R: resolveOperation(sim, method, path)
    R-->>S: "transfer"  (atau kosong → 404)
    S->>H: handle(Request)
    H->>E: eksekusi dalam 1 DB transaction
    E->>DB: partner → idempotensi → scenario → rule<br/>→ actions (atomik) → response
    DB-->>E: commit
    E-->>H: Result(status, body, fault?)
    H-->>S: Result
    Note over S: efek fisik fault (delay/drop/corrupt)<br/>diterapkan PASCA-commit
    S-->>C: HTTP response
    S->>DB: tulis request_logs
    S->>SSE: siarkan RequestEvent
```

Catatan penting: **emisi event ada di satu jalur** (server manager) untuk semua operasi.
Sebelumnya hanya transfer yang emit dari dalam engine — di dalam transaksi bisnis —
sehingga request yang rollback tak pernah muncul di Live View, justru saat paling ingin
dilihat.

### 2.6 Admin API

Semua endpoint bersegmen produk; controller-nya **satu set** untuk semua produk
(`{product}` → `ProductRegistry`).

```
/api/admin/v1/{bank|qris}/simulators              GET, POST
                         /{id}                    GET, DELETE
                         /{id}/clone              POST
                         /{id}/start | /stop      POST
                         /{id}/partners           CRUD          (generik)
                         /{id}/endpoints          CRUD path     (generik)
                         /{id}/scenarios?operation=…            (generik)
                         /{id}/scenarios/{name}/definition      (editor)
                         /{id}/active-scenario    PUT
                         /{id}/logs/stream        SSE
                         /{id}/webhooks/outbox    monitoring

khusus produk:
/api/admin/v1/bank/simulators/{id}/accounts            /virtual-accounts
/api/admin/v1/qris/simulators/{id}/qris
/api/admin/v1/webhook-sink                             (test sink, lintas produk)
```

Operasi salah kamar (mis. `?operation=qris-generate` di bawah `/bank/`) ditolak **400**.
Produk tak dikenal → **404**.

---

## 3. Desain Database

### 3.1 Peta schema

```mermaid
graph TB
    subgraph PG["PostgreSQL — database: behavio"]
        PLAT["<b>platform</b><br/>1 tabel<br/><i>port_registry</i>"]
        BANK["<b>bank</b> — 11 tabel<br/>konfigurasi + state<br/><i>+ accounts, transactions</i>"]
        QRIS["<b>qris</b> — 9 tabel<br/>konfigurasi + state<br/><i>tanpa accounts/transactions</i>"]
    end
    PLAT -. "tanpa FK — menunjuk bank.simulators<br/>ATAU qris.simulators tergantung<br/>kolom product" .-> BANK
    PLAT -.-> QRIS
    BANK x--x|"tak ada FK menyeberang"| QRIS
    classDef s fill:#e8f0fe,stroke:#4285f4
    class PLAT,BANK,QRIS s
```

`bank` dan `qris` **tidak punya satu pun FK yang saling menyeberang**. Struktur tabel
konfigurasinya identik — itu memang disengaja, karena mesinnya sama dan hanya schema-nya
yang jadi parameter.

### 3.2 Schema `bank`

```mermaid
erDiagram
    simulators ||--o{ partners : "punya"
    simulators ||--o{ endpoints : "punya"
    simulators ||--o{ accounts : ""
    simulators ||--o{ transactions : ""
    simulators ||--o{ access_tokens : ""
    simulators ||--o{ idempotency : ""
    simulators ||--o{ entities : ""
    simulators ||--o{ request_logs : ""
    simulators ||--o{ webhook_outbox : ""
    partners   ||--o{ accounts : "isolasi per-partner"
    partners   ||--o{ transactions : ""
    partners   ||--o{ access_tokens : ""
    partners   ||--o{ idempotency : ""
    partners   ||--o{ entities : ""
    endpoints  ||--o{ scenarios : "punya"

    simulators {
        uuid id PK
        varchar name
        int port UK "UNIQUE — tapi hanya dlm schema ini"
        varchar status "CHECK RUNNING|STOPPED"
        varchar signature_mode "CHECK STRICT|SIMULATED"
    }
    partners {
        uuid id PK
        uuid simulator_id FK
        varchar partner_id "X-PARTNER-ID · UNIQUE(sim, partner_id)"
        text public_key "verifikasi RSA"
        text client_secret "verifikasi HMAC"
    }
    endpoints {
        uuid id PK
        uuid simulator_id FK
        varchar method
        varchar path "dapat di-custom per profil"
        varchar operation "kunci stabil · UNIQUE(sim, operation)"
        jsonb headers
        uuid active_scenario_id "sakelar utama testing"
    }
    scenarios {
        uuid id PK
        uuid endpoint_id FK
        varchar name
        jsonb definition "NULL = pakai preset blueprint"
    }
    accounts {
        uuid id PK
        uuid simulator_id FK
        uuid partner_id FK
        varchar account_no "UNIQUE(sim, partner, account_no)"
        numeric balance "CHECK balance >= 0"
        varchar currency
    }
    transactions {
        uuid id PK
        varchar reference_no "UNIQUE(sim, reference_no)"
        numeric amount
        varchar status "CHECK PENDING|SUCCESS|FAILED"
    }
    idempotency {
        uuid id PK
        varchar external_id "X-EXTERNAL-ID · UNIQUE(sim, partner, external_id)"
        text stored_response
    }
    entities {
        uuid id PK
        varchar type "'virtual_account'"
        jsonb data
        varchar status
    }
    access_tokens {
        uuid id PK
        varchar token
        timestamptz expires_at
    }
    webhook_outbox {
        uuid id PK
        varchar url
        varchar status "CHECK PENDING|SENT|FAILED"
        int attempts
        timestamptz next_attempt_at
    }
```

### 3.3 Schema `qris`

Identik dengan `bank` untuk seluruh tabel konfigurasi, **kecuali**:

- **tidak ada `accounts` & `transactions`** — QRIS MPM di simulator ini tidak memindahkan
  saldo rekening;
- `entities.type` berisi `'qris'` (QR MPM), bukan `'virtual_account'`.

```mermaid
erDiagram
    simulators ||--o{ partners : ""
    simulators ||--o{ endpoints : ""
    simulators ||--o{ access_tokens : ""
    simulators ||--o{ idempotency : "belum dipakai"
    simulators ||--o{ entities : ""
    simulators ||--o{ request_logs : ""
    simulators ||--o{ webhook_outbox : ""
    endpoints  ||--o{ scenarios : ""
    partners   ||--o{ entities : "isolasi per-partner"

    simulators {
        uuid id PK
        varchar name "profil = PJP/acquirer"
        int port UK "default 9101+"
    }
    entities {
        uuid id PK
        varchar type "'qris'"
        jsonb data "referenceNo, qrContent, amount, paidAmount, refundedAmount…"
        varchar status "ACTIVE|PAID|REFUNDED|EXPIRED"
    }
```

### 3.4 `platform.port_registry` — satu-satunya tabel bersama

```mermaid
erDiagram
    port_registry {
        int port PK "keunikan LINTAS produk"
        varchar product "'bank' | 'qris'"
        uuid simulator_id "UNIQUE(product, simulator_id) — tanpa FK"
        timestamptz created_at
    }
```

**Kenapa ada:** satu proses OS = satu ruang port, tapi `bank.simulators.port` dan
`qris.simulators.port` masing-masing UNIQUE **tanpa saling melihat**. Tanpa registry ini,
profil bank & QRIS bisa sama-sama mengklaim 9001 dan baru gagal saat bind — error yang
muncul jauh dari sebabnya.

**Tanpa FK** karena barisnya menunjuk `bank.simulators` **atau** `qris.simulators`
tergantung kolom `product`. Konsekuensinya baris yatim tak ikut terhapus CASCADE →
dibersihkan `ResetStatusOnBoot` saat aplikasi start.

**Klaim port memakai `INSERT … ON CONFLICT (port) DO NOTHING`**, bukan menangkap
`DuplicateKeyException`: unique violation membuat transaksi Postgres masuk status
*aborted*, sehingga query untuk menyusun pesan error justru gagal `25P02` dan 409 berubah
jadi 500. Ini bug nyata yang pernah terjadi — jangan diubah tanpa memahami ini.

### 3.5 Strategi penyimpanan — hybrid

| Jenis | Bentuk | Alasan |
|---|---|---|
| Pembawa uang (`accounts`, `transactions`) | tabel kaku + CHECK | Ini uang. `balance >= 0` dijaga DB, bukan kode. |
| Non-uang (VA, QR) | `entities` JSONB | Fleksibel, tak perlu migrasi tiap nambah field. |
| Definisi scenario | `scenarios.definition` JSONB | Di-edit dari dashboard, bentuknya cermin AST. |

> **Konvensi wajib:** kolom `jsonb` **tidak pernah dipetakan di entity JPA**.
> `columnDefinition="jsonb"` hanya memengaruhi DDL, bukan binding JDBC — Hibernate tetap
> bind varchar dan setiap update gagal `42804`. Semua akses jsonb lewat JdbcClient dengan
> cast eksplisit (`?::jsonb` / `kolom::text`).

### 3.6 Jejak isolasi per-partner

Setiap baris state membawa `(simulator_id, partner_id)`. Dua partner di simulator yang
sama punya dunia rekening & transaksi yang tak tercampur.

---

## 4. Menambah produk baru

Inilah ujian sesungguhnya dari desain ini. Untuk produk baru (mis. `iso8583`):

1. Buat schema-nya di Liquibase (`db/changelog/<produk>/001-*.sql`).
2. Modul `:product-<nama>` + kelas `ProductCatalog` (daftar operasi + preset blueprint).
3. `<Nama>ProductConfig` merakit mesin generik dengan `SchemaTables("<nama>")`.
4. Daftarkan handler tiap operasi.

**Mesin tidak disentuh sama sekali.** Admin API `/api/admin/v1/<nama>/…` otomatis ada,
karena controller-nya generik.

---

## 5. Keterbatasan yang diketahui (jujur, per 2026-07-14)

Bagian ini sengaja ada supaya dokumen ini tidak jadi brosur.

**`qris.idempotency` belum dipakai.** Dibuat demi simetri; operasi QRIS belum menghormati
`X-EXTERNAL-ID`.

**Isolasi produk belum lengkap di runtime.** Modul sudah tak bisa saling melihat saat
kompilasi, tapi:
- keduanya berbagi **satu Spring context** — satu `@Primary` yang salah bisa membuat bank
  membaca schema QRIS tanpa error;
- keduanya berbagi **satu user DB** (`behavio`) yang punya USAGE di kedua schema — kode
  QRIS *bisa* `SELECT * FROM bank.accounts`; yang mencegah hanya tak ada yang menulis
  begitu. Schema memisahkan *namespace*, bukan *hak akses*.

**SPI masih ber-cetakan HTTP.** `Operation` punya `method` + `defaultPath`;
`OperationHandler.Request` punya `method/path/headers/body`; `Result` punya `status`
(HTTP); server-nya `com.sun.net.httpserver.HttpServer`. Rencana ISO-8583 (design.md §2A
"gRPC/ISO-8583 nanti = tambah adapter inbound") **belum dapat ditepati** tanpa mengangkat
abstraksi transport dulu — ISO-8583 tak punya path/header/HTTP status, melainkan MTI,
bitmap, dan field bernomor di atas TCP. Perlu diputuskan lebih dulu: ISO-8583 itu
**produk baru** atau **transport lain untuk produk bank yang sudah ada** (ATM/POS
menyentuh rekening yang sama).

**Dua klaim pola di design.md §2A tak cocok dengan kode**: *Chain of Responsibility* —
`DefaultBehaviorEngine.process()` sebenarnya method lurus dengan early-return, bukan
rantai objek handler; *State* — `QrisTransaction` itu enum + guard, bukan objek state
polimorfik.

---

## 6. Pipeline Scenario Engine & Handler Wiring

### 6.1 Dua jalur eksekusi

Operasi bank dijalankan lewat salah satu dari dua jalur:

| Jalur | Endpoint | Mekanisme |
|---|---|---|
| **Engine penuh** | transfer, transfer-interbank, balance-inquiry, account-inquiry-internal, transaction-history-list | `SimulationExecutor` → `DefaultBehaviorEngine.process()` — auth, scenario, rule, actions, response rendering |
| **Handler + wrapper** | access-token, va-create, va-status, va-delete | Handler khusus + `withScenario()` — handler jalan seperti biasa, wrapper cek scenario untuk Bank Down / Timeout |

### 6.2 Engine penuh (`DefaultBehaviorEngine.process`)

Pipeline lengkap (urutan persis sesuai kode):
1. Partner resolution (`X-PARTNER-ID`)
2. STRICT mode: validasi Bearer token + HMAC-SHA512 transactional signature
3. Idempotensi (`X-EXTERNAL-ID`) — balas respons tersimpan bila ada
4. Scenario lookup dari `ConfigRepository.findActiveScenario(sim, method, path)`
5. Rule evaluation (first-match dari `scenario.rules()`)
6. Aplikasi actions (Debit/Credit/CreateTransaction) — read-only endpoint punya actions kosong
7. Render response dari template `ResponseSpec.bodyTemplate`
8. Simpan untuk idempotensi
9. Webhook scheduling (bila ada `WebhookSpec`)
10. Fault effects (delay/drop/corrupt — diterapkan adapter pasca-commit)

### 6.3 Handler wrapping (`withScenario`)

Semua operasi non-engine dipasangi wrapper `withScenario()` di `BankProductConfig`:

```
request masuk
  → ConfigRepository.findActiveScenario(sim, method, path)
  → jika scenario.name == "Bank Down" → balas 503 {"responseCode":"5030000",...}
  → panggil handler asli
  → jika scenario.name == "Timeout" → bungkus hasil dengan FaultSpec.delayAfter(5000)
  → kembalikan hasil ke adapter
```

**Catatan:** `access-token` tidak bisa masuk engine karena flow auth-nya berbeda — ia pakai `X-CLIENT-KEY` + RSA asymmetric signature, bukan `X-PARTNER-ID` + HMAC-SHA512.

### 6.4 Custom response definition

| Endpoint | Custom definition dipakai runtime? | Mekanisme |
|---|---|---|
| transfer | **Ya** — engine render dari `scenarios.definition` | `ResponseRenderer.render(template, vars)` |
| transfer-interbank | **Ya** | Sama |
| balance-inquiry | **Ya** | Sama + `enrichAccountVars()` tambah `holderName` dari DB |
| account-inquiry-internal | **Ya** | Sama |
| transaction-history-list | **Ya** | Sama |
| access-token | **Tidak** — hanya nama scenario (Bank Down/Timeout) | `withScenario()` wrapper |
| va-create | **Tidak** — sama | `withScenario()` wrapper |
| va-status | **Tidak** — sama | `withScenario()` wrapper |
| va-delete | **Tidak** — sama | `withScenario()` wrapper |

### 6.5 Blueprint default per endpoint

Setiap endpoint punya blueprint Java yang mendefinisikan 3 scenario standar:

| Blueprint Class | Scenario | Template variables |
|---|---|---|
| `TransferIntrabankBlueprint` | Normal, Saldo Kurang, Limit, Bank Down, Timeout, Commit Then Drop, Malformed, Async Callback | `{{sourceAccountNo}}`, `{{amount}}`, `{{amountValue}}`, `{{currency}}`, ... |
| `InterbankTransferBlueprint` | Normal, Saldo Kurang, Limit, Bank Down, Timeout | Sama + `{{beneficiaryBankCode}}`, `{{traceNo}}` |
| `BalanceInquiryBlueprint` | Normal, Bank Down, Timeout | `{{accountNo}}`, `{{holderName}}`, `{{amountValue}}`, `{{currency}}` |
| `AccountInquiryInternalBlueprint` | Normal, Bank Down, Timeout | `{{accountNo}}`, `{{holderName}}`, `{{currency}}` |
| `TransactionHistoryListBlueprint` | Normal, Bank Down, Timeout | `{{amountValue}}`, `{{currency}}`, `{{txnStatus}}`, `{{txnType}}` |
| `AccessTokenBlueprint` | Normal, Bank Down, Timeout | `{{accessToken}}`, `{{expiresIn}}` |
| `VirtualAccountCreateBlueprint` | Normal, Bank Down, Timeout | `{{virtualAccountNo}}`, `{{virtualAccountName}}`, `{{amountValue}}` |
| `VirtualAccountStatusBlueprint` | Normal, Bank Down, Timeout | `{{virtualAccountNo}}`, `{{vaStatus}}` |
| `VirtualAccountDeleteBlueprint` | Normal, Bank Down, Timeout | — (response sederhana) |

Blueprint disimpan di kode Java (`BankCatalog.blueprint()`), **bukan di database**.
Database (`scenarios.definition`) hanya menyimpan **override custom** dari user.
Alur resolusi: `definition` tidak NULL/blank → pakai isinya; NULL → pakai blueprint Java.

### 6.6 SnapRequestMapper (generic field extraction)

`SnapRequestMapper.toFields()` mengekstrak **semua** field JSON body secara rekursif ke
flat `Map<String, Object>`. Special handling:

- `amount: { value, currency }` → `amount` (BigDecimal) + `currency` (String) — backward compat
- `totalAmount: { value, currency }` → `totalAmount.value` + `totalAmount.currency`
- Nested objects diekstrak dengan dot notation (`nested.field`)
- Numbers dikonversi ke `BigDecimal`

Field-flat ini jadi input ke `EvalContext` (evaluasi rule) dan `renderResponse` (substitusi
template variable).
