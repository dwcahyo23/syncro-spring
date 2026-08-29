-- Story 14-4: WAHA Notifications & 4-Hour Acknowledgment (FR-180/FR-181).
-- Additive on V66.
--
-- 1. Create workorder_acks table for tracking 4-hour ack records.
-- 2. Seed WORKORDER scope ACK_WAITING step in escalation_configs (480 min default).

-- ---------------------------------------------------------------------------
-- 1. workorder_acks — ack record per workorder (4-hour acknowledgment)
-- ---------------------------------------------------------------------------
CREATE TABLE workorder_acks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_order_id VARCHAR(50) NOT NULL,
  acknowledged_by UUID NOT NULL,
  acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_workorder_acks_work_order_id ON workorder_acks(work_order_id);

-- ---------------------------------------------------------------------------
-- 2. Seed WORKORDER scope ACK_WAITING step (480 min default, configurable)
-- ---------------------------------------------------------------------------
INSERT INTO escalation_configs (scope, step, min_cost, max_cost, duration_minutes, approval_role)
VALUES ('WORKORDER', 'ACK_WAITING', NULL, NULL, 480, NULL)
ON CONFLICT (scope, step) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 3. Seed default workorder_lifecycle + workorder_ack WAHA templates
--    (idempotent: ON CONFLICT DO NOTHING keeps an edited template)
-- ---------------------------------------------------------------------------
INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'workorder_lifecycle',
  'PERUBAHAN STATUS WORKORDER - {machineCode}

WO: {workOrderId}
Status: {status}
Kejadian: {eventLabel}
Waktu: {transitionedAt}

Silakan lakukan tindakan lanjutan.',
  now(), now()
)
ON CONFLICT (template_key) DO NOTHING;

INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'workorder_ack',
  'PERINGATAN AKUISISI - WORKORDER {workOrderId}

Mesin: {machineCode}
Deadline konfirmasi: {ackDeadline}

Klik link berikut untuk konfirmasi:
{ackLink}',
  now(), now()
)
ON CONFLICT (template_key) DO NOTHING;