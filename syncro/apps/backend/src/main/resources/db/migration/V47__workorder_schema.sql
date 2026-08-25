-- Story 10-1: workorder schema & categories foundation (AD-3/AD-4).
-- Four tables: dual-source work orders with a self-parent link, global category
-- master data, transition history (source + traceId), and per-prefix ID sequences
-- for the concurrency-safe WO-YYMM-XXXXX generator.
CREATE TABLE work_order_categories (
  id UUID PRIMARY KEY,
  code VARCHAR(16) NOT NULL,
  label VARCHAR(100) NOT NULL,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_work_order_categories_code UNIQUE (code)
);

CREATE TABLE work_orders (
  id VARCHAR(50) PRIMARY KEY,
  source VARCHAR(8) NOT NULL,
  parent_id VARCHAR(50),
  status VARCHAR(20) NOT NULL,
  category_id UUID,
  machine_id UUID NOT NULL,
  description TEXT,
  sync_version BIGINT NOT NULL DEFAULT 0,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_work_orders_parent FOREIGN KEY (parent_id) REFERENCES work_orders(id),
  CONSTRAINT fk_work_orders_category FOREIGN KEY (category_id) REFERENCES work_order_categories(id),
  CONSTRAINT fk_work_orders_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
  CONSTRAINT ck_work_orders_source CHECK (source IN ('SYNCED', 'INTERNAL')),
  CONSTRAINT ck_work_orders_status CHECK (status IN ('DRAFT', 'OPEN', 'ASSIGNED', 'IN_PROGRESS',
                                                     'ON_PROCUREMENT', 'DONE', 'CLOSED', 'CANCELLED'))
);

CREATE INDEX idx_work_orders_parent_id ON work_orders(parent_id);
CREATE INDEX idx_work_orders_machine_id ON work_orders(machine_id);
CREATE INDEX idx_work_orders_category_id ON work_orders(category_id);

CREATE TABLE work_order_status_history (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  source VARCHAR(8) NOT NULL,
  actor VARCHAR(50),
  trace_id VARCHAR(36),
  transitioned_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_work_order_status_history_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT ck_work_order_status_history_source CHECK (source IN ('MANUAL', 'DERIVED', 'SYNC'))
);

CREATE INDEX idx_work_order_status_history_work_order_id ON work_order_status_history(work_order_id);

CREATE TABLE workorder_id_sequences (
  prefix VARCHAR(6) PRIMARY KEY,
  last_seq INTEGER NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Audit: add WORK_ORDER_CATEGORY to the entity_type CHECK (V31/V42/V46 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY'));
