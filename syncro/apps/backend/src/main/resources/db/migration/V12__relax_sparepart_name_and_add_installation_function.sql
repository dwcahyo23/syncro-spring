DROP INDEX IF EXISTS uq_spareparts_lower_name;

ALTER TABLE machine_sparepart_installations
  ADD COLUMN function_name VARCHAR(255) NOT NULL DEFAULT 'Primary';

ALTER TABLE machine_sparepart_installations
  ADD CONSTRAINT ck_machine_sparepart_installations_function_name_not_blank CHECK (btrim(function_name) <> ''),
  ADD CONSTRAINT ck_machine_sparepart_installations_function_name_length CHECK (length(function_name) <= 255);

ALTER TABLE machine_sparepart_installations
  DROP CONSTRAINT IF EXISTS uq_machine_sparepart_installations_machine_id_sparepart_id;

CREATE UNIQUE INDEX uq_machine_sparepart_installations_machine_sparepart_function
  ON machine_sparepart_installations(machine_id, sparepart_id, lower(function_name));
