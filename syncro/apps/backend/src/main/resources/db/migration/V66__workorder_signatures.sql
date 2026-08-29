-- Story 14-3: workorder signatures & logo (FR-175). Additive on V65.
--
-- 1. workorder_signatures — one per workorder (unique constraint), bound at DONE/CLOSED.
--    Signature image bytes live in Garage under "workorders/{woId}/signature/{uuid}.{ext}";
--    PostgreSQL stores only the object key and signer metadata.
-- 2. settings — single-row company settings; stores only the current logo Garage object
--    key (bytes live in Garage under "settings/logo/{uuid}.{ext}"). The spec allows a
--    settings table as the config store; it rides on this migration so no separate
--    migration is needed.

CREATE TABLE workorder_signatures (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_order_id VARCHAR(50) NOT NULL,
  signature_object_key VARCHAR(512) NOT NULL,
  signer_identity VARCHAR(200) NOT NULL,
  signed_by UUID NOT NULL,
  signed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_workorder_signatures_work_order FOREIGN KEY (work_order_id)
    REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT uq_workorder_signatures_work_order UNIQUE (work_order_id)
);

-- No separate index on work_order_id: the unique constraint already creates one.

-- Single-row settings: exactly one row, no generated id — the singleton key '1' keeps
-- the "one settings row" invariant in SQL.
CREATE TABLE settings (
  singleton_key SMALLINT PRIMARY KEY DEFAULT 1 CHECK (singleton_key = 1),
  logo_object_key VARCHAR(512),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO settings (singleton_key) VALUES (1) ON CONFLICT DO NOTHING;

-- Audit: add WORKORDER_SIGNATURE to the entity_type CHECK (drop/re-add pattern,
-- preserve every existing type from V64).
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
                         'SPAREPART_STOCK', 'SYNC_RUN', 'SYNC_QUARANTINE',
                         'WORKORDER_SIGNATURE'));