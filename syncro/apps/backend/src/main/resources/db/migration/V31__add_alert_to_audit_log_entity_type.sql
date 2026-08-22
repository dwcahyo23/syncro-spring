-- Add ALERT to the audit_log entity_type CHECK constraint.
--
-- AuditLogWriter.recordSystem persists alert-creation audit rows with
-- entity_type='ALERT', but the V16 CHECK constraint never listed it,
-- so the alert-creation transaction always rolled back (audit insert failed ->
-- DataIntegrityViolationException outside the alert-save try/catch -> whole
-- @Transactional rollback). Discovered live by story 7-5 validation preflight.
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT'));
