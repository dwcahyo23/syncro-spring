-- ============================================================================
-- V20: whatsapp_message_logs outbound evidence columns (story 22-4, blueprint
-- I5). Additive-only migration — the V1 table is inbound-shaped (waha_message_id
-- NOT NULL UNIQUE, from_phone, received_at) and nothing wrote it; this story
-- extends the SAME table for outbound WAHA sends:
--   1. waha_message_id DROP NOT NULL (widening): outbound rows deliberately do
--      not parse a WAHA message id from the 2xx body — the raw per-attempt
--      response already lives in notification_attempts.response_detail, which
--      the log complements, not replaces. The UNIQUE constraint stays; Postgres
--      UNIQUE is NULLS DISTINCT so many null-id outbound rows coexist.
--   2. received_at DROP NOT NULL + DROP DEFAULT (widening): Design Notes fix
--      "inbound columns (from_phone/received_at/payload) stay null for send
--      rows" — without this the NOT NULL default would stamp every outbound row
--      with an ingest time it never had (and the DEFAULT would keep stamping it
--      on native inserts).
--   3. New outbound columns: direction (INBOUND/OUTBOUND, default OUTBOUND),
--      notification_job_id (FK SET NULL + UNIQUE — the collision-free logical-
--      notification dedupe anchor; the masked phone is lossy so the AC's 4-part
--      identity is stored as queryable columns instead), idempotency_key (raw
--      job key so any template/target derivation is recoverable), target_type,
--      target_id, template_name, recipient_masked (first3+last3 only — the raw
--      phone is never stored here), status (SENT/FAILED), attempt_count,
--      trace_id, text_sha256 (SHA-256 hex reference of the rendered text —
--      never the raw text), sent_at, logged_at (row-creation instant, always
--      set, so never-sent rows still carry a timestamp), version (@Version
--      optimistic lock).
--   4. Indexes on status and trace_id for the SUPER_ADMIN evidence filters, and
--      the composite matching the list ordering (sent_at DESC NULLS LAST,
--      logged_at DESC, id DESC) — the evidence list always orders by it and the
--      table grows one row per send.
-- No CHECK/enum drop-recreate: no new audit entity type is introduced (message
-- logs are system-generated evidence, same posture as 22-1 delivery logs).
-- ============================================================================

ALTER TABLE whatsapp_message_logs
  ALTER COLUMN waha_message_id DROP NOT NULL;

ALTER TABLE whatsapp_message_logs
  ALTER COLUMN received_at DROP NOT NULL;

ALTER TABLE whatsapp_message_logs
  ALTER COLUMN received_at DROP DEFAULT;

ALTER TABLE whatsapp_message_logs
  ADD COLUMN direction VARCHAR(9) NOT NULL DEFAULT 'OUTBOUND';

ALTER TABLE whatsapp_message_logs
  ADD COLUMN notification_job_id UUID;

ALTER TABLE whatsapp_message_logs
  ADD COLUMN idempotency_key VARCHAR(128);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN target_type VARCHAR(32);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN target_id VARCHAR(64);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN template_name VARCHAR(100);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN recipient_masked VARCHAR(64);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN status VARCHAR(16);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN attempt_count INT NOT NULL DEFAULT 0;

ALTER TABLE whatsapp_message_logs
  ADD COLUMN trace_id VARCHAR(64);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN text_sha256 VARCHAR(64);

ALTER TABLE whatsapp_message_logs
  ADD COLUMN sent_at TIMESTAMPTZ;

ALTER TABLE whatsapp_message_logs
  ADD COLUMN logged_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

ALTER TABLE whatsapp_message_logs
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

ALTER TABLE whatsapp_message_logs
  ADD CONSTRAINT fk_whatsapp_message_logs_notification_job
    FOREIGN KEY (notification_job_id) REFERENCES notification_jobs(id) ON DELETE SET NULL;

ALTER TABLE whatsapp_message_logs
  ADD CONSTRAINT uq_whatsapp_message_logs_notification_job_id
    UNIQUE (notification_job_id);

ALTER TABLE whatsapp_message_logs
  ADD CONSTRAINT ck_whatsapp_message_logs_direction
    CHECK (direction IN ('INBOUND', 'OUTBOUND'));

ALTER TABLE whatsapp_message_logs
  ADD CONSTRAINT ck_whatsapp_message_logs_status
    CHECK (status IS NULL OR status IN ('SENT', 'FAILED'));

CREATE INDEX idx_whatsapp_message_logs_status ON whatsapp_message_logs(status);
CREATE INDEX idx_whatsapp_message_logs_trace_id ON whatsapp_message_logs(trace_id);
CREATE INDEX idx_whatsapp_message_logs_sent_at_logged_at_id
  ON whatsapp_message_logs (sent_at DESC NULLS LAST, logged_at DESC, id DESC);
