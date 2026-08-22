-- DW-82: Enforce at DB level that a SENT notification job must carry a sent_at timestamp.
-- Backfill any existing SENT rows that lack sent_at (updated_at is a reasonable approximation
-- of the send time), then add a CONDITIONAL CHECK: SENT rows must have sent_at, all other
-- statuses (PENDING/ROUTING_FAILED/EXHAUSTED/ESCALATED/CANCELLED/RATE_LIMITED) legitimately
-- keep NULL sent_at and must remain insertable.
UPDATE notification_jobs SET sent_at = updated_at WHERE status = 'SENT' AND sent_at IS NULL;

ALTER TABLE notification_jobs
    ADD CONSTRAINT chk_notification_jobs_sent_requires_sent_at
    CHECK (status <> 'SENT' OR sent_at IS NOT NULL);
