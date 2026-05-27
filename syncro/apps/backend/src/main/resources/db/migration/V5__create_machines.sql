ALTER TABLE machine_groups
  ADD CONSTRAINT uq_machine_groups_id_plant_id UNIQUE (id, plant_id);

CREATE TABLE machines (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  machine_group_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255),
  status VARCHAR(16) NOT NULL,
  brand VARCHAR(255),
  installed_at DATE,
  notes VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machines_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machines_machine_group_plant FOREIGN KEY (machine_group_id, plant_id) REFERENCES machine_groups(id, plant_id) ON DELETE RESTRICT,
  CONSTRAINT ck_machines_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_machines_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_machines_code_length CHECK (length(code) <= 64),
  CONSTRAINT ck_machines_name_length CHECK (name IS NULL OR length(name) <= 255),
  CONSTRAINT ck_machines_brand_length CHECK (brand IS NULL OR length(brand) <= 255),
  CONSTRAINT ck_machines_notes_length CHECK (notes IS NULL OR length(notes) <= 1000)
);

CREATE UNIQUE INDEX uq_machines_plant_id_lower_code ON machines (plant_id, lower(code));
CREATE INDEX idx_machines_plant_id ON machines(plant_id);
CREATE INDEX idx_machines_machine_group_id ON machines(machine_group_id);
CREATE INDEX idx_machines_plant_id_status ON machines(plant_id, status);
