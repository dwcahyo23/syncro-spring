-- Add index on (status, attempted_at) for notification worker status queries
-- NotificationWorkerStatusService queries:
--   WHERE status = 'FAILED' AND attempted_at > :windowStart ORDER BY attempted_at DESC LIMIT 1
--   WHERE status = 'SENT'   AND attempted_at > :windowStart ORDER BY attempted_at DESC LIMIT 1
--   WHERE status = 'FAILED' AND attempted_at > :windowStart
CREATE INDEX idx_notification_attempts_status_attempted_at
    ON notification_attempts (status, attempted_at);
