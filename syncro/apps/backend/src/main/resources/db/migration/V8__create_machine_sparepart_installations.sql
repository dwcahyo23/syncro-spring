CREATE TABLE machine_sparepart_installations (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
  sparepart_id UUID NOT NULL,
  expected_production_count BIGINT NOT NULL,
  baseline_counter BIGINT NOT NULL,
  threshold_percentage INTEGER NOT NULL DEFAULT 90,
  installed_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_sparepart_installations_machine_id FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machine_sparepart_installations_sparepart_id FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT ck_machine_sparepart_installations_expected_positive CHECK (expected_production_count > 0),
  CONSTRAINT ck_machine_sparepart_installations_baseline_non_negative CHECK (baseline_counter >= 0),
  CONSTRAINT ck_machine_sparepart_installations_threshold_range CHECK (threshold_percentage BETWEEN 1 AND 100),
  CONSTRAINT uq_machine_sparepart_installations_machine_id_sparepart_id UNIQUE (machine_id, sparepart_id)
);

CREATE INDEX idx_machine_sparepart_installations_machine_id ON machine_sparepart_installations(machine_id);
CREATE INDEX idx_machine_sparepart_installations_sparepart_id ON machine_sparepart_installations(sparepart_id);
CREATE INDEX idx_machine_sparepart_installations_machine_id_sparepart_id ON machine_sparepart_installations(machine_id, sparepart_id);
