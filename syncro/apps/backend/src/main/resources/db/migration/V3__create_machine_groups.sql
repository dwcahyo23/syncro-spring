CREATE TABLE machine_groups (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_groups_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT uq_machine_groups_plant_id_name UNIQUE (plant_id, name),
  CONSTRAINT ck_machine_groups_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_machine_groups_plant_id ON machine_groups(plant_id);
