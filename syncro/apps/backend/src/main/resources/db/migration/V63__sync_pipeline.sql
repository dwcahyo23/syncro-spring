-- Story 13-1: Sync pipeline foundation (AD-7/FR-150/FR-151).
-- Additive on V62.
--
-- 1. sync_watermarks — single-row table (guarded by a CHECK on the PK) holding the
--    last processed external sheet_no so the scheduled worker resumes after restart.
--    The fixed UUID '00000000-0000-0000-0000-000000000001' gives JPA a stable key.
-- 2. sync_runs — one row per sync cycle: status + rows_read/rows_upserted + error.
-- 3. Add SYNC_RUN to the audit_log entity_type CHECK (drop/re-add pattern from V61).

-- ---------------------------------------------------------------------------
-- 1. sync_watermarks — single-row resume watermark
-- ---------------------------------------------------------------------------
CREATE TABLE sync_watermarks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  last_sheet_no VARCHAR(50),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_sync_watermarks_single_row CHECK (id = '00000000-0000-0000-0000-000000000001')
);

-- ---------------------------------------------------------------------------
-- 2. sync_runs — per-cycle run records
-- ---------------------------------------------------------------------------
CREATE TABLE sync_runs (
  id UUID PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL,
  rows_read INT NOT NULL DEFAULT 0,
  rows_upserted INT NOT NULL DEFAULT 0,
  error_message TEXT,
  CONSTRAINT ck_sync_runs_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_sync_runs_started_at ON sync_runs(started_at);

-- ---------------------------------------------------------------------------
-- 3. Audit: add SYNC_RUN to the entity_type CHECK (drop/re-add pattern)
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
                         'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
                         'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT',
                         'SPAREPART_REQUEST', 'DEPARTMENT', 'DEPARTMENT_MEMBER', 'USER',
                         'SPAREPART_STOCK', 'SYNC_RUN'));
