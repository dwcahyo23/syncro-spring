-- Add optimistic-locking version column to machine_sparepart_installations (DW-9).
-- Existing rows are backfilled at 0; JPA increments the version on each update.
ALTER TABLE machine_sparepart_installations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
