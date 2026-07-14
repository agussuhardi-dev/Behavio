--liquibase formatted sql

--changeset behavio:004-webhook-outbox
-- Outbox webhook (design.md §9): enqueue dalam transaksi request (atomik dengan
-- perubahan state), lalu worker mengirim + retry dengan backoff.

CREATE TABLE webhook_outbox (
    id              UUID PRIMARY KEY,
    simulator_id    UUID REFERENCES simulators(id) ON DELETE CASCADE,
    url             VARCHAR(1000) NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}'::jsonb,
    body            TEXT,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',   -- PENDING|SENT|FAILED
    attempts        INTEGER NOT NULL DEFAULT 0,
    max_attempts    INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    last_error      TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at         TIMESTAMPTZ,
    CONSTRAINT ck_outbox_status CHECK (status IN ('PENDING','SENT','FAILED'))
);
CREATE INDEX idx_outbox_due ON webhook_outbox (status, next_attempt_at);
