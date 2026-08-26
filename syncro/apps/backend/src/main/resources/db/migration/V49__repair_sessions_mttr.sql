-- Story 10-4: repair sessions & MTTR. Additive on V47/V48.
-- repair_sessions records non-overlapping time intervals a technician (or in-scope
-- leader) logs against an IN_PROGRESS workorder. Non-overlap (including at most one
-- open/unbounded session per workorder) is a hard DB invariant via a gist EXCLUDE
-- constraint on (work_order_id, tstzrange(started_at, ended_at)) — two unbounded
-- ranges always overlap, so a second open session is rejected too. The btree_gist
-- extension makes the equality operator (=) on work_order_id available inside the
-- gist index.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE repair_sessions (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  technician_id UUID NOT NULL,
  description VARCHAR(2000),
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ NULL,
  duration_minutes BIGINT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT fk_repair_sessions_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT excl_repair_sessions_no_overlap EXCLUDE USING gist (
    work_order_id WITH =,
    tstzrange(started_at, ended_at) WITH &&
  ),
  CONSTRAINT ck_repair_sessions_end_after_start CHECK (ended_at IS NULL OR ended_at > started_at),
  CONSTRAINT ck_repair_sessions_duration CHECK (ended_at IS NULL OR duration_minutes IS NOT NULL AND duration_minutes >= 0)
);

CREATE INDEX idx_repair_sessions_work_order_id ON repair_sessions(work_order_id);

-- MTTR (cumulative completed session durations), SLA response time (OPEN → first
-- session start) and the DONE-without-session documented reason are persisted on the
-- workorder so 14.2 dashboards can query them without recomputing.
ALTER TABLE work_orders ADD COLUMN mttr_minutes BIGINT NULL;
ALTER TABLE work_orders ADD COLUMN response_time_minutes BIGINT NULL;
ALTER TABLE work_orders ADD COLUMN done_reason VARCHAR(1000) NULL;

-- Category SLA target (FR-123): optional per-category target response time in minutes.
ALTER TABLE work_order_categories ADD COLUMN target_response_minutes INT NULL;

-- Audit: add REPAIR_SESSION to the entity_type CHECK (V47/V48 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION'));
