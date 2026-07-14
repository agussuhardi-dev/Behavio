--liquibase formatted sql

--changeset behavio:005-scenario-definition
-- Definisi scenario yang dapat di-override dari dashboard (design.md §2 & §8).
-- NULL = pakai preset blueprint; berisi JSON = definisi custom (request cond + response).
ALTER TABLE scenarios ADD COLUMN definition JSONB;
