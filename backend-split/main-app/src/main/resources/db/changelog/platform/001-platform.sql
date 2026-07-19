--liquibase formatted sql

--changeset behavio:platform-001-schemas
-- Pemisahan penuh bank vs QRIS (design.md §3.4): tiap produk punya schema sendiri
-- dengan set tabel lengkap, sehingga keduanya dapat dipecah jadi dua service tanpa
-- memindahkan data. Schema 'platform' HANYA berisi hal yang secara fisik memang
-- lintas-produk: alokasi port TCP.
CREATE SCHEMA IF NOT EXISTS platform;
CREATE SCHEMA IF NOT EXISTS bank;
CREATE SCHEMA IF NOT EXISTS qris;

--changeset behavio:platform-002-port-registry
-- Satu proses OS = satu ruang port. Constraint UNIQUE(port) di bank.simulators dan
-- qris.simulators tidak saling melihat, jadi profil bank & profil QRIS bisa sama-sama
-- mengklaim port yang sama dan baru gagal saat bind (connection error yang membingungkan).
-- Registry ini membuat DB yang menegakkan keunikan port LINTAS produk, bukan cek
-- aplikasi yang punya celah race.
CREATE TABLE platform.port_registry (
    port         INTEGER PRIMARY KEY,
    product      VARCHAR(20) NOT NULL,
    simulator_id UUID NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_port_registry_sim UNIQUE (product, simulator_id)
);
COMMENT ON TABLE platform.port_registry IS
    'Alokasi port lintas produk. Tak ada FK: baris merujuk bank.simulators ATAU qris.simulators tergantung kolom product; dibersihkan aplikasi saat simulator dihapus.';
