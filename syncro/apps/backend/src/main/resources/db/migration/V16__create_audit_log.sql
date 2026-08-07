CREATE TABLE audit_log (
  id UUID PRIMARY KEY,
  actor_id UUID NOT NULL,
  actor_name VARCHAR(255) NOT NULL,
  action VARCHAR(16) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id UUID NOT NULL,
  entity_label VARCHAR(255) NOT NULL,
  plant_id UUID,
  previous_value TEXT,
  new_value TEXT,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_audit_log_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
  CONSTRAINT ck_audit_log_entity_type CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY', 'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY')),
  CONSTRAINT fk_audit_log_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_log_entity_type ON audit_log(entity_type);
CREATE INDEX idx_audit_log_actor_name ON audit_log(actor_name);
CREATE INDEX idx_audit_log_plant_id ON audit_log(plant_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);

CREATE FUNCTION prevent_audit_log_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable_before_update
  BEFORE UPDATE OF actor_id, actor_name, action, entity_type, entity_id, entity_label,
                previous_value, new_value, created_at
  ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

CREATE TRIGGER audit_log_immutable_before_delete
  BEFORE DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();
