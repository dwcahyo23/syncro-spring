CREATE TABLE spareparts (
  id UUID PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  category_id UUID NOT NULL,
  brand_id UUID NOT NULL,
  kind_id UUID NOT NULL,
  type_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_spareparts_category_id FOREIGN KEY (category_id) REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_brand_id FOREIGN KEY (brand_id) REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_kind_id FOREIGN KEY (kind_id) REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT,
  CONSTRAINT fk_spareparts_type_id FOREIGN KEY (type_id) REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT,
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
