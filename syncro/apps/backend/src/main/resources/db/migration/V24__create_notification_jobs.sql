CREATE TABLE notification_jobs
(
    id                UUID         NOT NULL,
    alert_id          UUID         NOT NULL REFERENCES sparepart_alerts(id),
    escalation_level  VARCHAR(16)  NOT NULL,
    status            VARCHAR(24)  NOT NULL,
    recipient_user_id UUID,
    recipient_phone   VARCHAR(32),
    idempotency_key   VARCHAR(128) NOT NULL,
    trace_id          VARCHAR(64),
    error_detail      VARCHAR(512),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT pk_notification_jobs PRIMARY KEY (id),
    CONSTRAINT uq_notification_jobs_alert_level UNIQUE (alert_id, escalation_level)
);

CREATE INDEX idx_notification_jobs_alert_id ON notification_jobs(alert_id);
