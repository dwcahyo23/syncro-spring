-- ============================================================================
-- V8: PM checklist audit entity types (story 19-2, blueprint F4)
-- Additive-only migration extending the ck_audit_log_entity_type CHECK with
-- PM_CHECKLIST_CATEGORY and PM_CHECKLIST_ITEM that
-- AuditEntityType.PM_CHECKLIST_CATEGORY / AuditEntityType.PM_CHECKLIST_ITEM
-- write (the doc comment on that enum requires the Java enum and the SQL CHECK
-- to move together in one change). No schema changes to pm_checklist_categories
-- / pm_checklist_items — both tables already carry every F4 column in V1.
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
     'PM_CHECKLIST_CATEGORY', 'PM_CHECKLIST_ITEM'));
