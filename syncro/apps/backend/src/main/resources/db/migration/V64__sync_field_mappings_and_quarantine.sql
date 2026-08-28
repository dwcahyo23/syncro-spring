-- Story 13-2: Conflict resolution & field mapping (AD-8/FR-152/NFR-P2-9).
-- Additive on V63.
--
-- 1. sync_field_mappings — field classification configuration: which external fields
--    sync may overwrite (MASTER) versus which are locally-owned and always preserved
--    (OPERATIONAL). Unmapped fields default to MASTER (AD-8). Seeded with the known
--    field set from the intent contract.
-- 2. sync_quarantine — append-only evidence for rows rejected by sync protection
--    gates (terminal-state, ON_PROCUREMENT, parent-closed). Shared with story 13-3.
-- 3. Add SYNC_QUARANTINE to the audit_log entity_type CHECK (drop/re-add pattern).

-- ---------------------------------------------------------------------------
-- 1. sync_field_mappings — MASTER/OPERATIONAL classification
-- ---------------------------------------------------------------------------
CREATE TABLE sync_field_mappings (
  field_name VARCHAR(64) PRIMARY KEY,
  domain VARCHAR(16) NOT NULL,
  CONSTRAINT ck_sync_field_mappings_domain CHECK (domain IN ('MASTER', 'OPERATIONAL'))
);

-- Seed defaults (AD-8): the external master fields sync may overwrite, and the
-- locally-owned operational fields sync must never touch. Unmapped fields default
-- to MASTER at runtime, so this table is the full declared set.
INSERT INTO sync_field_mappings (field_name, domain) VALUES
  ('status', 'MASTER'),
  ('machine_id', 'MASTER'),
  ('category_id', 'MASTER'),
  ('parent_id', 'MASTER'),
  ('description', 'MASTER'),
  ('sync_version', 'MASTER'),
  ('report_chronological', 'OPERATIONAL'),
  ('report_analyze', 'OPERATIONAL'),
  ('report_corrective', 'OPERATIONAL'),
  ('report_preventive', 'OPERATIONAL'),
  ('cp_cp_lower', 'OPERATIONAL'),
  ('cp_cp_upper', 'OPERATIONAL'),
  ('cpk', 'OPERATIONAL'),
  ('cpk_pdf_object_key', 'OPERATIONAL'),
  ('fmea_failure_type', 'OPERATIONAL'),
  ('stop_time_reason', 'OPERATIONAL'),
  ('stop_time_detail', 'OPERATIONAL'),
  ('mttr_minutes', 'OPERATIONAL'),
  ('response_time_minutes', 'OPERATIONAL'),
  ('done_reason', 'OPERATIONAL');

-- ---------------------------------------------------------------------------
-- 2. sync_quarantine — append-only rejection evidence (shared with story 13-3)
-- ---------------------------------------------------------------------------
CREATE TABLE sync_quarantine (
  id UUID PRIMARY KEY,
  sheet_no VARCHAR(50),
  reason VARCHAR(64) NOT NULL,
  raw_payload JSONB,
  trace_id VARCHAR(36),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sync_quarantine_sheet_no ON sync_quarantine(sheet_no);

-- ---------------------------------------------------------------------------
-- 3. Audit: add SYNC_QUARANTINE to the entity_type CHECK (drop/re-add pattern)
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
                         'SPAREPART_STOCK', 'SYNC_RUN', 'SYNC_QUARANTINE'));
