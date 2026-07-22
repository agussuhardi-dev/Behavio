--liquibase formatted sql

--changeset behavio:iso8583-009-log-error
-- Alasan kegagalan disimpan bersama log pertukaran pesan.
-- Sebelumnya pesan yang gagal di-unpack hanya tercatat dengan response_hex kosong:
-- klien melihat "timeout" dan dashboard tak memberi petunjuk apa pun. Penyebab tersering
-- (bitmap HEX vs BINER, DE di luar kamus) sebenarnya sudah diketahui server — hanya
-- terbuang ke log aplikasi. Kolom ini membawanya ke Live View.

ALTER TABLE iso8583.request_logs
    ADD COLUMN error TEXT;

COMMENT ON COLUMN iso8583.request_logs.error IS
    'Alasan pesan gagal diproses; NULL bila berhasil. Balasan kosong + kolom ini terisi '
    'berarti klien akan mengalami timeout — dan di sinilah sebabnya tertulis.';
