CREATE UNIQUE INDEX uq_machine_groups_plant_id_lower_name ON machine_groups (plant_id, lower(name));
