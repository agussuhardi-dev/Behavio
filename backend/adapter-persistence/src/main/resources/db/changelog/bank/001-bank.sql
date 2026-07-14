--liquibase formatted sql

--changeset behavio:bank-001-config
-- ============================================================
-- PRODUK BANK — BIDANG KONFIGURASI (cetak biru)
-- Set tabel ini sengaja identik dengan schema 'qris': mesin konfigurasi
-- (simulator/partner/endpoint/scenario/rule) generik dan ditulis SEKALI di
-- adapter-persistence, lalu di-instansiasi per-schema. Yang membedakan kedua produk
-- adalah tabel STATE-nya, bukan mesinnya.
-- ============================================================

CREATE TABLE bank.simulators (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    port           INTEGER NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'STOPPED',
    signature_mode VARCHAR(20) NOT NULL DEFAULT 'SIMULATED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_simulators_port UNIQUE (port),
    CONSTRAINT ck_bank_simulators_status CHECK (status IN ('RUNNING','STOPPED')),
    CONSTRAINT ck_bank_simulators_sigmode CHECK (signature_mode IN ('STRICT','SIMULATED'))
);

CREATE TABLE bank.partners (
    id            UUID PRIMARY KEY,
    simulator_id  UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id    VARCHAR(36) NOT NULL,           -- nilai X-PARTNER-ID
    public_key    TEXT,                           -- verifikasi RSA (access token)
    client_secret TEXT,                           -- verifikasi HMAC (transaksional)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_partners UNIQUE (simulator_id, partner_id)
);

CREATE TABLE bank.endpoints (
    id                 UUID PRIMARY KEY,
    simulator_id       UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    method             VARCHAR(10) NOT NULL,
    path               VARCHAR(500) NOT NULL,
    operation          VARCHAR(50),               -- kunci stabil; path boleh di-custom
    headers            JSONB,
    active_scenario_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_endpoints UNIQUE (simulator_id, method, path)
);
CREATE UNIQUE INDEX uq_bank_endpoints_operation
    ON bank.endpoints (simulator_id, operation) WHERE operation IS NOT NULL;

CREATE TABLE bank.scenarios (
    id          UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES bank.endpoints(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    definition  JSONB,                             -- NULL = pakai preset blueprint
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE bank.rules (
    id          UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES bank.scenarios(id) ON DELETE CASCADE,
    ordering    INTEGER NOT NULL DEFAULT 0,        -- first-match
    name        VARCHAR(200),
    enabled     BOOLEAN NOT NULL DEFAULT true,
    when_json   JSONB,
    then_json   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_rules_scenario ON bank.rules (scenario_id, ordering);

--changeset behavio:bank-002-state
-- ============================================================
-- PRODUK BANK — BIDANG STATE (isolasi penuh per-partner)
-- accounts & transactions = tabel kaku (uang, integritas dijaga DB) — TIDAK ada
-- padanannya di schema qris, karena QRIS tak memindahkan saldo rekening.
-- ============================================================

CREATE TABLE bank.access_tokens (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    token        VARCHAR(512) NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_bank_tokens_lookup ON bank.access_tokens (simulator_id, token);

CREATE TABLE bank.idempotency (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id      UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    external_id     VARCHAR(36) NOT NULL,          -- X-EXTERNAL-ID
    stored_response TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_bank_idempotency UNIQUE (simulator_id, partner_id, external_id)
);

CREATE TABLE bank.accounts (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    account_no   VARCHAR(34) NOT NULL,
    holder_name  VARCHAR(140),
    currency     VARCHAR(3) NOT NULL DEFAULT 'IDR',
    balance      NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_bank_accounts_balance CHECK (balance >= 0),   -- saldo tak boleh minus
    CONSTRAINT uq_bank_accounts UNIQUE (simulator_id, partner_id, account_no)
);

CREATE TABLE bank.transactions (
    id                     UUID PRIMARY KEY,
    simulator_id           UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id             UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    reference_no           VARCHAR(64) NOT NULL,
    partner_reference_no   VARCHAR(64),
    source_account_no      VARCHAR(34),
    beneficiary_account_no VARCHAR(34),
    amount                 NUMERIC(18,2) NOT NULL,
    currency               VARCHAR(3) NOT NULL DEFAULT 'IDR',
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_bank_txn_status CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    CONSTRAINT uq_bank_txn_reference UNIQUE (simulator_id, reference_no)
);
CREATE INDEX idx_bank_txn_partner ON bank.transactions (simulator_id, partner_id);

-- Entitas generik (JSONB) untuk non-uang — di schema bank isinya virtual_account
-- (design.md §3.2 hybrid storage).
CREATE TABLE bank.entities (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    type         VARCHAR(50) NOT NULL,             -- 'virtual_account'
    data         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(30),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_entities_type ON bank.entities (simulator_id, partner_id, type);

CREATE TABLE bank.request_logs (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    method          VARCHAR(10) NOT NULL,
    path            VARCHAR(500) NOT NULL,
    http_status     INTEGER,
    response_code   VARCHAR(7),
    duration_millis BIGINT,
    request_body    TEXT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_bank_logs_sim_time ON bank.request_logs (simulator_id, created_at DESC);

CREATE TABLE bank.webhook_outbox (
    id              UUID PRIMARY KEY,
    simulator_id    UUID REFERENCES bank.simulators(id) ON DELETE CASCADE,
    url             VARCHAR(1000) NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}'::jsonb,
    body            TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_bank_outbox_status CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX idx_bank_outbox_due ON bank.webhook_outbox (status, next_attempt_at);

CREATE TABLE bank.webhook_subscriptions (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    url          VARCHAR(2048) NOT NULL,
    event_type   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_bank_whsub_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_bank_whsub_sim ON bank.webhook_subscriptions (simulator_id, partner_id);
