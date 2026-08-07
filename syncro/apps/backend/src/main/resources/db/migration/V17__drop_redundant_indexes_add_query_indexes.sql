DROP INDEX IF EXISTS idx_machine_sparepart_installations_machine_id_sparepart_id;

ALTER TABLE auth_user_plant_assignments
  DROP CONSTRAINT IF EXISTS uq_auth_user_plant_assignments_auth_user_plant;

DROP INDEX IF EXISTS idx_auth_user_plant_assignments_auth_user_id;
DROP INDEX IF EXISTS idx_machine_groups_plant_id;
DROP INDEX IF EXISTS idx_machines_plant_id;
DROP INDEX IF EXISTS idx_sparepart_taxonomy_dimension;

CREATE INDEX IF NOT EXISTS idx_spareparts_code ON spareparts(code);
CREATE INDEX IF NOT EXISTS idx_machines_code ON machines(code);
CREATE INDEX IF NOT EXISTS idx_sparepart_taxonomy_dimension_name ON sparepart_taxonomy(dimension, name);
