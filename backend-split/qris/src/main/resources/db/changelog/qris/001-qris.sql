--liquibase formatted sql

--changeset behavio:qris-001-config
-- ============================================================
-- PRODUK QRIS (PJP) — BIDANG KONFIGURASI (cetak biru)
-- Identik strukturnya dengan schema 'bank' karena mesin konfigurasinya memang sama
-- (ditulis sekali di adapter-persistence, di-instansiasi per-schema). Profil di sini
-- adalah PJP/acquirer — entitas berbeda dari bank, dengan partner & kredensialnya
-- sendiri, port sendiri, dan state yang tak pernah bersinggungan dengan bank.
-- ============================================================

CREATE TABLE qris.simulators (
    id             UUID PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    port           INTEGER NOT NULL,
    status         VARCHAR(20) NOT NULL DEFAULT 'STOPPED',
    signature_mode VARCHAR(20) NOT NULL DEFAULT 'SIMULATED',
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qris_simulators_port UNIQUE (port),
    CONSTRAINT ck_qris_simulators_status CHECK (status IN ('RUNNING','STOPPED')),
    CONSTRAINT ck_qris_simulators_sigmode CHECK (signature_mode IN ('STRICT','SIMULATED'))
);

CREATE TABLE qris.partners (
    id            UUID PRIMARY KEY,
    simulator_id  UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id    VARCHAR(36) NOT NULL,
    public_key    TEXT,
    client_secret TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qris_partners UNIQUE (simulator_id, partner_id)
);

CREATE TABLE qris.endpoints (
    id                 UUID PRIMARY KEY,
    simulator_id       UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    method             VARCHAR(10) NOT NULL,
    path               VARCHAR(500) NOT NULL,
    operation          VARCHAR(50),
    headers            JSONB,
    active_scenario_id UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qris_endpoints UNIQUE (simulator_id, method, path)
);
CREATE UNIQUE INDEX uq_qris_endpoints_operation
    ON qris.endpoints (simulator_id, operation) WHERE operation IS NOT NULL;

CREATE TABLE qris.scenarios (
    id          UUID PRIMARY KEY,
    endpoint_id UUID NOT NULL REFERENCES qris.endpoints(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    definition  JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE qris.rules (
    id          UUID PRIMARY KEY,
    scenario_id UUID NOT NULL REFERENCES qris.scenarios(id) ON DELETE CASCADE,
    ordering    INTEGER NOT NULL DEFAULT 0,
    name        VARCHAR(200),
    enabled     BOOLEAN NOT NULL DEFAULT true,
    when_json   JSONB,
    then_json   JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_qris_rules_scenario ON qris.rules (scenario_id, ordering);

--changeset behavio:qris-002-state
-- ============================================================
-- PRODUK QRIS — BIDANG STATE
-- Tanpa accounts/transactions: QRIS MPM di simulator ini tidak memindahkan saldo
-- rekening — QR & pembayarannya disimpan sebagai entitas JSONB (design.md §3.2).
-- ============================================================

CREATE TABLE qris.access_tokens (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES qris.partners(id) ON DELETE CASCADE,
    token        VARCHAR(512) NOT NULL,
    issued_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_qris_tokens_lookup ON qris.access_tokens (simulator_id, token);

CREATE TABLE qris.idempotency (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id      UUID NOT NULL REFERENCES qris.partners(id) ON DELETE CASCADE,
    external_id     VARCHAR(36) NOT NULL,
    stored_response TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_qris_idempotency UNIQUE (simulator_id, partner_id, external_id)
);

-- Entitas generik (JSONB) — di schema qris isinya type='qris' (QR MPM).
CREATE TABLE qris.entities (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES qris.partners(id) ON DELETE CASCADE,
    type         VARCHAR(50) NOT NULL,
    data         JSONB NOT NULL DEFAULT '{}'::jsonb,
    status       VARCHAR(30),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_qris_entities_type ON qris.entities (simulator_id, partner_id, type);

CREATE TABLE qris.request_logs (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    method          VARCHAR(10) NOT NULL,
    path            VARCHAR(500) NOT NULL,
    http_status     INTEGER,
    response_code   VARCHAR(7),
    duration_millis BIGINT,
    request_body    TEXT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_qris_logs_sim_time ON qris.request_logs (simulator_id, created_at DESC);

CREATE TABLE qris.webhook_outbox (
    id              UUID PRIMARY KEY,
    simulator_id    UUID REFERENCES qris.simulators(id) ON DELETE CASCADE,
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
    CONSTRAINT ck_qris_outbox_status CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX idx_qris_outbox_due ON qris.webhook_outbox (status, next_attempt_at);

CREATE TABLE qris.webhook_subscriptions (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES qris.partners(id) ON DELETE CASCADE,
    url          VARCHAR(2048) NOT NULL,
    event_type   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_qris_whsub_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_qris_whsub_sim ON qris.webhook_subscriptions (simulator_id, partner_id);
