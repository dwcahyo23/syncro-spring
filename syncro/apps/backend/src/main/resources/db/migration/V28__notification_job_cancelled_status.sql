-- Story 5.5: Stop Escalation When Alert Is Acknowledged
-- Adds CANCELLED to the documented notification job status set and
-- indexes (alert_id, status) for the bulk-cancel-by-alert query.

-- Document the allowed status set. The status column is VARCHAR(24);
-- CANCELLED (9 chars) fits within it. This constraint also protects
-- against invalid status strings being persisted by any code path.
ALTER TABLE notification_jobs
    ADD CONSTRAINT chk_notification_jobs_status_allowed
    CHECK (status IN ('PENDING', 'ROUTING_FAILED', 'SENT', 'EXHAUSTED', 'ESCALATED', 'CANCELLED'));

-- Index (alert_id, status) so the acknowledge-time bulk cancel
-- (UPDATE ... WHERE alert_id = ? AND status IN ('PENDING','SENT')) is fast.
CREATE INDEX idx_notification_jobs_alert_status ON notification_jobs (alert_id, status);
