--liquibase formatted sql

--changeset behavio:iso8583-006-scenarios
-- Scenario per OPERASI (bukan per method+path seperti produk HTTP): ISO-8583 tak punya
-- URL, operasinya ditentukan MTI + processing code lewat profil spec.
--
-- Definisi disimpan jsonb dan BISA DIEDIT user ("Edit Response") — filosofi yang sama
-- dengan blueprint→override di bank/qris, hanya bentuk body-nya peta DE, bukan JSON SNAP.
CREATE TABLE iso8583.scenarios (
    id           UUID PRIMARY KEY,
    simulator_id UUID NOT NULL REFERENCES iso8583.simulators(id) ON DELETE CASCADE,
    operation    VARCHAR(50)  NOT NULL,
    name         VARCHAR(100) NOT NULL,
    definition   JSONB        NOT NULL,
    is_active    BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_iso_scenarios UNIQUE (simulator_id, operation, name)
);

CREATE INDEX idx_iso_scenarios_active
    ON iso8583.scenarios (simulator_id, operation) WHERE is_active;

COMMENT ON COLUMN iso8583.scenarios.definition IS
    'Peta DE yang ditimpakan ke response alami + fault. Sengaja MENIMPA, bukan mengganti: field korelasi (STAN/RRN) wajib tetap digemakan, kalau diganti utuh peer tak bisa memasangkan balasan.';
