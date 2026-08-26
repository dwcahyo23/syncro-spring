-- Story 10-8: workorder & technician ratings (FR-121/FR-124, AD-14). Additive on V52.
-- Three tables:
--   rating_dimensions  — configurable 1-5 star rating dimensions, SUPER_ADMIN-managed
--                        (dimensions are data, not code). Seeded with a default set
--                        (Speed / Work Quality / Tidiness) so the UI is usable before
--                        customization.
--   workorder_ratings  — one immutable rating per (workorder, type, rated user). A
--                        WORKORDER-type rating has rated_user_id NULL and is unique per
--                        workorder via a partial unique index (PostgreSQL unique indexes
--                        treat NULLs as distinct).
--   workorder_rating_scores — normalized child scores: dimensions are dynamic so a
--                        column-per-dimension schema would need a migration whenever
--                        SUPER_ADMIN adds a dimension. PK (rating_id, dimension_id).

CREATE TABLE rating_dimensions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(40) NOT NULL,
  label VARCHAR(100) NOT NULL,
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_rating_dimensions_code UNIQUE (code)
);

CREATE INDEX idx_rating_dimensions_sort_order ON rating_dimensions(sort_order);

CREATE TABLE workorder_ratings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workorder_id VARCHAR(50) NOT NULL,
  rating_type VARCHAR(12) NOT NULL,
  rated_user_id UUID,
  rater_user_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_workorder_ratings_workorder FOREIGN KEY (workorder_id) REFERENCES work_orders(id),
  CONSTRAINT ck_workorder_ratings_type CHECK (rating_type IN ('TECHNICIAN','WORKORDER')),
  CONSTRAINT uq_workorder_ratings_identity UNIQUE (workorder_id, rating_type, rated_user_id)
);

CREATE UNIQUE INDEX uq_workorder_ratings_workorder ON workorder_ratings(workorder_id)
  WHERE rating_type = 'WORKORDER';

CREATE TABLE workorder_rating_scores (
  rating_id UUID NOT NULL,
  dimension_id UUID NOT NULL,
  score SMALLINT NOT NULL,
  CONSTRAINT pk_workorder_rating_scores PRIMARY KEY (rating_id, dimension_id),
  CONSTRAINT fk_workorder_rating_scores_rating FOREIGN KEY (rating_id)
    REFERENCES workorder_ratings(id) ON DELETE CASCADE,
  CONSTRAINT fk_workorder_rating_scores_dimension FOREIGN KEY (dimension_id)
    REFERENCES rating_dimensions(id),
  CONSTRAINT ck_workorder_rating_scores_score CHECK (score BETWEEN 1 AND 5)
);

-- Seed the default dimension set (AD-14 / UJ-3 example) so the ratings UI is usable
-- before SUPER_ADMIN customizes the configuration. Idempotent by unique code.
INSERT INTO rating_dimensions (code, label, sort_order, created_by)
VALUES ('SPEED', 'Speed', 1, '00000000-0000-0000-0000-000000000000'),
       ('WORK_QUALITY', 'Work Quality', 2, '00000000-0000-0000-0000-000000000000'),
       ('TIDINESS', 'Tidiness', 3, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (code) DO NOTHING;

-- Audit: add WORKORDER_RATING + RATING_DIMENSION to the entity_type CHECK
-- (V47/V48/V49/V50/V52 drop/re-add pattern).
ALTER TABLE audit_log DROP CONSTRAINT ck_audit_log_entity_type;
ALTER TABLE audit_log ADD CONSTRAINT ck_audit_log_entity_type
  CHECK (entity_type IN ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
                         'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
                         'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
                         'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
                         'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION'));
