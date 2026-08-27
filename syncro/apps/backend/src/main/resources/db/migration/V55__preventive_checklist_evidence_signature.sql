-- Story 11-2: preventive checklist, assessment & signature (FR-132, AD-10). Additive on V54.
-- One checklist result per schedule (uq_preventive_checklist_schedule); items carry
-- assessment values with optional LSL/USL bounds; approval fills leader_id/assessment/
-- approved_at/signature_object_key/signer_identity (Garage object key only — bytes live
-- in Garage under "preventive/{scheduleId}/{attachmentId}/{uuid}.{ext}"). Evidence rows
-- mirror workorder_attachments exactly (story 10-5): PostgreSQL stores only the object key.

CREATE TABLE preventive_checklist_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schedule_id UUID NOT NULL,
  performed_by UUID NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  notes TEXT,
  leader_id UUID,
  assessment TEXT,
  approved_at TIMESTAMPTZ,
  signature_object_key VARCHAR(512),
  signer_identity VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_checklist_results_schedule FOREIGN KEY (schedule_id)
    REFERENCES preventive_schedules(id) ON DELETE CASCADE,
  CONSTRAINT uq_preventive_checklist_schedule UNIQUE (schedule_id)
);

CREATE INDEX idx_preventive_checklist_schedule ON preventive_checklist_results(schedule_id);

CREATE TABLE preventive_checklist_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  result_id UUID NOT NULL,
  position SMALLINT NOT NULL,
  label VARCHAR(200) NOT NULL,
  value TEXT,
  lsl NUMERIC,
  usl NUMERIC,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_checklist_items_result FOREIGN KEY (result_id)
    REFERENCES preventive_checklist_results(id) ON DELETE CASCADE
);

CREATE INDEX idx_preventive_checklist_items_result ON preventive_checklist_items(result_id);

CREATE TABLE preventive_schedule_attachments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schedule_id UUID NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploaded_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NULL,
  CONSTRAINT fk_preventive_schedule_attachments_schedule FOREIGN KEY (schedule_id)
    REFERENCES preventive_schedules(id) ON DELETE CASCADE,
  CONSTRAINT ck_preventive_schedule_attachments_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_preventive_schedule_attachments_schedule ON preventive_schedule_attachments(schedule_id);

-- Audit: add PREVENTIVE_CHECKLIST + PREVENTIVE_ATTACHMENT to the entity_type CHECK
-- (V47..V54 drop/re-add pattern; preserve every existing type).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
                         'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
                         'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT'));
