-- ============================================================================
-- V18: setup baseline + lesson-learned event links, evidence, optimistic locks
-- and compliance audit entity types (story 21-3, blueprint H5/H6). Additive-only
-- migration:
--   1. lesson_learned gains the optional event-link columns nc_id / eight_d_id /
--      work_order_id (FK ON DELETE SET NULL — deleting the source event keeps the
--      lesson, matching the executed_wo_id precedent) plus an evidence JSONB array
--      of Garage {objectKey, filename} references (8D pdfArtifactUrl passthrough
--      posture — no attachment table) and the optimistic-lock version column
--      (V13/V15/V17 precedent).
--   2. machine_setup_baselines gains lock_version: the table's existing version
--      column is the server-assigned BUSINESS version (part of
--      uq_machine_setup_baselines_machine_ecn_version), so the JPA @Version
--      optimistic lock needs its own column (the spec's Design Notes treat the
--      unique constraint and @Version as two distinct write guards). Two partial
--      unique indexes close the review-21-3 H1/H2 gaps: Postgres UNIQUE is
--      NULLS DISTINCT, so the old (machine_id, ecn_id, version) constraint never
--      fires for concurrent no-ECN creates with the same max+1 —
--      uq_machine_setup_baselines_machine_version guards exactly that case;
--      uq_one_active_baseline_per_machine DB-enforces "exactly one active
--      baseline per machine" (the service's supersede loop deactivates siblings
--      before inserting, so the index only catches races).
--   3. ck_audit_log_entity_type is extended with MACHINE_SETUP_BASELINE and
--      LESSON_LEARNED that the baseline/lesson services write when mutations land
--      (the enum doc requires the Java enum and the SQL CHECK to move together in
--      one change — V6..V17 drop-and-recreate pattern, preserving every prior
--      value including 21-2's CALIBRATION_INSTRUMENT/CALIBRATION_RECORD/
--      EQUIPMENT_CHANGE_NOTICE).
-- ============================================================================

ALTER TABLE lesson_learned
  ADD COLUMN nc_id UUID;

ALTER TABLE lesson_learned
  ADD COLUMN eight_d_id UUID;

ALTER TABLE lesson_learned
  ADD COLUMN work_order_id VARCHAR(50);

ALTER TABLE lesson_learned
  ADD COLUMN evidence JSONB;

ALTER TABLE lesson_learned
  ADD CONSTRAINT fk_lesson_learned_nc FOREIGN KEY (nc_id)
    REFERENCES non_conformances(id) ON DELETE SET NULL;

ALTER TABLE lesson_learned
  ADD CONSTRAINT fk_lesson_learned_eight_d FOREIGN KEY (eight_d_id)
    REFERENCES eight_d_reports(id) ON DELETE SET NULL;

ALTER TABLE lesson_learned
  ADD CONSTRAINT fk_lesson_learned_work_order FOREIGN KEY (work_order_id)
    REFERENCES work_orders(id) ON DELETE SET NULL;

ALTER TABLE lesson_learned
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE machine_setup_baselines
  ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0;

-- Review 21-3 H1: NULLS DISTINCT makes the old (machine_id, ecn_id, version) uq
-- useless for concurrent no-ECN creates — this partial index is the real guard.
CREATE UNIQUE INDEX uq_machine_setup_baselines_machine_version
  ON machine_setup_baselines (machine_id, version) WHERE ecn_id IS NULL;

-- Review 21-3 H2: DB-enforced single active baseline per machine (cold-start race).
CREATE UNIQUE INDEX uq_one_active_baseline_per_machine
  ON machine_setup_baselines (machine_id) WHERE is_active;

ALTER TABLE audit_log
  DROP CONSTRAINT ck_audit_log_entity_type;

ALTER TABLE audit_log
  ADD CONSTRAINT ck_audit_log_entity_type CHECK (entity_type IN
    ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
     'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
     'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
     'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
     'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
     'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
     'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT',
     'SPAREPART_REQUEST', 'DEPARTMENT', 'DEPARTMENT_USER', 'USER',
     'INVENTORY_LOCATION', 'INVENTORY_STOCK_BALANCE',
     'INVENTORY_TRANSFER', 'INVENTORY_RESERVATION',
     'MACHINE_AREA', 'JOB_TITLE', 'SYSTEM_ROLE', 'ROLE_PERMISSION_MAPPING',
     'MENU_FEATURE', 'DOMAIN_CONTEXT', 'USER_JOB_BINDING', 'USER_ROLE_BINDING',
     'PLANT_WORKING_CALENDAR', 'SIGNATURE_USE',
     'SPAREPART_STOCK', 'SYNC_RUN', 'SYNC_QUARANTINE', 'WORKORDER_SIGNATURE',
     'WORK_ASSIGNMENT', 'WORK_LOG', 'WORK_LOG_RATING',
     'WORK_ORDER_QUALITY_RATING',
     'PM_FREQUENCY', 'PM_CHECKSHEET',
     'PM_CHECKLIST_CATEGORY', 'PM_CHECKLIST_ITEM',
     'PM_SCHEDULE', 'PM_SCHEDULE_DATE',
     'PM_WORK_ORDER',
     'PM_EXECUTION', 'PM_EXECUTION_ITEM',
     'KPI_TARGET',
     'NON_CONFORMANCE', 'EIGHT_D_REPORT',
     'PHONE_VERIFICATION_CHALLENGE',
     'CALIBRATION_INSTRUMENT', 'CALIBRATION_RECORD', 'EQUIPMENT_CHANGE_NOTICE',
     'MACHINE_SETUP_BASELINE', 'LESSON_LEARNED'));
