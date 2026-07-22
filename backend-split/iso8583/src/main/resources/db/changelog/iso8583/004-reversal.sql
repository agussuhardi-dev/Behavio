--liquibase formatted sql

--changeset behavio:iso8583-007-txn-reversal
-- Kolom tambahan agar transaksi bisa DIBALIK. Changeset BARU, bukan mengedit
-- iso8583-004-state: mengubah changeset yang sudah ter-apply memecahkan checksum Liquibase.
ALTER TABLE iso8583.transactions
    ADD COLUMN account_no     VARCHAR(28),
    ADD COLUMN counterpart_no VARCHAR(28),
    ADD COLUMN reversed       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reversed_at    TIMESTAMPTZ;

COMMENT ON COLUMN iso8583.transactions.account_no IS
    'Rekening yang DIDEBIT. Wajib disimpan: reversal harus tahu ke mana dana dikembalikan, dan DE90 hanya membawa identitas pesan asli — bukan rekeningnya.';
COMMENT ON COLUMN iso8583.transactions.counterpart_no IS
    'Rekening tujuan (transfer). NULL untuk tarik tunai.';
COMMENT ON COLUMN iso8583.transactions.reversed IS
    'Penanda idempotensi. Reversal LAZIM dikirim ulang saat acquirer tak yakin sudah diterima; tanpa penanda ini pengiriman kedua akan mengembalikan dana dua kali.';

-- Pencarian transaksi asli oleh reversal: DE90 membawa MTI + STAN pesan asli.
CREATE INDEX idx_iso_txn_stan ON iso8583.transactions (simulator_id, stan);
