CREATE TABLE plants (
  id UUID PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_plants_code UNIQUE (code),
  CONSTRAINT ck_plants_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_plants_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE auth_user_plant_assignments (
  auth_user_id UUID NOT NULL,
  plant_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_auth_user_plant_assignments PRIMARY KEY (auth_user_id, plant_id),
  CONSTRAINT fk_auth_user_plant_assignments_auth_user FOREIGN KEY (auth_user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_auth_user_plant_assignments_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_auth_user_plant_assignments_auth_user_plant UNIQUE (auth_user_id, plant_id)
);

CREATE INDEX idx_auth_user_plant_assignments_auth_user_id ON auth_user_plant_assignments(auth_user_id);
CREATE INDEX idx_auth_user_plant_assignments_plant_id ON auth_user_plant_assignments(plant_id);
