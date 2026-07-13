--liquibase formatted sql

--changeset behavio:001-config-tables
-- ============================================================
-- BIDANG KONFIGURASI (cetak biru)
-- ============================================================

CREATE TABLE simulators (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    port           INTEGER NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'STOPPED',
    signature_mode VARCHAR(20) NOT NULL DEFAULT 'SIMULATED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_simulators_port UNIQUE (port),
    CONSTRAINT ck_simulators_status CHECK (status IN ('RUNNING','STOPPED')),
    CONSTRAINT ck_simulators_sigmode CHECK (signature_mode IN ('STRICT','SIMULATED'))
);

CREATE TABLE partners (
    id            UUID PRIMARY KEY,
    simulator_id  UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id    VARCHAR(36) NOT NULL,           -- nilai X-PARTNER-ID
    public_key    TEXT,                           -- verifikasi RSA (access token)
    client_secret TEXT,                           -- verifikasi HMAC (transaksional)
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_partners UNIQUE (simulator_id, partner_id)
);

CREATE TABLE endpoints (
    id                 UUID PRIMARY KEY,
    simulator_id       UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    method             VARCHAR(10) NOT NULL,
    path               VARCHAR(500) NOT NULL,
    active_scenario_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_endpoints UNIQUE (simulator_id, method, path)
);

CREATE TABLE scenarios (
    id          UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES endpoints(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rules (
    id          UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES scenarios(id) ON DELETE CASCADE,
    ordering    INTEGER NOT NULL DEFAULT 0,         -- first-match
    name        VARCHAR(200),
    enabled     BOOLEAN NOT NULL DEFAULT true,
    when_json   JSONB,                              -- Condition (AST + escape-hatch)
    then_json   JSONB,                              -- Outcome (actions+response+fault+webhook)
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_rules_scenario ON rules (scenario_id, ordering);

--changeset behavio:002-state-tables
-- ============================================================
-- BIDANG STATE (isolasi penuh per-partner)
-- Tabel kaku untuk entitas pembawa uang (integritas dijaga DB).
-- ============================================================

CREATE TABLE accounts (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    account_no   VARCHAR(34) NOT NULL,
    holder_name  VARCHAR(140),
    currency     VARCHAR(3) NOT NULL DEFAULT 'IDR',
    balance      NUMERIC(18,2) NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_accounts_balance CHECK (balance >= 0),      -- saldo tak boleh minus
    CONSTRAINT uq_accounts UNIQUE (simulator_id, partner_id, account_no)
);

CREATE TABLE transactions (
    id                     UUID PRIMARY KEY,
    simulator_id           UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id             UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    reference_no           VARCHAR(64) NOT NULL,
    partner_reference_no   VARCHAR(64),
    source_account_no      VARCHAR(34),
    beneficiary_account_no VARCHAR(34),
    amount                 NUMERIC(18,2) NOT NULL,
    currency               VARCHAR(3) NOT NULL DEFAULT 'IDR',
    status                 VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_txn_status CHECK (status IN ('PENDING','SUCCESS','FAILED')),
    CONSTRAINT uq_txn_reference UNIQUE (simulator_id, reference_no)
);
CREATE INDEX idx_txn_partner ON transactions (simulator_id, partner_id);

CREATE TABLE access_tokens (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    token        VARCHAR(512) NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tokens_lookup ON access_tokens (simulator_id, token);

CREATE TABLE idempotency (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id      UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    external_id     VARCHAR(36) NOT NULL,           -- X-EXTERNAL-ID
    stored_response TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_idempotency UNIQUE (simulator_id, partner_id, external_id)
);

-- Entitas generik (JSON) untuk non-uang: virtual_account, qr, dll (tanpa migrasi).
CREATE TABLE entities (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    type         VARCHAR(50) NOT NULL,              -- 'virtual_account' | 'qr' | ...
    data         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(30),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_entities_type ON entities (simulator_id, partner_id, type);

CREATE TABLE request_logs (
    id             UUID PRIMARY KEY,
    simulator_id   UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    method         VARCHAR(10) NOT NULL,
    path           VARCHAR(500) NOT NULL,
    http_status    INTEGER,
    response_code  VARCHAR(7),
    duration_millis BIGINT,
    request_body   TEXT,
    response_body  TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_logs_sim_time ON request_logs (simulator_id, created_at DESC);
