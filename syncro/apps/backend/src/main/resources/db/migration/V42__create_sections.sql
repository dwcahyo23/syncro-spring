-- Story 9-1: org structure foundation — sections per plant and machine-group
-- to-section assignment. A section is an org container (MACHINERY|UTILITY|WORKSHOP),
-- never a scoping dimension (AD-2); derived section-leader scope comes from
-- machine responsibilities, and read paths filter by machineGroupIds only.
CREATE TABLE sections (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  code VARCHAR(24) NOT NULL,
  name VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_sections_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT uq_sections_plant_code UNIQUE (plant_id, code),
  CONSTRAINT ck_sections_code CHECK (code IN ('MACHINERY', 'UTILITY', 'WORKSHOP')),
  CONSTRAINT ck_sections_name_not_blank CHECK (btrim(name) <> '')
);

-- PostgreSQL table UNIQUE constraints accept column lists only, so the
-- case-insensitive name uniqueness is a unique index (V4 pattern).
CREATE UNIQUE INDEX uq_sections_plant_id_lower_name ON sections (plant_id, lower(name));

CREATE INDEX idx_sections_plant_id ON sections(plant_id);

-- Existing machine groups have no section; nullable so every existing row and the
-- pilot seed stay valid. Assignment is an explicit mutation (clear then re-assign).
ALTER TABLE machine_groups ADD COLUMN section_id UUID NULL REFERENCES sections(id);

CREATE INDEX idx_machine_groups_section_id ON machine_groups(section_id);

-- Audit: add SECTION to the entity_type CHECK (V31/V38 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION'));
