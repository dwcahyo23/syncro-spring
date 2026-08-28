-- Story 12-3: Approval, Separation of Duty & Escalation (FR-142/FR-147).
-- Additive on V59.
--
-- 1. Create escalation_configs table with unique (scope, step) constraint.
-- 2. Seed SPAREPART_REQUEST rows: 3 approval tiers + 3 escalation durations.
-- 3. Add message_body column to notification_jobs (for sparepart request escalation).

-- ---------------------------------------------------------------------------
-- 1. escalation_configs — configurable approval tiers and escalation durations
-- ---------------------------------------------------------------------------
CREATE TABLE escalation_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  scope VARCHAR(20) NOT NULL CHECK (scope IN ('SPAREPART_REQUEST','WORKORDER')),
  step VARCHAR(30) NOT NULL,
  min_cost NUMERIC(18,2),
  max_cost NUMERIC(18,2),
  duration_minutes INTEGER NOT NULL,
  approval_role VARCHAR(30),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE escalation_configs ADD CONSTRAINT uq_escalation_configs_scope_step UNIQUE (scope, step);

-- ---------------------------------------------------------------------------
-- 2. Seed SPAREPART_REQUEST rows
-- ---------------------------------------------------------------------------

-- Approval tiers (no cost = unbounded)
INSERT INTO escalation_configs (scope, step, min_cost, max_cost, duration_minutes, approval_role)
VALUES
  ('SPAREPART_REQUEST', 'SECTION_LEADER_APPROVAL',    NULL,          5000000,     0, 'SECTION_LEADER'),
  ('SPAREPART_REQUEST', 'MAINTENANCE_LEADER_APPROVAL', 5000000,      50000000,    0, 'MAINTENANCE_LEADER'),
  ('SPAREPART_REQUEST', 'MANAGER_APPROVAL',            50000000,     NULL,        0, 'MANAGER_MAINTENANCE');

-- Escalation durations (seeded defaults pending PRD OQ-2 confirmation; configurable)
INSERT INTO escalation_configs (scope, step, min_cost, max_cost, duration_minutes, approval_role)
VALUES
  ('SPAREPART_REQUEST', 'ACK_WAITING',      NULL, NULL,  480, NULL),
  ('SPAREPART_REQUEST', 'PROCESS_WAITING',  NULL, NULL, 1440, NULL),
  ('SPAREPART_REQUEST', 'PURCHASE_WAITING', NULL, NULL, 2880, NULL);

-- ---------------------------------------------------------------------------
-- 3. Add message_body to notification_jobs (additive, nullable)
-- ---------------------------------------------------------------------------
ALTER TABLE notification_jobs ADD COLUMN message_body TEXT;