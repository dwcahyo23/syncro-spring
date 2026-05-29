ALTER TABLE sparepart_taxonomy
  ADD COLUMN category_id UUID NULL REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT;

INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'ELECTRIC', 'Electric', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (
  SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'ELECTRIC'
);

UPDATE sparepart_taxonomy
SET category_id = (SELECT id FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'ELECTRIC' LIMIT 1)
WHERE dimension <> 'CATEGORY' AND category_id IS NULL;

ALTER TABLE sparepart_taxonomy
  ADD CONSTRAINT ck_sparepart_taxonomy_category_link CHECK (
    (dimension = 'CATEGORY' AND category_id IS NULL)
    OR (dimension <> 'CATEGORY' AND category_id IS NOT NULL)
  );

CREATE INDEX idx_sparepart_taxonomy_category_id ON sparepart_taxonomy(category_id);
