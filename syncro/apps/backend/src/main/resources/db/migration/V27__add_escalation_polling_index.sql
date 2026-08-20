-- Add index on (status, sent_at) for efficient escalation polling
-- Escalation worker queries: WHERE status = 'SENT' AND sent_at <= :cutoff ORDER BY sent_at ASC LIMIT 10
CREATE INDEX idx_notification_jobs_status_sent_at ON notification_jobs (status, sent_at);
