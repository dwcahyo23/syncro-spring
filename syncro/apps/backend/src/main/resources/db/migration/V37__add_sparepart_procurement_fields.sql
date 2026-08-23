-- Story 8-2: procurement readiness inputs on spareparts.
-- material_code is optional and globally unique (not plant/machine-scoped); uniqueness is
-- case-insensitive and multi-null safe via a partial unique index on lower(material_code),
-- mirroring the uq_spareparts_lower_code idiom from V7.
-- lead_time_hours is an optional fractional duration in HOURS (7.5 days = 180.00); scale 2.

ALTER TABLE spareparts ADD COLUMN material_code VARCHAR(64);
ALTER TABLE spareparts ADD COLUMN lead_time_hours NUMERIC(12, 2);

ALTER TABLE spareparts
  ADD CONSTRAINT ck_spareparts_lead_time_positive CHECK (lead_time_hours IS NULL OR lead_time_hours > 0);

CREATE UNIQUE INDEX uq_spareparts_material_code
  ON spareparts (lower(material_code))
  WHERE material_code IS NOT NULL;
