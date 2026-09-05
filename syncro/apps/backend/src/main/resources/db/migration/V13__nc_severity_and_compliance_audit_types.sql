-- ============================================================================
-- V13: NC severity + compliance audit entity types (story 21-1, blueprint H1/H2).
-- Additive-only migration:
--   1. non_conformances gains the severity column the story AC requires
--      (uppercase enum MINOR/MAJOR/CRITICAL, nullable — existing rows and
--      creates without an explicit severity stay NULL).
--   2. ck_audit_log_entity_type is extended with NON_CONFORMANCE +
--      EIGHT_D_REPORT that AuditEntityType writes when compliance mutations
--      land (the enum doc requires the Java enum and the SQL CHECK to move
--      together in one change — V6..V12 drop-and-recreate pattern, preserving
--      every prior value).
--   3. non_conformances/eight_d_reports gain the optimistic-lock version column
--      (review 21-1 P6: compliance records must not silently lose concurrent
--      updates — InventoryStockBalanceEntity/SectionEntity precedent).
-- ============================================================================

ALTER TABLE non_conformances
  ADD COLUMN severity VARCHAR(16);

ALTER TABLE non_conformances
  ADD CONSTRAINT ck_non_conformances_severity CHECK (severity IS NULL
    OR severity IN ('MINOR', 'MAJOR', 'CRITICAL'));

ALTER TABLE non_conformances
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE eight_d_reports
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

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
     'NON_CONFORMANCE', 'EIGHT_D_REPORT'));
