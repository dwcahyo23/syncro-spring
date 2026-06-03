CREATE UNIQUE INDEX uq_spareparts_machine_taxonomy_identity
  ON spareparts(machine_id, category_id, kind_id, brand_id, type_id);
