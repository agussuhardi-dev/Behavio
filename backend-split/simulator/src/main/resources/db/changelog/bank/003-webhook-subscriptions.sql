--liquibase formatted sql

--changeset behavio:bank-004-webhook-subscriptions
-- ============================================================
-- REGISTRASI URL NOTIFIKASI (design.md §9.1)
--
-- Tabel ini pernah ADA lalu DIHAPUS di changeset `bank-003-drop-unused` (2026-07-14).
-- Alasan penghapusannya masih benar: waktu itu ia bisa didaftarkan tapi tak pernah
-- dibaca engine, jadi "fitur yang tak berefek". Kini ia dihidupkan kembali karena
-- perannya berubah total: sejak `X-CALLBACK-URL` dibuang, tabel ini adalah
-- SATU-SATUNYA sumber URL tujuan webhook — dibaca engine lewat port
-- `WebhookSubscriptions` pada setiap pengiriman.
--
-- Ditulis sebagai berkas BARU (bukan dengan mengedit 001/002): mengubah changeset yang
-- sudah ter-apply akan memecahkan checksum Liquibase pada DB yang berjalan.
--
-- CATATAN PARSER: jangan pernah memulai baris komentar dengan kata `changeset`,
-- `rollback`, atau `precondition` — parser SQL berformat Liquibase membaca `-- changeset`
-- sebagai direktif, lalu menolak seluruh berkas. Prosa yang tak sengaja jadi perintah.
--
-- event_type: 'transfer-notify' | 'va-payment' | 'ALL'
--   Resolusi (§9.1): cari event spesifik dulu → bila tak ada pakai 'ALL' → bila tak ada
--   juga, webhook dilewati dan alasannya dicatat.
-- ============================================================

CREATE TABLE bank.webhook_subscriptions (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES bank.simulators(id) ON DELETE CASCADE,
    partner_id   UUID NOT NULL REFERENCES bank.partners(id) ON DELETE CASCADE,
    url          VARCHAR(2048) NOT NULL,
    event_type   VARCHAR(50) NOT NULL DEFAULT 'ALL',
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_bank_whsub_status CHECK (status IN ('ACTIVE','INACTIVE')),
    -- Satu partner hanya boleh punya SATU URL per event: tanpa ini, dua baris untuk event
    -- sama membuat tujuan notifikasi bergantung urutan baris — persis jenis kesalahan
    -- diam-diam yang berulang kali digigit di dokumen ini.
    CONSTRAINT uq_bank_whsub UNIQUE (simulator_id, partner_id, event_type)
);
CREATE INDEX idx_bank_whsub_lookup ON bank.webhook_subscriptions (simulator_id, partner_id, event_type)
    WHERE status = 'ACTIVE';
