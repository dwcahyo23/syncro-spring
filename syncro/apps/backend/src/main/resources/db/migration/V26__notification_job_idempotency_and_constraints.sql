-- Patch: Add optimistic locking version column to notification_jobs (AC 9)
ALTER TABLE notification_jobs
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

-- Patch: Add CHECK constraint to enforce max_attempts > 0
ALTER TABLE notification_jobs
    ADD CONSTRAINT chk_notification_jobs_max_attempts CHECK (max_attempts > 0);

-- Patch: Add composite index on (status, next_attempt_at) for efficient polling
CREATE INDEX idx_notification_jobs_status_next_attempt
    ON notification_jobs (status, next_attempt_at);

-- Patch: Add UNIQUE constraint on (job_id, attempt_number) to prevent duplicate attempt records
ALTER TABLE notification_attempts
    ADD CONSTRAINT uq_notification_attempts_job_attempt UNIQUE (job_id, attempt_number);
