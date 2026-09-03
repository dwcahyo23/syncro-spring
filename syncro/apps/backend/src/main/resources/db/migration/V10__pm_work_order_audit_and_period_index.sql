-- ============================================================================
-- V10: PM work order audit entity type + period idempotency index (story 19-4,
-- blueprint F6). Additive-only migration:
--   (a) extends the ck_audit_log_entity_type CHECK with PM_WORK_ORDER that
--       AuditEntityType.PM_WORK_ORDER writes (the doc comment on that enum
--       requires the Java enum and the SQL CHECK to move together in one
--       change — V6..V9 drop-and-recreate pattern, preserving every prior
--       value);
--   (b) creates the partial unique index uq_pm_work_orders_period on
--       pm_work_orders(machine_id, template_id, scheduled_date) — the race-safe
--       idempotency backstop for workorder generation per schedule period
--       (FR-134). Partial because template_id/scheduled_date are nullable in
--       V1 and only fully-specified generated rows must be unique.
-- No schema changes to pm_work_orders — the table already carries every F6
-- column in V1.
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
     'PM_WORK_ORDER'));

CREATE UNIQUE INDEX uq_pm_work_orders_period ON pm_work_orders(machine_id, template_id, scheduled_date)
  WHERE template_id IS NOT NULL AND scheduled_date IS NOT NULL;
