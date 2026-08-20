-- Add columns to notification_jobs for attempt tracking
ALTER TABLE notification_jobs
    ADD COLUMN sent_at         TIMESTAMPTZ,
    ADD COLUMN attempt_count   INT         NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN max_attempts    INT         NOT NULL DEFAULT 3;

-- Create notification_attempts table for attempt history
CREATE TABLE notification_attempts
(
    id              UUID         NOT NULL,
    job_id          UUID         NOT NULL REFERENCES notification_jobs (id),
    attempt_number  INT          NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    attempted_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    response_detail VARCHAR(512),
    trace_id        VARCHAR(64),
    CONSTRAINT pk_notification_attempts PRIMARY KEY (id)
);

CREATE INDEX idx_notification_attempts_job_id ON notification_attempts (job_id);
