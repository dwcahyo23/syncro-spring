-- Story 11-1: preventive programs & schedules (FR-130/FR-131, AD-12). Additive on V53.
-- preventive_programs — a per-machine recurring maintenance definition. Category is
-- mechanical/electrical; schedule type is limited to MONTHLY or ANNUAL (FR-130, no
-- daily/weekly/hourly for now). day_of_month is the monthly/annual anchor day
-- (clamped to the target month's day count at generation time); month_of_year is
-- required for ANNUAL and forbidden for MONTHLY. Works entirely without telemetry.
-- preventive_schedules — materialized due instances. Generation is idempotent per
-- (program_id, due_date) via a unique constraint; the next due date rolls forward from
-- completion (floating interval, AD-12), never from the original anchor.

CREATE TABLE preventive_programs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL,
  category VARCHAR(20) NOT NULL,
  schedule_type VARCHAR(10) NOT NULL,
  day_of_month SMALLINT NOT NULL,
  month_of_year SMALLINT,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_programs_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
  CONSTRAINT ck_preventive_programs_category CHECK (category IN ('MECHANICAL','ELECTRICAL')),
  CONSTRAINT ck_preventive_programs_type CHECK (schedule_type IN ('MONTHLY','ANNUAL')),
  CONSTRAINT ck_preventive_programs_day CHECK (day_of_month BETWEEN 1 AND 31),
  CONSTRAINT ck_preventive_programs_month CHECK (month_of_year IS NULL OR month_of_year BETWEEN 1 AND 12)
);

CREATE INDEX idx_preventive_programs_machine ON preventive_programs(machine_id);

CREATE TABLE preventive_schedules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  program_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  due_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  completed_at TIMESTAMPTZ,
  performed_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_schedules_program FOREIGN KEY (program_id)
    REFERENCES preventive_programs(id) ON DELETE CASCADE,
  CONSTRAINT ck_preventive_schedules_status CHECK (status IN ('SCHEDULED','IN_PROGRESS','PERFORMED','SKIPPED')),
  CONSTRAINT uq_preventive_schedules_period UNIQUE (program_id, due_date)
);

CREATE INDEX idx_preventive_schedules_status_due ON preventive_schedules(status, due_date);
CREATE INDEX idx_preventive_schedules_machine ON preventive_schedules(machine_id);

-- Audit: add PREVENTIVE_PROGRAM + PREVENTIVE_SCHEDULE to the entity_type CHECK
-- (V47/V48/V49/V50/V52/V53 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
                         'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE'));
