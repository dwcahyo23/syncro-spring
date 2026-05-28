CREATE TABLE sparepart_taxonomy (
  id UUID PRIMARY KEY,
  dimension VARCHAR(32) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_sparepart_taxonomy_dimension CHECK (dimension IN ('CATEGORY', 'BRAND', 'KIND', 'TYPE')),
  CONSTRAINT ck_sparepart_taxonomy_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_sparepart_taxonomy_name_length CHECK (length(name) <= 255)
);

CREATE UNIQUE INDEX uq_sparepart_taxonomy_dimension_lower_name ON sparepart_taxonomy (dimension, lower(name));
CREATE INDEX idx_sparepart_taxonomy_dimension ON sparepart_taxonomy(dimension);
