-- DW-128: the BEFORE UPDATE OF column list inherited from V16 (recreated in V44 with
-- decision_id) omitted `id` and `plant_id`, so `UPDATE audit_log SET id = ...` or
-- `SET plant_id = ...` bypassed the immutability trigger. Recreate the trigger with
-- every column covered.
DROP TRIGGER audit_log_immutable_before_update ON audit_log;

CREATE TRIGGER audit_log_immutable_before_update
  BEFORE UPDATE OF id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
                  plant_id, previous_value, new_value, created_at, decision_id
  ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();
