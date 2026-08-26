-- Story 10-2: workorder create & assign. Additive on V47.
-- create (FR-110) needs an Idempotency-Key column for the 5-minute dedupe window
-- (AD-3); assign (FR-113) needs the executing technician. No data backfill, no
-- drops: both columns are nullable.
-- The idempotency key is UNIQUE (partial, non-null) so two concurrent POSTs with the
-- same key cannot both insert — the loser hits a duplicate-key violation and the
-- service returns the winner's row (AD-3 concurrency guarantee).
ALTER TABLE work_orders ADD COLUMN idempotency_key VARCHAR(64);
ALTER TABLE work_orders ADD COLUMN assigned_technician_id UUID;

CREATE UNIQUE INDEX uq_work_orders_idempotency_key
  ON work_orders(idempotency_key) WHERE idempotency_key IS NOT NULL;

-- Audit: add WORK_ORDER to the entity_type CHECK (V47 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER'));
