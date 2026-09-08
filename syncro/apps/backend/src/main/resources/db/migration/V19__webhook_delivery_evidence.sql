-- ============================================================================
-- V19: webhook delivery evidence columns + WEBHOOK_CONFIG audit type
-- (story 22-1, blueprint I4). Additive-only migration:
--   1. webhook_delivery_logs gains the evidence columns the story AC requires:
--      trace_id (copied from the source event's traceId), max_attempts
--      (per-row retry budget, default 3), idempotency_key (unique —
--      configId:eventType:eventKey, the duplicate-enqueue guard) and the
--      optimistic-lock version column (V13/V15/V17/V18 precedent). The
--      idempotency_key column is nullable so pre-existing rows (none today,
--      forward-only posture) stay valid; Postgres UNIQUE is NULLS DISTINCT.
--   2. webhook_configs gains the version column for @Version optimistic
--      locking on config mutations.
--   3. ck_audit_log_entity_type is extended with WEBHOOK_CONFIG that
--      WebhookConfigService writes when config mutations land (the enum doc
--      requires the Java enum and the SQL CHECK to move together in one
--      change — V6..V18 drop-and-recreate pattern, preserving every prior
--      value including 21-2/21-3's five compliance types and the
--      PHONE_VERIFICATION_CHALLENGE value already present since V14).
-- ============================================================================

ALTER TABLE webhook_delivery_logs
  ADD COLUMN trace_id VARCHAR(64);

ALTER TABLE webhook_delivery_logs
  ADD COLUMN max_attempts INT NOT NULL DEFAULT 3;

ALTER TABLE webhook_delivery_logs
  ADD COLUMN idempotency_key VARCHAR(255);

ALTER TABLE webhook_delivery_logs
  ADD CONSTRAINT uq_webhook_delivery_logs_idempotency_key UNIQUE (idempotency_key);

ALTER TABLE webhook_delivery_logs
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE webhook_configs
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
     'NON_CONFORMANCE', 'EIGHT_D_REPORT',
     'PHONE_VERIFICATION_CHALLENGE',
     'CALIBRATION_INSTRUMENT', 'CALIBRATION_RECORD', 'EQUIPMENT_CHANGE_NOTICE',
     'MACHINE_SETUP_BASELINE', 'LESSON_LEARNED',
     'WEBHOOK_CONFIG'));
