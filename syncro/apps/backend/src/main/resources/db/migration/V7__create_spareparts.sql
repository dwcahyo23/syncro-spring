CREATE UNIQUE INDEX uq_sparepart_taxonomy_id_dimension ON sparepart_taxonomy (id, dimension);

CREATE TABLE spareparts (
  id UUID PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  category_id UUID NOT NULL,
  brand_id UUID NOT NULL,
  kind_id UUID NOT NULL,
  type_id UUID NOT NULL,
  category_dimension VARCHAR(32) NOT NULL DEFAULT 'CATEGORY',
  brand_dimension VARCHAR(32) NOT NULL DEFAULT 'BRAND',
  kind_dimension VARCHAR(32) NOT NULL DEFAULT 'KIND',
  type_dimension VARCHAR(32) NOT NULL DEFAULT 'TYPE',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_spareparts_category_dimension FOREIGN KEY (category_id, category_dimension) REFERENCES sparepart_taxonomy(id, dimension) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_brand_dimension FOREIGN KEY (brand_id, brand_dimension) REFERENCES sparepart_taxonomy(id, dimension) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_kind_dimension FOREIGN KEY (kind_id, kind_dimension) REFERENCES sparepart_taxonomy(id, dimension) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_type_dimension FOREIGN KEY (type_id, type_dimension) REFERENCES sparepart_taxonomy(id, dimension) ON DELETE RESTRICT,
  CONSTRAINT ck_spareparts_category_dimension CHECK (category_dimension = 'CATEGORY'),
  CONSTRAINT ck_spareparts_brand_dimension CHECK (brand_dimension = 'BRAND'),
  CONSTRAINT ck_spareparts_kind_dimension CHECK (kind_dimension = 'KIND'),
  CONSTRAINT ck_spareparts_type_dimension CHECK (type_dimension = 'TYPE'),
  CONSTRAINT ck_spareparts_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_spareparts_code_length CHECK (length(code) <= 64),
  CONSTRAINT ck_spareparts_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_spareparts_name_length CHECK (length(name) <= 255)
);

CREATE UNIQUE INDEX uq_spareparts_lower_code ON spareparts (lower(code));
CREATE UNIQUE INDEX uq_spareparts_lower_name ON spareparts (lower(name));
CREATE INDEX idx_spareparts_category_id ON spareparts(category_id);
CREATE INDEX idx_spareparts_brand_id ON spareparts(brand_id);
CREATE INDEX idx_spareparts_kind_id ON spareparts(kind_id);
CREATE INDEX idx_spareparts_type_id ON spareparts(type_id);
