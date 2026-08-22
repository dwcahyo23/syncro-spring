-- Add optimistic-locking version column to sparepart_alerts (DW-40).
-- Existing rows are backfilled at 0; JPA increments the version on each update.
ALTER TABLE sparepart_alerts ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
