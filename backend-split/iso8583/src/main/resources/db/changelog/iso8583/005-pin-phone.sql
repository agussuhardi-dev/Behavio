--liquibase formatted sql

--changeset behavio:iso8583-008-pin-phone
-- Dukungan operasi Change PIN & Change Phone Number.
-- Sengaja changeset BARU, bukan mengedit yang lama: mengubah changeset yang sudah
-- ter-apply memecahkan checksum Liquibase.
-- (Baris komentar TIDAK boleh diawali kata "changeset" — Liquibase mengiranya direktif.)

ALTER TABLE iso8583.accounts
    ADD COLUMN phone VARCHAR(30);

COMMENT ON COLUMN iso8583.accounts.phone IS
    'Nomor telepon nasabah — sasaran operasi change-phone.';

ALTER TABLE iso8583.cards
    ADD COLUMN pin_block   VARCHAR(64),
    ADD COLUMN pin_changed_at TIMESTAMPTZ;

COMMENT ON COLUMN iso8583.cards.pin_block IS
    'PIN block APA ADANYA dari pesan — DISIMPAN MENTAH, TIDAK didekripsi & TIDAK diverifikasi. '
    'Verifikasi PIN sungguhan menuntut HSM/ZPK; di simulator "PIN salah" dihasilkan lewat '
    'scenario (DE39=55). Kolom ini hanya membuktikan operasi change-pin benar-benar sampai '
    'dan mengubah sesuatu — JANGAN diperlakukan sebagai kredensial.';
