--liquibase formatted sql

--changeset behavio:006-endpoint-operation
-- URL endpoint per-simulator dapat di-custom dari dashboard (design.md §2 override
-- path/URL). 'operation' = kunci internal stabil yang tak berubah walau path diedit
-- (mis. bank BRI pakai path berbeda dari ASPI standar untuk operasi yang sama).
ALTER TABLE endpoints ADD COLUMN operation VARCHAR(50);

-- Backfill baris lama (Fase 1-4 sebelum kolom ini ada): satu-satunya operation yang
-- sudah tercatat dgn scenario adalah transfer & qris-generate (dikenali dari path).
UPDATE endpoints SET operation = 'transfer' WHERE path = '/v1.0/transfer-intrabank' AND operation IS NULL;
UPDATE endpoints SET operation = 'qris-generate' WHERE path = '/v1.0/qr/qr-mpm-generate' AND operation IS NULL;

CREATE UNIQUE INDEX uq_endpoints_operation ON endpoints (simulator_id, operation) WHERE operation IS NOT NULL;
