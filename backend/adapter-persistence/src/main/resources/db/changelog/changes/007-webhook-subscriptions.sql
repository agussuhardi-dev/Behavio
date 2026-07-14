--liquibase formatted sql

--changeset behavio:007-webhook-subscriptions
CREATE TABLE webhook_subscriptions (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES partners(id) ON DELETE CASCADE,
    url          VARCHAR(2048) NOT NULL,
    event_type   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_whsub_status CHECK (status IN ('ACTIVE','INACTIVE'))
);
CREATE INDEX idx_whsub_sim ON webhook_subscriptions (simulator_id, partner_id);
