-- Update CAT-BRG if it exists to MECHANIC to avoid FK violations
UPDATE sparepart_taxonomy 
SET code = 'MECHANIC', name = 'Mechanic' 
WHERE dimension = 'CATEGORY' AND code = 'CAT-BRG';

-- Ensure all required categories exist
INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'ELECTRIC', 'Electric', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'ELECTRIC');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'MECHANIC', 'Mechanic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'MECHANIC');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'HYDRAULIC', 'Hydraulic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'HYDRAULIC');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'PNEUMATIC', 'Pneumatic', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'PNEUMATIC');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, created_at, updated_at)
SELECT gen_random_uuid(), 'CATEGORY', 'CONSUMABLE', 'Consumable', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM sparepart_taxonomy WHERE dimension = 'CATEGORY' AND code = 'CONSUMABLE');
