-- Story 12-4: Sparepart Stock OP/OQ & New-Item Completion (FR-144/FR-146/AD-11).
-- Additive on V60.
--
-- 1. Add a plain UNIQUE constraint on spareparts.material_code to back the stock FK.
--    (The existing uq_spareparts_material_code is a partial expression index on
--    lower(material_code) and cannot back a foreign key in PostgreSQL. The new
--    case-sensitive UNIQUE constraint is guaranteed safe: the case-insensitive
--    partial index already prevents duplicate non-null codes, and NULLs are
--    allowed to repeat under both.)
-- 2. Create sparepart_stock table with composite PK (material_code, plant_id).
-- 3. Add SPAREPART_STOCK to the audit_log entity_type CHECK.
-- 4. Index for plant-scoped queries.

-- ---------------------------------------------------------------------------
-- 1. Unique constraint on spareparts(material_code) for the FK reference
-- ---------------------------------------------------------------------------
ALTER TABLE spareparts ADD CONSTRAINT uq_spareparts_material_code_key UNIQUE (material_code);

-- ---------------------------------------------------------------------------
-- 2. sparepart_stock — per-plant stock tracking by material code
-- ---------------------------------------------------------------------------
CREATE TABLE sparepart_stock (
  material_code VARCHAR(64) NOT NULL,
  plant_id UUID NOT NULL,
  stock_on_hand NUMERIC(18,2) NOT NULL DEFAULT 0,
  order_point NUMERIC(18,2) NOT NULL DEFAULT 0,
  order_qty NUMERIC(18,2) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_sparepart_stock PRIMARY KEY (material_code, plant_id),
  CONSTRAINT fk_sparepart_stock_material_code FOREIGN KEY (material_code)
    REFERENCES spareparts(material_code),
  CONSTRAINT fk_sparepart_stock_plant FOREIGN KEY (plant_id)
    REFERENCES plants(id)
);

CREATE INDEX idx_sparepart_stock_plant ON sparepart_stock(plant_id);

-- ---------------------------------------------------------------------------
-- 2. Audit: add SPAREPART_STOCK to the entity_type CHECK (drop/re-add pattern)
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
                         'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
                         'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT',
                         'SPAREPART_REQUEST', 'DEPARTMENT', 'DEPARTMENT_MEMBER', 'USER',
                         'SPAREPART_STOCK'));