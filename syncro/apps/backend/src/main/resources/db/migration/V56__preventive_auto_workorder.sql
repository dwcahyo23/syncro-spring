-- Story 11-3: preventive report & auto-workorder (FR-133/FR-134, AD-12). Additive on V55.
-- preventive_programs.auto_workorder — when true, an approved (PERFORMED) schedule of this
-- program auto-creates an internal preventive workorder (category 02) linked back to the
-- schedule. work_orders.preventive_schedule_id — nullable FK so only auto-generated
-- workorders carry it; ON DELETE SET NULL so deleting a schedule never blocks a workorder.
-- The unique index is the idempotency backstop: one workorder per schedule period, even
-- under concurrent approval. Category 02 (Preventive) is seeded so the auto-workorder can
-- resolve it at runtime (ON CONFLICT keeps it idempotent across environments).

ALTER TABLE preventive_programs ADD COLUMN auto_workorder BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE work_orders ADD COLUMN preventive_schedule_id UUID NULL;
ALTER TABLE work_orders ADD CONSTRAINT fk_work_orders_preventive_schedule
  FOREIGN KEY (preventive_schedule_id) REFERENCES preventive_schedules(id) ON DELETE SET NULL;

CREATE UNIQUE INDEX uq_work_orders_preventive_schedule ON work_orders(preventive_schedule_id)
  WHERE preventive_schedule_id IS NOT NULL;
CREATE INDEX idx_work_orders_preventive_schedule ON work_orders(preventive_schedule_id);

INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at)
VALUES (gen_random_uuid(), '02', 'Preventive', NULL, NOW(), NOW())
ON CONFLICT (code) DO NOTHING;
