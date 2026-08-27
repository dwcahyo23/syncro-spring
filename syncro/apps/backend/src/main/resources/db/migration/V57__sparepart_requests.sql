-- Story 12-1: sparepart request creation & types (FR-140/FR-143/FR-144, AD-9). Additive on V56.
-- sparepart_requests — one row per part requested against a workorder (SERVICE_EXTERNAL) or
-- standalone (CONSUMABLE/SPAREPART). request_type enforces FR-140 rules at the service layer
-- (SERVICE_EXTERNAL requires a workorder; CONSUMABLE has no machine binding; SPAREPART uses the
-- electric/mechanic taxonomy). material_code is a denormalized snapshot for the storekeeper
-- display; the authoritative code lives on spareparts.material_code. A request without a known
-- material code starts PENDING_COMPLETION for inventory to finish (FR-144). The full state
-- machine (ACKED/PROCESSING/READY/...) arrives in 12-2 and re-adds the status CHECK.

CREATE TABLE sparepart_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_type VARCHAR(20) NOT NULL,
  work_order_id VARCHAR(50),
  machine_id UUID,
  sparepart_id UUID,
  material_code VARCHAR(64),
  quantity SMALLINT NOT NULL,
  est_price_id UUID,
  est_unit_price NUMERIC(18,2),
  purchase_reference_url VARCHAR(2048),
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_sparepart_requests_work_order FOREIGN KEY (work_order_id)
    REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_sparepart_requests_machine FOREIGN KEY (machine_id)
    REFERENCES machines(id) ON DELETE SET NULL,
  CONSTRAINT fk_sparepart_requests_sparepart FOREIGN KEY (sparepart_id)
    REFERENCES spareparts(id) ON DELETE SET NULL,
  CONSTRAINT fk_sparepart_requests_price_entry FOREIGN KEY (est_price_id)
    REFERENCES sparepart_price_entries(id) ON DELETE SET NULL,
  CONSTRAINT ck_sparepart_requests_type CHECK (request_type IN ('SPAREPART','CONSUMABLE','SERVICE_EXTERNAL')),
  CONSTRAINT ck_sparepart_requests_status CHECK (status IN ('REQUESTED','PENDING_COMPLETION')),
  CONSTRAINT ck_sparepart_requests_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_sparepart_requests_work_order ON sparepart_requests(work_order_id);
CREATE INDEX idx_sparepart_requests_status ON sparepart_requests(status);
CREATE INDEX idx_sparepart_requests_machine ON sparepart_requests(machine_id);

-- Audit: add SPAREPART_REQUEST to the entity_type CHECK (drop/re-add pattern, preserve all types).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
                         'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
                         'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT',
                         'SPAREPART_REQUEST'));
