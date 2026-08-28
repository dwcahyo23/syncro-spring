-- Story 12-3 follow-up fix: escalation jobs (sparepart request escalation) carry no
-- sparepart_alert — the random-UUID stopgap in V60 always violated the alert_id FK.
--
-- Make alert_id nullable so request-escalation jobs can store NULL (a NULL referencing
-- column is not checked against the referenced table by PostgreSQL). Alert jobs keep
-- their non-null alert_id and the (alert_id, escalation_level) unique semantics;
-- uq_notification_jobs_idempotency_key continues to dedupe all jobs.
--
-- AD-9 polymorphic target columns are still NOT introduced here (deferred).
ALTER TABLE notification_jobs
    ALTER COLUMN alert_id DROP NOT NULL;
