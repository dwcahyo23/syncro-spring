-- ============================================================================
-- V12: KPI audit entity type (story 20-1, blueprint G1, AD-20).
-- Additive-only migration: extends the ck_audit_log_entity_type CHECK with
-- KPI_TARGET that AuditEntityType.KPI_TARGET writes when a KPI target is
-- created or updated (the enum doc requires the Java enum and the SQL CHECK
-- to move together in one change — V6..V11 drop-and-recreate pattern,
-- preserving every prior value).
-- No schema changes to the kpi_* tables — all eight already exist in V1.
-- ============================================================================

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
     'KPI_TARGET'));
