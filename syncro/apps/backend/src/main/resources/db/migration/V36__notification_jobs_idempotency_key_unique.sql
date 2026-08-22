CREATE UNIQUE INDEX uq_notification_jobs_idempotency_key
    ON notification_jobs (idempotency_key);