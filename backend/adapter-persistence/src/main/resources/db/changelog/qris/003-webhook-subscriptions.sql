--liquibase formatted sql

--changeset behavio:qris-004-webhook-subscriptions
-- ============================================================
-- REGISTRASI URL NOTIFIKASI — QRIS (design.md §9.1)
--
-- Sengaja identik bentuknya dengan `bank.webhook_subscriptions`: mesin webhook generik
-- dan ditulis SEKALI, lalu di-instansiasi per-schema (sama seperti tabel konfigurasi
-- lain). Yang membedakan produk adalah event-nya, bukan mesinnya.
--
-- Riwayat & alasan sama dengan sisi bank: tabel ini pernah dihapus di
-- `qris-003-drop-unused` karena tak pernah dibaca engine; kini dihidupkan karena
-- menjadi satu-satunya sumber URL setelah `X-CALLBACK-URL` dibuang.
--
-- event_type: 'qris-payment' | 'ALL'
-- ============================================================

CREATE TABLE qris.webhook_subscriptions (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES qris.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES qris.partners(id) ON DELETE CASCADE,
    url          VARCHAR(2048) NOT NULL,
    event_type   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_qris_whsub_status CHECK (status IN ('ACTIVE','INACTIVE')),
    CONSTRAINT uq_qris_whsub UNIQUE (simulator_id, partner_id, event_type)
);
CREATE INDEX idx_qris_whsub_lookup ON qris.webhook_subscriptions (simulator_id, partner_id, event_type)
    WHERE status = 'ACTIVE';
