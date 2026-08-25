ALTER TABLE audit_log ADD COLUMN decision_id UUID NULL;

DROP TRIGGER audit_log_immutable_before_update ON audit_log;

CREATE TRIGGER audit_log_immutable_before_update
  BEFORE UPDATE OF actor_id, actor_name, action, entity_type, entity_id, entity_label,
                  previous_value, new_value, created_at, decision_id
  ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();
