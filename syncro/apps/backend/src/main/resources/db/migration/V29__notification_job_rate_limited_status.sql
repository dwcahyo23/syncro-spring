-- Story 5-8: Implement WAHA Rate Limiting
-- Adds RATE_LIMITED to the CHECK constraint that documents the allowed
-- notification_jobs status set, and drops/recreates the constraint atomically.
--
-- VARCHAR(24) column; RATE_LIMITED is 12 chars — fits within it.

ALTER TABLE notification_jobs
    DROP CONSTRAINT chk_notification_jobs_status_allowed;

ALTER TABLE notification_jobs
    ADD CONSTRAINT chk_notification_jobs_status_allowed
    CHECK (status IN ('PENDING', 'ROUTING_FAILED', 'SENT', 'EXHAUSTED', 'ESCALATED', 'CANCELLED', 'RATE_LIMITED'));
