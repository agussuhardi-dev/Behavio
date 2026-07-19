--liquibase formatted sql

--changeset behavio:bank-003-drop-unused
-- Buang tabel yang terbukti TIDAK PERNAH dipakai (audit 2026-07-14):
--
-- `rules`  — peninggalan desain awal (design.md §8 membayangkan rule disimpan per-baris).
--            Kenyataannya rule disimpan sebagai JSONB di `scenarios.definition` (di-edit
--            dari dashboard, round-trip lewat ScenarioCodec). Nol baris, dan tak ada satu
--            pun kode yang menyentuhnya — SchemaTables bahkan tak punya accessor untuk itu.
--
-- `webhook_subscriptions` — hanya WebhookAdminController (CRUD) yang menyentuhnya; tak ada
--            kode engine yang membacanya saat mengirim webhook, dan dashboard tak pernah
--            memanggilnya. Webhook nyata dikirim ke URL dari header X-CALLBACK-URL
--            per-request (lihat WebhookSpec.urlHeader). Jadi subscription bisa didaftarkan
--            tapi tak akan pernah dipakai — lebih jujur dibuang daripada dibiarkan
--            menipu pembaca skema.
--
-- CATATAN: `webhook_outbox` TETAP ADA — itu inti pengiriman webhook (design.md §9).
DROP TABLE IF EXISTS bank.webhook_subscriptions;
DROP TABLE IF EXISTS bank.rules;
