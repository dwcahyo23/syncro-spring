-- Story 10-7: workorder todos & kanban (FR-119). Additive on V51.
-- workorder_todos stores per-task tracking items for a workorder. Each todo is a local
-- operational field (AD-3 "preserved") — allowed on both SYNCED and INTERNAL workorders,
-- never touches status or sync_version. The FK has ON DELETE CASCADE so removing a
-- workorder also removes its todos.

CREATE TABLE workorder_todos (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workorder_id VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  assigned_technician_id UUID,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT fk_workorder_todos_workorder FOREIGN KEY (workorder_id) REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT ck_workorder_todos_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','CANCELLED'))
);

CREATE INDEX idx_workorder_todos_workorder_id ON workorder_todos(workorder_id);
CREATE INDEX idx_workorder_todos_assigned_tech ON workorder_todos(assigned_technician_id);

-- Audit: add WORK_ORDER_TODO to the entity_type CHECK (V47/V48/V49/V50 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO'));