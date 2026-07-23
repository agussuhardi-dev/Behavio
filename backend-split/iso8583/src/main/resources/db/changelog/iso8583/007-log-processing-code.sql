--liquibase formatted sql

--changeset behavio:iso8583-010-log-processing-code
-- Processing code (DE3) disimpan bersama log pertukaran pesan.
-- Sebelumnya baris log hanya memuat nama operasi hasil routing. Saat pemakai merasa
-- "saya kirim 400000 tapi tercatat inquiry", tak ada cara membuktikan apa pun tanpa
-- men-decode hex secara manual — padahal nilai yang menentukan routing justru DE3.

ALTER TABLE iso8583.request_logs
    ADD COLUMN processing_code VARCHAR(6);

COMMENT ON COLUMN iso8583.request_logs.processing_code IS
    'DE3 apa adanya dari request. Bersama kolom operation ia menjawab "kenapa pesan ini '
    'dirutekan ke sana" tanpa perlu membaca hex.';
