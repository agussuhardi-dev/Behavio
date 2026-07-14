--liquibase formatted sql

--changeset behavio:007-qris-cancel
-- Rename old qris-expire operation key to qris-cancel (SNAP MPM spec service 77).
-- The path also changed from /v1.0/qr/qr-expire to /v1.0/qr/qr-mpm-cancel
-- per the ASPI SNAP BI MPM specification.
UPDATE endpoints SET operation = 'qris-cancel' WHERE operation = 'qris-expire';
UPDATE endpoints SET path = '/v1.0/qr/qr-mpm-cancel' WHERE path = '/v1.0/qr/qr-expire';
