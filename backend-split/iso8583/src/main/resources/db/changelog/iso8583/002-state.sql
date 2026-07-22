--liquibase formatted sql

--changeset behavio:iso8583-003-simulators
-- Profil simulator ISO. Berbeda dari bank/qris: TAK punya kolom method/path karena
-- ISO-8583 tak mengenal URL — routing operasi ditentukan MTI + processing code lewat
-- profil spec yang ditunjuk kolom spec_profile_*.
CREATE TABLE iso8583.simulators (
    id                    UUID PRIMARY KEY,
    name                  VARCHAR(200) NOT NULL,
    port                  INTEGER      NOT NULL UNIQUE,
    status                VARCHAR(20)  NOT NULL DEFAULT 'STOPPED',
    spec_profile_name     VARCHAR(100) NOT NULL,
    spec_profile_version  VARCHAR(50)  NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
COMMENT ON COLUMN iso8583.simulators.spec_profile_name IS
    'Profil spec yang dipakai. Menunjuk versi TERTENTU (bukan "terbaru") supaya perilaku simulator tak berubah diam-diam saat profil baru diunggah.';

--changeset behavio:iso8583-004-state
-- Rekening & kartu MILIK SENDIRI — terpisah penuh dari bank.accounts (docs/iso8583-plan.md
-- §3 poin 4). Saldo di sini BUKAN saldo bank simulator; keduanya dunia berbeda.
CREATE TABLE iso8583.accounts (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES iso8583.simulators(id) ON DELETE CASCADE,
    account_no   VARCHAR(28)   NOT NULL,
    holder_name  VARCHAR(100)  NOT NULL,
    balance      NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency     VARCHAR(3)    NOT NULL DEFAULT '360',
    CONSTRAINT uq_iso_accounts UNIQUE (simulator_id, account_no)
);
COMMENT ON COLUMN iso8583.accounts.currency IS
    'Kode mata uang ISO-4217 NUMERIK (360 = IDR) — ISO-8583 DE49 memakai angka, bukan "IDR".';

CREATE TABLE iso8583.cards (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES iso8583.simulators(id) ON DELETE CASCADE,
    pan          VARCHAR(19) NOT NULL,
    account_no   VARCHAR(28) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    CONSTRAINT uq_iso_cards UNIQUE (simulator_id, pan)
);
COMMENT ON TABLE iso8583.cards IS
    'PAN → rekening. Konsep khas ISO yang tak ada di bank simulator. PIN TIDAK disimpan: verifikasi PIN butuh HSM/ZPK, jadi "PIN salah" disimulasikan lewat scenario (DE39=55).';

CREATE TABLE iso8583.transactions (
    id              UUID PRIMARY KEY,
    simulator_id    UUID NOT NULL REFERENCES iso8583.simulators(id) ON DELETE CASCADE,
    mti             VARCHAR(4)  NOT NULL,
    processing_code VARCHAR(6),
    stan            VARCHAR(6),
    rrn             VARCHAR(12),
    pan             VARCHAR(19),
    amount          NUMERIC(18,2),
    response_code   VARCHAR(2),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_iso_txn_sim ON iso8583.transactions (simulator_id, created_at DESC);

--changeset behavio:iso8583-005-request-logs
-- Live View: pesan disimpan sebagai HEX, bukan teks — ISO-8583 itu biner, dan hex adalah
-- bentuk yang bisa ditempel balik ke fitur uji trace saat menelusuri masalah.
CREATE TABLE iso8583.request_logs (
    id            UUID PRIMARY KEY,
    simulator_id  UUID NOT NULL REFERENCES iso8583.simulators(id) ON DELETE CASCADE,
    mti           VARCHAR(4),
    operation     VARCHAR(50),
    response_code VARCHAR(2),
    request_hex   TEXT,
    response_hex  TEXT,
    duration_ms   BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_iso_logs_sim ON iso8583.request_logs (simulator_id, created_at DESC);
