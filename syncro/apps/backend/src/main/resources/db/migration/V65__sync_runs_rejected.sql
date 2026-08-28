-- Story 13-3: Quarantine & Observability (FR-153).
-- Additive on V64.
--
-- Add rows_rejected to sync_runs so the health dashboard can report rejected rows
-- alongside rows_read and rows_upserted. The DEFAULT 0 means existing runs get a
-- sensible value without a backfill.

ALTER TABLE sync_runs ADD COLUMN rows_rejected INT NOT NULL DEFAULT 0;