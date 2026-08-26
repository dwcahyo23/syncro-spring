-- Story 10-5: evidence & technical drawings (AD-10). Additive on V49.
-- workorder_attachments stores object keys only — file bytes live in Garage under
-- "workorders/{workOrderId}/{attachmentId}.{ext}". Multiple attachments per workorder
-- are independent rows (no EXCLUDE constraint needed). uploaded_by is the uploader's
-- auth user id; updated_at stays NULL until a replace (PUT) rewrites the file.
CREATE TABLE workorder_attachments (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploaded_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NULL,
  CONSTRAINT fk_workorder_attachments_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT ck_workorder_attachments_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_workorder_attachments_work_order_id ON workorder_attachments(work_order_id);

-- Audit: add WORKORDER_ATTACHMENT to the entity_type CHECK (V47/V48/V49 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT'));
