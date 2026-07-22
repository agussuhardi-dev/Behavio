--liquibase formatted sql

--changeset behavio:iso8583-001-schema
-- ============================================================
-- PRODUK ISO-8583 (host simulator, transport TCP)
-- Schema sendiri, tak bersinggungan dengan 'bank' maupun 'qris' — termasuk rekening &
-- kartunya sendiri (docs/iso8583-plan.md §3 poin 4). Saldo di sini BUKAN saldo bank.
-- ============================================================
CREATE SCHEMA IF NOT EXISTS iso8583;

--changeset behavio:iso8583-002-spec-profiles
-- Profil spec = DATA yang di-upload, bukan kode (docs/iso8583-plan.md §2). Satu profil =
-- satu host/jaringan (Shinhan, ATM Bersama, …). Spec host bank tidak publik, jadi
-- menambah host baru harus cukup dengan unggah profil — tanpa menyentuh kode.
CREATE TABLE iso8583.spec_profiles (
    id            UUID PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    version       VARCHAR(50)  NOT NULL,
    parent        VARCHAR(100),
    definition    JSONB        NOT NULL,
    source_format VARCHAR(10)  NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- IMMUTABLE: unggahan berikutnya = versi BARU, bukan menimpa. Tanpa ini, seseorang
    -- meng-update spec dan tes yang kemarin hijau mendadak merah tanpa jejak penyebab.
    CONSTRAINT uq_spec_profiles_name_version UNIQUE (name, version)
);

COMMENT ON TABLE iso8583.spec_profiles IS
    'Profil spec ISO-8583 hasil unggahan (packager XML atau JSON), disimpan kanonik sebagai JSON. Immutable per (name, version).';
COMMENT ON COLUMN iso8583.spec_profiles.parent IS
    'Nama profil induk yang diwarisi (extends). Profil turunan cukup memuat DE yang BERBEDA — spec bank umumnya "standar, kecuali N field ini".';
COMMENT ON COLUMN iso8583.spec_profiles.source_format IS
    'Asal berkas: XML (jPOS packager) atau JSON. Hanya untuk jejak; isinya selalu disimpan sebagai JSON kanonik.';

CREATE INDEX idx_spec_profiles_name ON iso8583.spec_profiles (name);
