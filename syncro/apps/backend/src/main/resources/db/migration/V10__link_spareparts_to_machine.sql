ALTER TABLE spareparts
  ADD COLUMN machine_id UUID NULL REFERENCES machines(id) ON DELETE RESTRICT;

UPDATE spareparts sparepart
SET machine_id = (
  SELECT installation.machine_id
  FROM machine_sparepart_installations installation
  WHERE installation.sparepart_id = sparepart.id
  ORDER BY installation.created_at ASC
  LIMIT 1
)
WHERE machine_id IS NULL;

UPDATE spareparts sparepart
SET machine_id = (
  SELECT machine.id
  FROM machines machine
  ORDER BY machine.created_at ASC
  LIMIT 1
)
WHERE machine_id IS NULL;

ALTER TABLE spareparts
  ALTER COLUMN machine_id SET NOT NULL;

CREATE INDEX idx_spareparts_machine_id ON spareparts(machine_id);
