-- ============================================================================
-- V7: PM frequencies seed + audit entity type extension (story 19-1, blueprint F1)
-- Additive-only migration: seed MONTHLY/ANNUAL into pm_frequencies (idempotent
-- ON CONFLICT DO NOTHING) and extend ck_audit_log_entity_type with PM_FREQUENCY
-- and PM_CHECKSHEET that AuditEntityType.PM_FREQUENCY / PM_CHECKSHEET write
-- (the doc comment on that enum requires the Java enum and the SQL CHECK to move
-- together in one change).
-- ============================================================================

-- Seed MONTHLY/ANNUAL frequencies (sort_order 1/2).
INSERT INTO pm_frequencies (id, code, name, description, sort_order, is_active, created_at, updated_at)
VALUES
  (gen_random_uuid(), 'MONTHLY', 'Monthly', 'Monthly preventive maintenance', 1, TRUE, NOW(), NOW()),
  (gen_random_uuid(), 'ANNUAL', 'Annual', 'Annual preventive maintenance', 2, TRUE, NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

-- Extend the audit_log entity_type CHECK additively (V6 pattern).
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
     'PM_FREQUENCY', 'PM_CHECKSHEET'));
