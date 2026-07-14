--liquibase formatted sql

--changeset behavio:008-endpoint-headers
ALTER TABLE endpoints ADD COLUMN IF NOT EXISTS headers JSONB;
COMMENT ON COLUMN endpoints.headers IS 'JSON: {"headerName": "value", ...} — custom headers tambahan endpoint per-simulator';
