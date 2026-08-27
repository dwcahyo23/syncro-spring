-- Story 9-5b / Org Maintenance Model (spec-org-maintenance-model): departments layer,
-- user master extension, explicit section leaders. Additive on V57.
--
-- Summary:
--   * job_titles master (code/name).
--   * departments (plant-scoped, spv/mg leaders, soft-inactive).
--   * auth_users + display_name, nik, phone_number, job_title_id, department_id
--     (partial unique NIK/phone; backfill display_name from login_identifier).
--   * department_members link table.
--   * sections.leader_user_id (explicit section leader; auto LEADER responsibility
--     side-effect is service-owned, see SectionService.assignLeader).
--   * audit_log entity_type CHECK += DEPARTMENT, DEPARTMENT_MEMBER, USER.
--
-- Ordering note: departments and auth_users have a circular FK relationship
-- (departments.spv_id/mg_id -> auth_users; auth_users.department_id ->
-- departments). PostgreSQL requires the referenced table to exist when an FK is
-- declared, so departments is created with spv_id/mg_id as plain UUID columns and
-- the auth_users-reference FKs are added AFTER auth_users gains department_id.

-- ---------------------------------------------------------------------------
-- 1. job_titles master
-- ---------------------------------------------------------------------------
CREATE TABLE job_titles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_job_titles_code UNIQUE (code),
  CONSTRAINT ck_job_titles_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_job_titles_name_not_blank CHECK (btrim(name) <> '')
);

-- ---------------------------------------------------------------------------
-- 2. departments (spv_id/mg_id are plain UUIDs here; FKs added after auth_users)
-- ---------------------------------------------------------------------------
CREATE TABLE departments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL REFERENCES plants(id) ON DELETE RESTRICT,
  name VARCHAR(255) NOT NULL,
  spv_id UUID,
  mg_id UUID,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_departments_plant_name UNIQUE (plant_id, name),
  CONSTRAINT ck_departments_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_departments_plant_id ON departments(plant_id);
CREATE INDEX idx_departments_spv_id ON departments(spv_id);
CREATE INDEX idx_departments_mg_id ON departments(mg_id);

-- ---------------------------------------------------------------------------
-- 3. auth_users extension (department_id FK resolves departments created above;
--    job_title_id resolves job_titles)
-- ---------------------------------------------------------------------------
ALTER TABLE auth_users ADD COLUMN display_name VARCHAR(200);
ALTER TABLE auth_users ADD COLUMN nik VARCHAR(50);
ALTER TABLE auth_users ADD COLUMN phone_number VARCHAR(32);
ALTER TABLE auth_users ADD COLUMN job_title_id UUID REFERENCES job_titles(id) ON DELETE SET NULL;
ALTER TABLE auth_users ADD COLUMN department_id UUID REFERENCES departments(id) ON DELETE SET NULL;

-- Partial unique: existing rows keep NULL and remain unaffected; duplicates only
-- rejected among non-NULL values (spec: NIK/phone nullable, unique null-safe).
CREATE UNIQUE INDEX uq_auth_users_nik ON auth_users(nik) WHERE nik IS NOT NULL;
CREATE UNIQUE INDEX uq_auth_users_phone ON auth_users(phone_number) WHERE phone_number IS NOT NULL;

-- Backfill display_name from the login identifier (split at @). Existing users
-- become usable immediately without manual data entry.
UPDATE auth_users
SET display_name = COALESCE(NULLIF(split_part(login_identifier, '@', 1), ''), login_identifier)
WHERE display_name IS NULL;

-- ---------------------------------------------------------------------------
-- 4. departments -> auth_users leader FKs (circular dependency, added now)
-- ---------------------------------------------------------------------------
ALTER TABLE departments
  ADD CONSTRAINT fk_departments_spv FOREIGN KEY (spv_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  ADD CONSTRAINT fk_departments_mg FOREIGN KEY (mg_id) REFERENCES auth_users(id) ON DELETE SET NULL;

-- ---------------------------------------------------------------------------
-- 5. department_members (people unit membership; multi-department allowed)
-- ---------------------------------------------------------------------------
CREATE TABLE department_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
  assigned_by UUID NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_department_members UNIQUE (department_id, user_id)
);

CREATE INDEX idx_department_members_department_id ON department_members(department_id);
CREATE INDEX idx_department_members_user_id ON department_members(user_id);

-- ---------------------------------------------------------------------------
-- 6. sections.leader_user_id (explicit section leader)
-- ---------------------------------------------------------------------------
ALTER TABLE sections ADD COLUMN leader_user_id UUID REFERENCES auth_users(id) ON DELETE SET NULL;

CREATE INDEX idx_sections_leader_user_id ON sections(leader_user_id);

-- ---------------------------------------------------------------------------
-- 7. audit_log entity_type CHECK += DEPARTMENT, DEPARTMENT_MEMBER, USER
--    (drop/re-add pattern — preserve all existing types)
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
                         'SPAREPART_REQUEST', 'DEPARTMENT', 'DEPARTMENT_MEMBER', 'USER'));
