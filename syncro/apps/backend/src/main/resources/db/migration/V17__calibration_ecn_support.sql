-- ============================================================================
-- V17: calibration + ECN support columns + compliance audit entity types
-- (story 21-2, blueprint H3/H4). Additive-only migration:
--   1. calibration_instruments gains plant_id (nullable — a null plant marks a
--      global instrument visible to every authenticated user, the NC-unlinked
--      precedent) with an FK to plants, and the optimistic-lock version column
--      (V13/V15 precedent: compliance records must not silently lose
--      concurrent updates).
--   2. equipment_change_notices gains the same version column.
--   3. ck_audit_log_entity_type is extended with CALIBRATION_INSTRUMENT,
--      CALIBRATION_RECORD and EQUIPMENT_CHANGE_NOTICE that the calibration and
--      ECN services write when mutations land (the enum doc requires the Java
--      enum and the SQL CHECK to move together in one change — V6..V14
--      drop-and-recreate pattern, preserving every prior value).
--   4. Indexes for the instrument scope filter (plant_id is the primary
--      findScoped predicate) and the due-date ordering.
-- plant_id is deliberately NOT backfilled: under the development-phase reset
-- stance (AD-22) every existing row is a global instrument by design, and the
-- seed/reseed owns any plant assignment.
-- ============================================================================

ALTER TABLE calibration_instruments
  ADD COLUMN plant_id UUID;

ALTER TABLE calibration_instruments
  ADD CONSTRAINT fk_calibration_instruments_plant FOREIGN KEY (plant_id)
    REFERENCES plants(id) ON DELETE SET NULL;

ALTER TABLE calibration_instruments
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE equipment_change_notices
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE INDEX idx_calibration_instruments_plant ON calibration_instruments(plant_id);
CREATE INDEX idx_calibration_instruments_next_date
  ON calibration_instruments(next_calibration_date);

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
     'CALIBRATION_INSTRUMENT', 'CALIBRATION_RECORD', 'EQUIPMENT_CHANGE_NOTICE'));
