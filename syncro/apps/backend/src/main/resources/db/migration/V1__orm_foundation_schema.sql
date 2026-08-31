-- ============================================================================
-- V1__orm_foundation_schema.sql — Story 15-1: consolidated fresh schema
-- ============================================================================
--
-- Single consolidated migration producing the ORM target blueprint base schema
-- (_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md) plus the
-- adapted legacy final state (the former V1..V68 accumulation). Per AD-22 the
-- dev-phase DB may be reset; there is NO backward-compatibility burden and no
-- baseline shim. Old databases with a legacy 68-row history are dropped and
-- re-created — never migrated in place.
--
-- Adaptations vs. the legacy final state (resolved decision points DP1..DP5):
--   * department_members        -> department_users (DP: blueprint A3)
--   * sparepart_stock           -> inventory_stock_balances (DP3): UUID PK over
--                                  (sparepart_id, location_id); available /
--                                  reserved / consumed / minimum_stock semantics
--                                  (reorder rule: available <= minimum_stock)
--   * workorder_signatures      -> user_signatures + signature_uses (DP4); the
--                                  WO signature becomes signature_uses with
--                                  subject_type='WORK_ORDER'
--   * work_orders.source        -> CHECK ('EXTERNAL','INTERNAL') (DP5; was SYNCED)
--   * work_orders.status        -> 6-value CHECK (OPEN, IN_PROGRESS,
--                                  PENDING_SPAREPART, PENDING_REVIEW, CLOSED,
--                                  CANCELLED); internal id format WO-YYMMXXXX
--   * workorder_todos.workorder_id -> work_order_id (FK naming convention)
--   * spareparts                -> + BOM master columns (hierarchy_identity_key,
--                                  bom_serial, bom_code, bom_code_version,
--                                  review_status, rejection_reason) (DP2: the
--                                  taxonomy stays; no new taxonomy tables)
--   * machines                  -> + area_id -> machine_areas (DP1: machine_groups
--                                  kept as the category container)
--   * job_titles                -> + binding_scope / is_active /
--                                  default_system_role_id (A2/A4)
--   * work_order_categories     -> + plant_id (nullable = global), requires_rating
--   * work_order_status_history -> + actor_type (USER/SYSTEM/BOT)
--   * audit_log.entity_type     -> CHECK extended with the new module values
--   * preventive_programs / preventive_schedules / preventive_checklist_* /
--     preventive_schedule_attachments are KEPT as-is — their replacement happens
--     in the PM redesign epic (the new pm_* tables live alongside them).
--
-- Blueprint tables added (Modul A-I): system_roles, role_permission_mappings,
-- menu_features, domain_contexts, user_job_bindings, user_role_bindings,
-- machine_areas, plant_working_calendars(+_dates), work_assignments, work_logs,
-- work_log/work_order rating criteria (+categories), work_log_ratings,
-- work_order_quality_ratings(+technicians+scores), inventory_locations/
-- transfers/reservations, pm_frequencies..pm_execution_items, kpi_* tables,
-- non_conformances, eight_d_reports, calibration_*, equipment_change_notices,
-- machine_setup_baselines, lesson_learned, historical_machine_records,
-- user_signatures, signature_uses, auth_login_audits,
-- phone_verification_challenges, webhook_configs, webhook_delivery_logs,
-- whatsapp_message_logs.
--
-- Consolidation order follows FK dependency: extension -> auth/plants -> org ->
-- machines -> spareparts -> maintenance -> preventive (legacy + new PM) ->
-- inventory -> kpi -> compliance -> requests/alerts/notifications -> sync ->
-- settings/audit. Migration-owned seeds (waha templates, rating dimensions,
-- escalation configs, sync field mappings, settings row, category 02) ride on
-- V1 exactly as their original per-story migrations seeded them.
-- ============================================================================

-- repair_sessions EXCLUDE (no-overlap) needs btree_gist for the = operator on uuid.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ============================================================================
-- 1. Plants & auth baseline (legacy V1/V2/V23/V45/V58 final state)
-- ============================================================================

CREATE TABLE plants (
  id UUID PRIMARY KEY,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_plants_code UNIQUE (code),
  CONSTRAINT ck_plants_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_plants_name_not_blank CHECK (btrim(name) <> '')
);

-- ============================================================================
-- 2. New module A core: system roles, menu features, domain contexts
--    (created before job_titles so the default_system_role_id FK can resolve)
-- ============================================================================

CREATE TABLE system_roles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  level INT NOT NULL DEFAULT 0,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  description TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_system_roles_code UNIQUE (code),
  CONSTRAINT ck_system_roles_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_system_roles_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_system_roles_level_non_negative CHECK (level >= 0)
);

CREATE TABLE menu_features (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(100) NOT NULL,
  module VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_menu_features_code UNIQUE (code),
  CONSTRAINT ck_menu_features_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_menu_features_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE domain_contexts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(64) NOT NULL,
  name VARCHAR(200) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_domain_contexts_code UNIQUE (code),
  CONSTRAINT ck_domain_contexts_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_domain_contexts_name_not_blank CHECK (btrim(name) <> '')
);

-- ============================================================================
-- 3. Job titles (legacy V58 + A4 extension: binding_scope / is_active /
--    default_system_role_id)
-- ============================================================================

CREATE TABLE job_titles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  binding_scope VARCHAR(16) NOT NULL DEFAULT 'NONE',
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  default_system_role_id UUID REFERENCES system_roles(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_job_titles_code UNIQUE (code),
  CONSTRAINT ck_job_titles_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_job_titles_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_job_titles_binding_scope CHECK (binding_scope IN ('NONE', 'PLANT', 'AREA'))
);

CREATE INDEX idx_job_titles_default_system_role_id ON job_titles(default_system_role_id);

-- ============================================================================
-- 4. Departments (legacy V58 final state; blueprint A2) — spv/mg are plain UUID
--    columns here; the auth_users FKs are added after auth_users below (circular
--    dependency, V58 pattern).
-- ============================================================================

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

-- ============================================================================
-- 5. Auth users (legacy V1/V23/V45/V58 final state)
-- ============================================================================

CREATE TABLE auth_users (
  id UUID PRIMARY KEY,
  login_identifier VARCHAR(255) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  application_role VARCHAR(32) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  whatsapp_number VARCHAR(32),
  display_name VARCHAR(200),
  nik VARCHAR(50),
  phone_number VARCHAR(32),
  job_title_id UUID REFERENCES job_titles(id) ON DELETE SET NULL,
  department_id UUID REFERENCES departments(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_auth_users_login_identifier UNIQUE (login_identifier),
  CONSTRAINT ck_auth_users_application_role CHECK (application_role IN
    ('SUPER_ADMIN','MANAGER_MAINTENANCE','MAINTENANCE_LEADER','SECTION_LEADER',
     'STAFF_MAINTENANCE','TECHNICIAN','INVENTORY_MAINTENANCE','STOREKEEPER',
     'PRODUCTION_LEADER','AUDITOR'))
);

CREATE UNIQUE INDEX uq_auth_users_nik ON auth_users(nik) WHERE nik IS NOT NULL;
CREATE UNIQUE INDEX uq_auth_users_phone ON auth_users(phone_number) WHERE phone_number IS NOT NULL;
CREATE INDEX idx_auth_users_job_title_id ON auth_users(job_title_id);
CREATE INDEX idx_auth_users_department_id ON auth_users(department_id);

-- Departments -> auth_users leader FKs (circular dependency resolved here).
ALTER TABLE departments
  ADD CONSTRAINT fk_departments_spv FOREIGN KEY (spv_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  ADD CONSTRAINT fk_departments_mg FOREIGN KEY (mg_id) REFERENCES auth_users(id) ON DELETE SET NULL;

-- ============================================================================
-- 6. department_users (blueprint A3; renamed from department_members)
-- ============================================================================

CREATE TABLE department_users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  department_id UUID NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
  assigned_by UUID NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_department_users_department_user UNIQUE (department_id, user_id)
);

CREATE INDEX idx_department_users_department_id ON department_users(department_id);
CREATE INDEX idx_department_users_user_id ON department_users(user_id);

-- ============================================================================
-- 7. New module A bindings: role_permission_mappings, user_job_bindings,
--    user_role_bindings (blueprint A6/A9/A10)
-- ============================================================================

CREATE TABLE role_permission_mappings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  system_role_id UUID NOT NULL REFERENCES system_roles(id) ON DELETE CASCADE,
  menu_feature_id UUID NOT NULL REFERENCES menu_features(id) ON DELETE CASCADE,
  domain_id UUID REFERENCES domain_contexts(id) ON DELETE CASCADE,
  is_granted BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- domain_id is nullable: NULL means "all domains". PostgreSQL UNIQUE treats NULLs
-- as distinct, so the all-domains row uniqueness needs the COALESCE expression.
CREATE UNIQUE INDEX uq_role_permission_mappings_role_menu_domain
  ON role_permission_mappings (system_role_id, menu_feature_id, COALESCE(domain_id, '00000000-0000-0000-0000-000000000000'::uuid));

CREATE INDEX idx_role_permission_mappings_system_role_id ON role_permission_mappings(system_role_id);
CREATE INDEX idx_role_permission_mappings_menu_feature_id ON role_permission_mappings(menu_feature_id);

CREATE TABLE user_job_bindings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
  job_title_id UUID NOT NULL REFERENCES job_titles(id) ON DELETE RESTRICT,
  assigned_by UUID NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_user_job_bindings_user UNIQUE (user_id)
);

CREATE INDEX idx_user_job_bindings_job_title_id ON user_job_bindings(job_title_id);

CREATE TABLE user_role_bindings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
  system_role_id UUID NOT NULL REFERENCES system_roles(id) ON DELETE CASCADE,
  is_override BOOLEAN NOT NULL DEFAULT FALSE,
  assigned_by UUID NOT NULL,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_user_role_bindings_user_role UNIQUE (user_id, system_role_id)
);

CREATE INDEX idx_user_role_bindings_system_role_id ON user_role_bindings(system_role_id);

-- ============================================================================
-- 8. Plant assignments, sections, machine groups, machine areas (legacy
--    V2/V42/V3/V4 + blueprint A11 machine_areas)
-- ============================================================================

CREATE TABLE auth_user_plant_assignments (
  auth_user_id UUID NOT NULL,
  plant_id UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_auth_user_plant_assignments PRIMARY KEY (auth_user_id, plant_id),
  CONSTRAINT fk_auth_user_plant_assignments_auth_user FOREIGN KEY (auth_user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
  CONSTRAINT fk_auth_user_plant_assignments_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE
);

CREATE INDEX idx_auth_user_plant_assignments_plant_id ON auth_user_plant_assignments(plant_id);

CREATE TABLE sections (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  code VARCHAR(24) NOT NULL,
  name VARCHAR(255) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  version BIGINT NOT NULL DEFAULT 0,
  leader_user_id UUID REFERENCES auth_users(id) ON DELETE SET NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_sections_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT uq_sections_plant_code UNIQUE (plant_id, code),
  CONSTRAINT ck_sections_code CHECK (code IN ('MACHINERY', 'UTILITY', 'WORKSHOP')),
  CONSTRAINT ck_sections_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uq_sections_plant_id_lower_name ON sections (plant_id, lower(name));
CREATE INDEX idx_sections_plant_id ON sections(plant_id);
CREATE INDEX idx_sections_leader_user_id ON sections(leader_user_id);

CREATE TABLE machine_groups (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  section_id UUID REFERENCES sections(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_groups_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machine_groups_section FOREIGN KEY (section_id) REFERENCES sections(id),
  CONSTRAINT uq_machine_groups_plant_id_name UNIQUE (plant_id, name),
  CONSTRAINT ck_machine_groups_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uq_machine_groups_plant_id_lower_name ON machine_groups (plant_id, lower(name));
CREATE UNIQUE INDEX uq_machine_groups_id_plant_id ON machine_groups (id, plant_id);
CREATE INDEX idx_machine_groups_section_id ON machine_groups(section_id);

CREATE TABLE machine_areas (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL REFERENCES plants(id) ON DELETE RESTRICT,
  code VARCHAR(64),
  name VARCHAR(255) NOT NULL,
  description TEXT,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_machine_areas_plant_name UNIQUE (plant_id, name),
  CONSTRAINT ck_machine_areas_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uq_machine_areas_plant_code ON machine_areas (plant_id, code) WHERE code IS NOT NULL;
CREATE INDEX idx_machine_areas_plant_id ON machine_areas(plant_id);

-- ============================================================================
-- 9. Machines (legacy V5/V10/V18 + machine_areas.area_id)
-- ============================================================================

CREATE TABLE machines (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  machine_group_id UUID NOT NULL,
  area_id UUID,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255),
  status VARCHAR(16) NOT NULL,
  brand VARCHAR(255),
  installed_at DATE,
  notes VARCHAR(1000),
  optional_telemetry_fields JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machines_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machines_machine_group_plant FOREIGN KEY (machine_group_id, plant_id) REFERENCES machine_groups(id, plant_id) ON DELETE RESTRICT,
  CONSTRAINT fk_machines_area FOREIGN KEY (area_id) REFERENCES machine_areas(id) ON DELETE SET NULL,
  CONSTRAINT ck_machines_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_machines_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
  CONSTRAINT ck_machines_code_length CHECK (length(code) <= 64),
  CONSTRAINT ck_machines_name_length CHECK (name IS NULL OR length(name) <= 255),
  CONSTRAINT ck_machines_brand_length CHECK (brand IS NULL OR length(brand) <= 255),
  CONSTRAINT ck_machines_notes_length CHECK (notes IS NULL OR length(notes) <= 1000)
);

CREATE UNIQUE INDEX uq_machines_plant_id_lower_code ON machines (plant_id, lower(code));
CREATE INDEX idx_machines_code ON machines(code);
CREATE INDEX idx_machines_machine_group_id ON machines(machine_group_id);
CREATE INDEX idx_machines_area_id ON machines(area_id);
CREATE INDEX idx_machines_plant_id_status ON machines(plant_id, status);

CREATE TABLE machine_responsibilities (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL REFERENCES machines(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE CASCADE,
  level VARCHAR(32) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT uq_machine_responsibilities_machine_user UNIQUE (machine_id, user_id)
);

CREATE INDEX idx_machine_responsibilities_machine_id ON machine_responsibilities(machine_id);
CREATE INDEX idx_machine_responsibilities_user_id ON machine_responsibilities(user_id);

-- ============================================================================
-- 9b. Cross-plant teams (legacy V43) — expiry-dated collections of members and
--     target machines; at derive time the org module resolves the machines'
--     machine-group ids into the machineGroupIds scope. Expiry is evaluated
--     lazily via Clock — no stored `active` column. Team audit rows use
--     plant_id=null (audit_log.plant_id is nullable).
-- ============================================================================

CREATE TABLE teams (
  id UUID PRIMARY KEY,
  name VARCHAR(255) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_teams_name_not_blank CHECK (btrim(name) <> '')
);

CREATE UNIQUE INDEX uq_teams_lower_name ON teams (lower(name));

CREATE TABLE team_members (
  team_id UUID NOT NULL,
  user_id UUID NOT NULL,
  PRIMARY KEY (team_id, user_id),
  CONSTRAINT fk_team_members_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT fk_team_members_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE RESTRICT
);

CREATE INDEX idx_team_members_user_id ON team_members(user_id);

CREATE TABLE team_machines (
  team_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  PRIMARY KEY (team_id, machine_id),
  CONSTRAINT fk_team_machines_team FOREIGN KEY (team_id) REFERENCES teams(id) ON DELETE CASCADE,
  CONSTRAINT fk_team_machines_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT
);

CREATE INDEX idx_team_machines_machine_id ON team_machines(machine_id);

CREATE TABLE machine_counter_states (
  machine_id UUID NOT NULL,
  counting BIGINT NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_machine_counter_states PRIMARY KEY (machine_id),
  CONSTRAINT fk_machine_counter_states_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE
);

-- ============================================================================
-- 10. Shift windows (legacy V40)
-- ============================================================================

CREATE TABLE machine_group_shift_windows (
  id UUID PRIMARY KEY,
  machine_group_id UUID NOT NULL,
  shift_number SMALLINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_group_shift_windows_machine_group_id FOREIGN KEY (machine_group_id) REFERENCES machine_groups(id) ON DELETE CASCADE,
  CONSTRAINT uq_machine_group_shift_windows_machine_group_shift_number UNIQUE (machine_group_id, shift_number),
  CONSTRAINT ck_machine_group_shift_windows_shift_number CHECK (shift_number BETWEEN 1 AND 3)
);

CREATE TABLE machine_shift_windows (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
  shift_number SMALLINT NOT NULL,
  start_time TIME NOT NULL,
  end_time TIME NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_shift_windows_machine_id FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT uq_machine_shift_windows_machine_shift_number UNIQUE (machine_id, shift_number),
  CONSTRAINT ck_machine_shift_windows_shift_number CHECK (shift_number BETWEEN 1 AND 3)
);

-- ============================================================================
-- 11. Sparepart taxonomy & master (legacy V6/V7/V10/V11/V12/V13/V15/V37/V39/V61
--     + blueprint D2/D3 BOM master columns; DP2: taxonomy kept, no new tables)
-- ============================================================================

CREATE TABLE sparepart_taxonomy (
  id UUID PRIMARY KEY,
  dimension VARCHAR(32) NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  category_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_sparepart_taxonomy_dimension CHECK (dimension IN ('CATEGORY', 'BRAND', 'KIND', 'TYPE')),
  CONSTRAINT ck_sparepart_taxonomy_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_sparepart_taxonomy_code_length CHECK (length(code) <= 64),
  CONSTRAINT ck_sparepart_taxonomy_name_not_blank CHECK (btrim(name) <> ''),
  CONSTRAINT ck_sparepart_taxonomy_name_length CHECK (length(name) <= 255)
);

CREATE UNIQUE INDEX uq_sparepart_taxonomy_dimension_lower_code ON sparepart_taxonomy (dimension, lower(code));
CREATE UNIQUE INDEX uq_sparepart_taxonomy_dimension_lower_name ON sparepart_taxonomy (dimension, lower(name));
CREATE UNIQUE INDEX uq_sparepart_taxonomy_id_dimension ON sparepart_taxonomy (id, dimension);
CREATE INDEX idx_sparepart_taxonomy_dimension_name ON sparepart_taxonomy(dimension, name);
CREATE INDEX idx_sparepart_taxonomy_category_id ON sparepart_taxonomy(category_id);

ALTER TABLE sparepart_taxonomy
  ADD CONSTRAINT fk_sparepart_taxonomy_category FOREIGN KEY (category_id) REFERENCES sparepart_taxonomy(id) ON DELETE RESTRICT,
  ADD CONSTRAINT ck_sparepart_taxonomy_category_link CHECK (
    (dimension = 'CATEGORY' AND category_id IS NULL)
    OR (dimension <> 'CATEGORY' AND category_id IS NOT NULL)
  );

CREATE TABLE spareparts (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
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
  material_code VARCHAR(64),
  lead_time_hours NUMERIC(12, 2),
  image_object_key VARCHAR(255),
  -- Blueprint D3 BOM master columns
  hierarchy_identity_key VARCHAR(255),
  bom_serial VARCHAR(100),
  bom_code VARCHAR(100),
  bom_code_version INT,
  review_status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
  rejection_reason TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_spareparts_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
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
  CONSTRAINT ck_spareparts_name_length CHECK (length(name) <= 255),
  CONSTRAINT ck_spareparts_lead_time_positive CHECK (lead_time_hours IS NULL OR lead_time_hours > 0),
  CONSTRAINT ck_spareparts_review_status CHECK (review_status IN ('PENDING_REVIEW', 'ACTIVE', 'REJECTED')),
  CONSTRAINT uq_spareparts_material_code_key UNIQUE (material_code)
);

CREATE UNIQUE INDEX uq_spareparts_lower_code ON spareparts (lower(code));
CREATE INDEX idx_spareparts_code ON spareparts(code);
CREATE INDEX idx_spareparts_machine_id ON spareparts(machine_id);
CREATE INDEX idx_spareparts_category_id ON spareparts(category_id);
CREATE INDEX idx_spareparts_brand_id ON spareparts(brand_id);
CREATE INDEX idx_spareparts_kind_id ON spareparts(kind_id);
CREATE INDEX idx_spareparts_type_id ON spareparts(type_id);

-- Blueprint D3: hierarchy identity unique; BOM code unique; case-insensitive
-- multi-null-safe material code uniqueness (V37 pattern).
CREATE UNIQUE INDEX uq_spareparts_hierarchy_identity_key ON spareparts (hierarchy_identity_key) WHERE hierarchy_identity_key IS NOT NULL;
CREATE UNIQUE INDEX uq_spareparts_bom_code ON spareparts (bom_code) WHERE bom_code IS NOT NULL;
CREATE UNIQUE INDEX uq_spareparts_material_code ON spareparts (lower(material_code)) WHERE material_code IS NOT NULL;
CREATE UNIQUE INDEX uq_spareparts_machine_cat_kind_serial
  ON spareparts (machine_id, category_id, kind_id, bom_serial) WHERE bom_serial IS NOT NULL;

-- ============================================================================
-- 12. Machine sparepart installations (legacy V8/V12/V32)
-- ============================================================================

CREATE TABLE machine_sparepart_installations (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
  sparepart_id UUID NOT NULL,
  expected_production_count BIGINT NOT NULL,
  baseline_counter BIGINT NOT NULL,
  threshold_percentage INTEGER NOT NULL DEFAULT 90,
  function_name VARCHAR(255) NOT NULL DEFAULT 'Primary',
  installed_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_sparepart_installations_machine_id FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machine_sparepart_installations_sparepart_id FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT ck_machine_sparepart_installations_expected_positive CHECK (expected_production_count > 0),
  CONSTRAINT ck_machine_sparepart_installations_baseline_non_negative CHECK (baseline_counter >= 0),
  CONSTRAINT ck_machine_sparepart_installations_threshold_range CHECK (threshold_percentage BETWEEN 1 AND 100),
  CONSTRAINT ck_machine_sparepart_installations_function_name_not_blank CHECK (btrim(function_name) <> ''),
  CONSTRAINT ck_machine_sparepart_installations_function_name_length CHECK (length(function_name) <= 255)
);

CREATE UNIQUE INDEX uq_machine_sparepart_installations_machine_sparepart_function
  ON machine_sparepart_installations(machine_id, sparepart_id, lower(function_name));
CREATE INDEX idx_machine_sparepart_installations_machine_id ON machine_sparepart_installations(machine_id);
CREATE INDEX idx_machine_sparepart_installations_sparepart_id ON machine_sparepart_installations(sparepart_id);

CREATE TABLE sparepart_price_entries (
  id UUID PRIMARY KEY,
  sparepart_id UUID NOT NULL,
  amount NUMERIC(18, 2) NOT NULL,
  currency CHAR(3) NOT NULL DEFAULT 'IDR',
  kurs_to_idr NUMERIC(18, 6),
  idr_amount NUMERIC(18, 2) NOT NULL,
  entered_by UUID NOT NULL,
  entered_at TIMESTAMPTZ NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT fk_sparepart_price_entries_sparepart_id FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT fk_sparepart_price_entries_entered_by FOREIGN KEY (entered_by) REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_sparepart_price_entries_amount_positive CHECK (amount > 0),
  CONSTRAINT ck_sparepart_price_entries_kurs_positive CHECK (kurs_to_idr IS NULL OR kurs_to_idr > 0),
  CONSTRAINT ck_sparepart_price_entries_idr_amount_positive CHECK (idr_amount > 0),
  CONSTRAINT ck_sparepart_price_entries_currency_format CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE INDEX idx_sparepart_price_entries_sparepart_entered_at
  ON sparepart_price_entries (sparepart_id, entered_at DESC);

-- ============================================================================
-- 13. Workorder categories & work orders (legacy V47/V48/V49/V51/V56 + blueprint
--     B1/B2: 6-value status CHECK, EXTERNAL/INTERNAL source, WO-YYMMXXXX ids,
--     categories gain plant_id (nullable = global) + requires_rating)
-- ============================================================================

CREATE TABLE work_order_categories (
  id UUID PRIMARY KEY,
  plant_id UUID REFERENCES plants(id) ON DELETE CASCADE,
  code VARCHAR(16) NOT NULL,
  label VARCHAR(100) NOT NULL,
  description TEXT,
  requires_rating BOOLEAN NOT NULL DEFAULT FALSE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  target_response_minutes INT,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_work_order_categories_code UNIQUE (code)
);

CREATE INDEX idx_work_order_categories_plant_id ON work_order_categories(plant_id);

CREATE TABLE work_orders (
  id VARCHAR(50) PRIMARY KEY,
  source VARCHAR(8) NOT NULL,
  parent_id VARCHAR(50),
  status VARCHAR(20) NOT NULL,
  category_id UUID,
  machine_id UUID NOT NULL,
  description TEXT,
  sync_version BIGINT NOT NULL DEFAULT 0,
  idempotency_key VARCHAR(64),
  assigned_technician_id UUID,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  mttr_minutes BIGINT,
  response_time_minutes BIGINT,
  done_reason VARCHAR(1000),
  preventive_schedule_id UUID,
  report_chronological TEXT,
  report_analyze TEXT,
  report_corrective TEXT,
  report_preventive TEXT,
  cp_cp_lower NUMERIC(8,4),
  cp_cp_upper NUMERIC(8,4),
  cpk NUMERIC(8,4),
  cpk_pdf_object_key VARCHAR(255),
  fmea_failure_type VARCHAR(30),
  stop_time_reason VARCHAR(30),
  stop_time_detail VARCHAR(500),
  CONSTRAINT fk_work_orders_parent FOREIGN KEY (parent_id) REFERENCES work_orders(id),
  CONSTRAINT fk_work_orders_category FOREIGN KEY (category_id) REFERENCES work_order_categories(id),
  CONSTRAINT fk_work_orders_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
  CONSTRAINT ck_work_orders_source CHECK (source IN ('EXTERNAL', 'INTERNAL')),
  CONSTRAINT ck_work_orders_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'PENDING_SPAREPART',
                                                     'PENDING_REVIEW', 'CLOSED', 'CANCELLED')),
  CONSTRAINT ck_work_orders_fmea_failure_type
    CHECK (fmea_failure_type IS NULL OR fmea_failure_type IN ('ELECTRIC','MECHANICAL','PNEUMATIC','HYDRAULIC','OTHER')),
  CONSTRAINT ck_work_orders_stop_time_reason
    CHECK (stop_time_reason IS NULL OR stop_time_reason IN ('ELECTRIC','MECHANICAL','PNEUMATIC','HYDRAULIC','OTHER'))
);

CREATE UNIQUE INDEX uq_work_orders_idempotency_key
  ON work_orders(idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX idx_work_orders_parent_id ON work_orders(parent_id);
CREATE INDEX idx_work_orders_machine_id ON work_orders(machine_id);
CREATE INDEX idx_work_orders_category_id ON work_orders(category_id);
CREATE INDEX idx_work_orders_status ON work_orders(status);

-- preventive_schedule_id FK is added after preventive_schedules exists (V56 pattern).
CREATE UNIQUE INDEX uq_work_orders_preventive_schedule ON work_orders(preventive_schedule_id)
  WHERE preventive_schedule_id IS NOT NULL;
CREATE INDEX idx_work_orders_preventive_schedule ON work_orders(preventive_schedule_id);

CREATE TABLE work_order_status_history (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  source VARCHAR(8) NOT NULL,
  actor VARCHAR(50),
  actor_type VARCHAR(16) NOT NULL DEFAULT 'USER',
  trace_id VARCHAR(36),
  transitioned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_work_order_status_history_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT ck_work_order_status_history_source CHECK (source IN ('MANUAL', 'DERIVED', 'SYNC')),
  CONSTRAINT ck_work_order_status_history_actor_type CHECK (actor_type IN ('USER', 'SYSTEM', 'BOT'))
);

CREATE INDEX idx_work_order_status_history_work_order_id ON work_order_status_history(work_order_id);

CREATE TABLE workorder_id_sequences (
  prefix VARCHAR(6) PRIMARY KEY,
  last_seq INTEGER NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================================
-- 14. Repair sessions (legacy V49) — btree_gist EXCLUDE no-overlap
-- ============================================================================

CREATE TABLE repair_sessions (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  technician_id UUID NOT NULL,
  description VARCHAR(2000),
  started_at TIMESTAMPTZ NOT NULL,
  ended_at TIMESTAMPTZ NULL,
  duration_minutes BIGINT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_repair_sessions_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT excl_repair_sessions_no_overlap EXCLUDE USING gist (
    work_order_id WITH =,
    tstzrange(started_at, ended_at) WITH &&
  ),
  CONSTRAINT ck_repair_sessions_end_after_start CHECK (ended_at IS NULL OR ended_at > started_at),
  CONSTRAINT ck_repair_sessions_duration CHECK (ended_at IS NULL OR duration_minutes IS NOT NULL AND duration_minutes >= 0)
);

CREATE INDEX idx_repair_sessions_work_order_id ON repair_sessions(work_order_id);

-- ============================================================================
-- 15. Workorder attachments, todos, ratings, acks, signatures (legacy
--     V50/V52/V53/V67 + blueprint C + DP4 signature replacement)
-- ============================================================================

CREATE TABLE workorder_attachments (
  id UUID PRIMARY KEY,
  work_order_id VARCHAR(50) NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  object_key VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploaded_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NULL,
  CONSTRAINT fk_workorder_attachments_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id),
  CONSTRAINT ck_workorder_attachments_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_workorder_attachments_work_order_id ON workorder_attachments(work_order_id);

CREATE TABLE workorder_todos (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_order_id VARCHAR(50) NOT NULL,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  assigned_technician_id UUID,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  sort_order INTEGER NOT NULL DEFAULT 0,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  completed_at TIMESTAMPTZ,
  CONSTRAINT fk_workorder_todos_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT ck_workorder_todos_status CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','CANCELLED'))
);

CREATE INDEX idx_workorder_todos_work_order_id ON workorder_todos(work_order_id);
CREATE INDEX idx_workorder_todos_assigned_tech ON workorder_todos(assigned_technician_id);

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

CREATE TABLE workorder_acks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_order_id VARCHAR(50) NOT NULL,
  acknowledged_by UUID NOT NULL,
  acknowledged_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX uq_workorder_acks_work_order_id ON workorder_acks(work_order_id);

-- ============================================================================
-- 16. Blueprint Modul B: work_assignments & work_logs (AD-17/AD-18)
-- ============================================================================

CREATE TABLE work_assignments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  parent_type VARCHAR(24) NOT NULL,
  work_order_id VARCHAR(50) NOT NULL,
  technician_id UUID NOT NULL,
  assigned_by UUID,
  assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  dropped_at TIMESTAMPTZ,
  dropped_by UUID,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_work_assignments_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_work_assignments_technician FOREIGN KEY (technician_id) REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_work_assignments_assigned_by FOREIGN KEY (assigned_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_work_assignments_dropped_by FOREIGN KEY (dropped_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT ck_work_assignments_parent_type CHECK (parent_type IN ('CORRECTIVE_WO')),
  CONSTRAINT uq_work_assignments_wo_tech_at UNIQUE (work_order_id, technician_id, assigned_at)
);

CREATE INDEX idx_work_assignments_work_order_id ON work_assignments(work_order_id);
CREATE INDEX idx_work_assignments_technician_id ON work_assignments(technician_id);
CREATE INDEX idx_work_assignments_active ON work_assignments(work_order_id, is_active);

CREATE TABLE work_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_assignment_id UUID,
  work_order_id VARCHAR(50) NOT NULL,
  technician_id UUID NOT NULL,
  start_time TIMESTAMPTZ NOT NULL,
  end_time TIMESTAMPTZ,
  stopped_reason VARCHAR(24),
  activity_note VARCHAR(2000) NOT NULL,
  completion_note TEXT,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_work_logs_work_assignment FOREIGN KEY (work_assignment_id) REFERENCES work_assignments(id) ON DELETE SET NULL,
  CONSTRAINT fk_work_logs_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_work_logs_technician FOREIGN KEY (technician_id) REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT ck_work_logs_stopped_reason
    CHECK (stopped_reason IS NULL OR stopped_reason IN ('WAITING_SPAREPART','SHIFT_END','COMPLETED','OTHER')),
  CONSTRAINT ck_work_logs_end_after_start CHECK (end_time IS NULL OR end_time > start_time),
  CONSTRAINT ck_work_logs_activity_note_not_blank CHECK (btrim(activity_note) <> '')
);

CREATE INDEX idx_work_logs_work_order_id ON work_logs(work_order_id);
CREATE INDEX idx_work_logs_work_assignment_id ON work_logs(work_assignment_id);
CREATE INDEX idx_work_logs_technician_id ON work_logs(technician_id);

-- ============================================================================
-- 17. Blueprint Modul C: rating criteria + quality ratings
-- ============================================================================

CREATE TABLE work_log_rating_criteria (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  description TEXT,
  min_score INT NOT NULL DEFAULT 1,
  max_score INT NOT NULL DEFAULT 5,
  plant_id UUID REFERENCES plants(id) ON DELETE CASCADE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_work_log_rating_criteria_score_range CHECK (min_score < max_score),
  CONSTRAINT ck_work_log_rating_criteria_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE work_log_rating_criterion_categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  criterion_id UUID NOT NULL REFERENCES work_log_rating_criteria(id) ON DELETE CASCADE,
  category_id UUID NOT NULL REFERENCES work_order_categories(id) ON DELETE CASCADE,
  CONSTRAINT uq_work_log_rating_criterion_categories UNIQUE (criterion_id, category_id)
);

CREATE INDEX idx_work_log_rating_criterion_categories_category ON work_log_rating_criterion_categories(category_id);

CREATE TABLE work_log_ratings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_log_id UUID NOT NULL REFERENCES work_logs(id) ON DELETE CASCADE,
  criterion_id UUID NOT NULL REFERENCES work_log_rating_criteria(id) ON DELETE RESTRICT,
  score INT NOT NULL,
  rated_by UUID,
  rated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  remarks TEXT,
  CONSTRAINT fk_work_log_ratings_rated_by FOREIGN KEY (rated_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT ck_work_log_ratings_score_not_null CHECK (score IS NOT NULL),
  CONSTRAINT uq_work_log_ratings_log_criterion UNIQUE (work_log_id, criterion_id)
);

CREATE TABLE work_order_rating_criteria (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  description TEXT,
  min_score INT NOT NULL DEFAULT 1,
  max_score INT NOT NULL DEFAULT 5,
  plant_id UUID REFERENCES plants(id) ON DELETE CASCADE,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order INT NOT NULL DEFAULT 0,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_work_order_rating_criteria_score_range CHECK (min_score < max_score),
  CONSTRAINT ck_work_order_rating_criteria_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE work_order_rating_criterion_categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  criterion_id UUID NOT NULL REFERENCES work_order_rating_criteria(id) ON DELETE CASCADE,
  category_id UUID NOT NULL REFERENCES work_order_categories(id) ON DELETE CASCADE,
  CONSTRAINT uq_work_order_rating_criterion_categories UNIQUE (criterion_id, category_id)
);

CREATE INDEX idx_work_order_rating_criterion_categories_category ON work_order_rating_criterion_categories(category_id);

CREATE TABLE work_order_quality_ratings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  work_order_id VARCHAR(50) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  due_at TIMESTAMPTZ,
  submitted_at TIMESTAMPTZ,
  submitted_by UUID,
  cleanliness_score INT,
  tidiness_score INT,
  speed_score INT,
  remarks TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_work_order_quality_ratings_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_work_order_quality_ratings_submitted_by FOREIGN KEY (submitted_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT ck_work_order_quality_ratings_status CHECK (status IN ('PENDING','SUBMITTED','EXPIRED')),
  CONSTRAINT uq_work_order_quality_ratings_work_order UNIQUE (work_order_id)
);

CREATE TABLE work_order_quality_rating_technicians (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  quality_rating_id UUID NOT NULL REFERENCES work_order_quality_ratings(id) ON DELETE CASCADE,
  work_assignment_id UUID REFERENCES work_assignments(id) ON DELETE SET NULL,
  technician_id UUID NOT NULL REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT uq_work_order_quality_rating_technicians UNIQUE (quality_rating_id, technician_id)
);

CREATE INDEX idx_work_order_quality_rating_technicians_technician ON work_order_quality_rating_technicians(technician_id);

CREATE TABLE work_order_quality_rating_scores (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  quality_rating_id UUID NOT NULL REFERENCES work_order_quality_ratings(id) ON DELETE CASCADE,
  criterion_id UUID NOT NULL REFERENCES work_order_rating_criteria(id) ON DELETE RESTRICT,
  score INT NOT NULL,
  CONSTRAINT uq_work_order_quality_rating_scores UNIQUE (quality_rating_id, criterion_id)
);

-- ============================================================================
-- 18. Legacy preventive tables (V54/V55/V56) — KEPT as-is per spec Never-clause;
--     replacement happens in the PM redesign epic.
-- ============================================================================

CREATE TABLE preventive_programs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL,
  category VARCHAR(20) NOT NULL,
  schedule_type VARCHAR(10) NOT NULL,
  day_of_month SMALLINT NOT NULL,
  month_of_year SMALLINT,
  title VARCHAR(200) NOT NULL,
  description TEXT,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  auto_workorder BOOLEAN NOT NULL DEFAULT FALSE,
  created_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_programs_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
  CONSTRAINT ck_preventive_programs_category CHECK (category IN ('MECHANICAL','ELECTRICAL')),
  CONSTRAINT ck_preventive_programs_type CHECK (schedule_type IN ('MONTHLY','ANNUAL')),
  CONSTRAINT ck_preventive_programs_day CHECK (day_of_month BETWEEN 1 AND 31),
  CONSTRAINT ck_preventive_programs_month CHECK (month_of_year IS NULL OR month_of_year BETWEEN 1 AND 12)
);

CREATE INDEX idx_preventive_programs_machine ON preventive_programs(machine_id);

CREATE TABLE preventive_schedules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  program_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  due_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
  completed_at TIMESTAMPTZ,
  performed_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_schedules_program FOREIGN KEY (program_id)
    REFERENCES preventive_programs(id) ON DELETE CASCADE,
  CONSTRAINT ck_preventive_schedules_status CHECK (status IN ('SCHEDULED','IN_PROGRESS','PERFORMED','SKIPPED')),
  CONSTRAINT uq_preventive_schedules_period UNIQUE (program_id, due_date)
);

CREATE INDEX idx_preventive_schedules_status_due ON preventive_schedules(status, due_date);
CREATE INDEX idx_preventive_schedules_machine ON preventive_schedules(machine_id);

-- work_orders.preventive_schedule_id FK now that preventive_schedules exists.
ALTER TABLE work_orders ADD CONSTRAINT fk_work_orders_preventive_schedule
  FOREIGN KEY (preventive_schedule_id) REFERENCES preventive_schedules(id) ON DELETE SET NULL;

CREATE TABLE preventive_checklist_results (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schedule_id UUID NOT NULL,
  performed_by UUID NOT NULL,
  completed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  notes TEXT,
  leader_id UUID,
  assessment TEXT,
  approved_at TIMESTAMPTZ,
  signature_object_key VARCHAR(512),
  signer_identity VARCHAR(200),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_checklist_results_schedule FOREIGN KEY (schedule_id)
    REFERENCES preventive_schedules(id) ON DELETE CASCADE,
  CONSTRAINT uq_preventive_checklist_schedule UNIQUE (schedule_id)
);

CREATE INDEX idx_preventive_checklist_schedule ON preventive_checklist_results(schedule_id);

CREATE TABLE preventive_checklist_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  result_id UUID NOT NULL,
  position SMALLINT NOT NULL,
  label VARCHAR(200) NOT NULL,
  value TEXT,
  lsl NUMERIC,
  usl NUMERIC,
  note TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_preventive_checklist_items_result FOREIGN KEY (result_id)
    REFERENCES preventive_checklist_results(id) ON DELETE CASCADE
);

CREATE INDEX idx_preventive_checklist_items_result ON preventive_checklist_items(result_id);

CREATE TABLE preventive_schedule_attachments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schedule_id UUID NOT NULL,
  filename VARCHAR(255) NOT NULL,
  content_type VARCHAR(100) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  size_bytes BIGINT NOT NULL,
  uploaded_by UUID NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NULL,
  CONSTRAINT fk_preventive_schedule_attachments_schedule FOREIGN KEY (schedule_id)
    REFERENCES preventive_schedules(id) ON DELETE CASCADE,
  CONSTRAINT ck_preventive_schedule_attachments_size_non_negative CHECK (size_bytes >= 0)
);

CREATE INDEX idx_preventive_schedule_attachments_schedule ON preventive_schedule_attachments(schedule_id);

-- ============================================================================
-- 19. Blueprint Modul F: PM execution model (F1..F8)
-- ============================================================================

CREATE TABLE pm_frequencies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code VARCHAR(50) NOT NULL,
  name VARCHAR(200) NOT NULL,
  description TEXT,
  sort_order INT NOT NULL DEFAULT 0,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_pm_frequencies_code UNIQUE (code),
  CONSTRAINT ck_pm_frequencies_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_pm_frequencies_name_not_blank CHECK (btrim(name) <> '')
);

CREATE TABLE pm_checksheets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL REFERENCES machines(id) ON DELETE RESTRICT,
  frequency_id UUID NOT NULL REFERENCES pm_frequencies(id) ON DELETE RESTRICT,
  revision_no INT NOT NULL DEFAULT 1,
  revision_reason TEXT,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  supersedes UUID,
  approved_by UUID,
  approved_at TIMESTAMPTZ,
  effective_date DATE,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_checksheets_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_checksheets_frequency FOREIGN KEY (frequency_id) REFERENCES pm_frequencies(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_checksheets_supersedes FOREIGN KEY (supersedes) REFERENCES pm_checksheets(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_checksheets_approved_by FOREIGN KEY (approved_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_pm_checksheets_machine_frequency_revision UNIQUE (machine_id, frequency_id, revision_no)
);

CREATE INDEX idx_pm_checksheets_machine ON pm_checksheets(machine_id);
CREATE INDEX idx_pm_checksheets_frequency ON pm_checksheets(frequency_id);

CREATE TABLE active_checksheets (
  machine_id UUID NOT NULL,
  frequency_id UUID NOT NULL,
  checksheet_id UUID NOT NULL,
  CONSTRAINT pk_active_checksheets PRIMARY KEY (machine_id, frequency_id),
  CONSTRAINT fk_active_checksheets_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT fk_active_checksheets_frequency FOREIGN KEY (frequency_id) REFERENCES pm_frequencies(id) ON DELETE CASCADE,
  CONSTRAINT fk_active_checksheets_checksheet FOREIGN KEY (checksheet_id) REFERENCES pm_checksheets(id) ON DELETE CASCADE,
  CONSTRAINT uq_active_checksheets_checksheet UNIQUE (checksheet_id)
);

CREATE TABLE pm_checklist_categories (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  checksheet_id UUID NOT NULL REFERENCES pm_checksheets(id) ON DELETE CASCADE,
  name VARCHAR(200) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_pm_checklist_categories_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_pm_checklist_categories_checksheet ON pm_checklist_categories(checksheet_id);

CREATE TABLE pm_checklist_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  checksheet_id UUID NOT NULL,
  category_id UUID,
  sequence INT NOT NULL DEFAULT 0,
  parameter_text VARCHAR(500) NOT NULL,
  check_method VARCHAR(200),
  input_type VARCHAR(16) NOT NULL DEFAULT 'OK_NG',
  unit VARCHAR(50),
  lsl NUMERIC,
  nominal NUMERIC,
  usl NUMERIC,
  is_critical_flag BOOLEAN NOT NULL DEFAULT FALSE,
  reference_document VARCHAR(255),
  calibration_instrument_id UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_checklist_items_checksheet FOREIGN KEY (checksheet_id) REFERENCES pm_checksheets(id) ON DELETE CASCADE,
  CONSTRAINT fk_pm_checklist_items_category FOREIGN KEY (category_id) REFERENCES pm_checklist_categories(id) ON DELETE SET NULL,
  CONSTRAINT ck_pm_checklist_items_input_type CHECK (input_type IN ('MEASUREMENT','OK_NG')),
  CONSTRAINT ck_pm_checklist_items_parameter_not_blank CHECK (btrim(parameter_text) <> '')
);

CREATE INDEX idx_pm_checklist_items_checksheet ON pm_checklist_items(checksheet_id);
CREATE INDEX idx_pm_checklist_items_category ON pm_checklist_items(category_id);

-- The calibration_instrument FK on pm_checklist_items is added after
-- calibration_instruments exists (Modul H section below).

CREATE TABLE pm_schedules (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  checksheet_id UUID NOT NULL,
  checksheet_revision_no INT NOT NULL,
  frequency_id UUID NOT NULL,
  frequency_code VARCHAR(50) NOT NULL,
  frequency_name VARCHAR(200) NOT NULL,
  year INT NOT NULL,
  status VARCHAR(28) NOT NULL DEFAULT 'DRAFT',
  submitted_by UUID,
  submitted_at TIMESTAMPTZ,
  approved_by_spv UUID,
  approved_at_spv TIMESTAMPTZ,
  approved_by_prod UUID,
  approved_at_prod TIMESTAMPTZ,
  warnings JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_schedules_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_schedules_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_schedules_checksheet FOREIGN KEY (checksheet_id) REFERENCES pm_checksheets(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_schedules_submitted_by FOREIGN KEY (submitted_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_schedules_approved_by_spv FOREIGN KEY (approved_by_spv) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_schedules_approved_by_prod FOREIGN KEY (approved_by_prod) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_pm_schedules_plant_machine_checksheet_year UNIQUE (plant_id, machine_id, checksheet_id, year),
  CONSTRAINT ck_pm_schedules_status CHECK (status IN ('DRAFT','PENDING_SPV_APPROVAL','PENDING_PRODUCTION_APPROVAL','APPROVED','ACTIVE')),
  CONSTRAINT ck_pm_schedules_year CHECK (year BETWEEN 2000 AND 2999)
);

CREATE INDEX idx_pm_schedules_machine ON pm_schedules(machine_id);
CREATE INDEX idx_pm_schedules_status ON pm_schedules(status);

CREATE TABLE pm_schedule_dates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  schedule_id UUID NOT NULL,
  planned_date DATE NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_schedule_dates_schedule FOREIGN KEY (schedule_id) REFERENCES pm_schedules(id) ON DELETE CASCADE,
  CONSTRAINT uq_pm_schedule_dates_schedule_planned_date UNIQUE (schedule_id, planned_date),
  CONSTRAINT ck_pm_schedule_dates_status CHECK (status IN ('SCHEDULED','EXECUTED','MISSED','RESCHEDULED'))
);

CREATE INDEX idx_pm_schedule_dates_schedule ON pm_schedule_dates(schedule_id);

CREATE TABLE pm_work_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL,
  template_id UUID,
  frequency_id UUID,
  frequency_code VARCHAR(50),
  frequency_name VARCHAR(200),
  template_revision INT,
  status VARCHAR(16) NOT NULL DEFAULT 'SCHEDULED',
  assigned_technician_id UUID,
  scheduled_date DATE,
  started_at TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  certificate_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_work_orders_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_work_orders_frequency FOREIGN KEY (frequency_id) REFERENCES pm_frequencies(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_work_orders_assigned_technician FOREIGN KEY (assigned_technician_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT ck_pm_work_orders_status CHECK (status IN ('SCHEDULED','ASSIGNED','IN_PROGRESS','COMPLETED','OVERDUE'))
);

CREATE INDEX idx_pm_work_orders_machine ON pm_work_orders(machine_id);
CREATE INDEX idx_pm_work_orders_status ON pm_work_orders(status);
CREATE INDEX idx_pm_work_orders_scheduled_date ON pm_work_orders(scheduled_date);

CREATE TABLE pm_executions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  pm_wo_id UUID NOT NULL,
  schedule_date_id UUID,
  technician_id UUID NOT NULL,
  spv_verifier_id UUID,
  technician_signature_id UUID,
  technician_signed_at TIMESTAMPTZ,
  spv_signature_id UUID,
  spv_signed_at TIMESTAMPTZ,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  has_ng_items BOOLEAN NOT NULL DEFAULT FALSE,
  ng_count INT NOT NULL DEFAULT 0,
  finding_wo_id VARCHAR(50),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_executions_pm_wo FOREIGN KEY (pm_wo_id) REFERENCES pm_work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_pm_executions_schedule_date FOREIGN KEY (schedule_date_id) REFERENCES pm_schedule_dates(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_executions_technician FOREIGN KEY (technician_id) REFERENCES auth_users(id) ON DELETE RESTRICT,
  CONSTRAINT fk_pm_executions_spv_verifier FOREIGN KEY (spv_verifier_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_executions_finding_wo FOREIGN KEY (finding_wo_id) REFERENCES work_orders(id) ON DELETE SET NULL,
  CONSTRAINT uq_pm_executions_pm_wo UNIQUE (pm_wo_id),
  CONSTRAINT uq_pm_executions_schedule_date UNIQUE (schedule_date_id)
);

CREATE INDEX idx_pm_executions_technician ON pm_executions(technician_id);

CREATE TABLE pm_execution_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  execution_id UUID NOT NULL,
  checklist_item_id UUID,
  sequence INT NOT NULL DEFAULT 0,
  category_name VARCHAR(200),
  parameter_text VARCHAR(500),
  check_method VARCHAR(200),
  input_type VARCHAR(16),
  is_critical_flag BOOLEAN NOT NULL DEFAULT FALSE,
  unit VARCHAR(50),
  lsl NUMERIC,
  nominal NUMERIC,
  usl NUMERIC,
  actual_value NUMERIC,
  is_ok BOOLEAN,
  is_ng BOOLEAN NOT NULL DEFAULT FALSE,
  ng_notes TEXT,
  ng_photo_url TEXT,
  is_blocked BOOLEAN NOT NULL DEFAULT FALSE,
  blocked_wo_code VARCHAR(50),
  blocking_wo_id VARCHAR(50),
  ng_resolved_at TIMESTAMPTZ,
  ng_resolution_notes TEXT,
  spv_verified_at TIMESTAMPTZ,
  spv_verifier_id UUID,
  spv_signature_id UUID,
  filled_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_pm_execution_items_execution FOREIGN KEY (execution_id) REFERENCES pm_executions(id) ON DELETE CASCADE,
  CONSTRAINT fk_pm_execution_items_checklist_item FOREIGN KEY (checklist_item_id) REFERENCES pm_checklist_items(id) ON DELETE SET NULL,
  CONSTRAINT fk_pm_execution_items_blocking_wo FOREIGN KEY (blocking_wo_id) REFERENCES work_orders(id) ON DELETE SET NULL,
  CONSTRAINT ck_pm_execution_items_input_type
    CHECK (input_type IS NULL OR input_type IN ('MEASUREMENT','OK_NG'))
);

CREATE INDEX idx_pm_execution_items_execution ON pm_execution_items(execution_id);
CREATE INDEX idx_pm_execution_items_checklist_item ON pm_execution_items(checklist_item_id);

-- ============================================================================
-- 20. Blueprint Modul E: inventory (E1..E4) — replaces sparepart_stock (DP3)
-- ============================================================================

CREATE TABLE inventory_locations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_inventory_locations_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT uq_inventory_locations_plant_code UNIQUE (plant_id, code),
  CONSTRAINT ck_inventory_locations_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_inventory_locations_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_inventory_locations_plant_id ON inventory_locations(plant_id);

CREATE TABLE inventory_stock_balances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sparepart_id UUID NOT NULL,
  location_id UUID NOT NULL,
  available NUMERIC(18,2) NOT NULL DEFAULT 0,
  reserved NUMERIC(18,2) NOT NULL DEFAULT 0,
  consumed NUMERIC(18,2) NOT NULL DEFAULT 0,
  minimum_stock NUMERIC(18,2) NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_inventory_stock_balances_sparepart FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_stock_balances_location FOREIGN KEY (location_id) REFERENCES inventory_locations(id) ON DELETE RESTRICT,
  CONSTRAINT uq_inventory_stock_balances_sparepart_location UNIQUE (sparepart_id, location_id),
  CONSTRAINT ck_inventory_stock_balances_non_negative
    CHECK (available >= 0 AND reserved >= 0 AND consumed >= 0 AND minimum_stock >= 0)
);

CREATE INDEX idx_inventory_stock_balances_location_id ON inventory_stock_balances(location_id);
CREATE INDEX idx_inventory_stock_balances_sparepart_id ON inventory_stock_balances(sparepart_id);

CREATE TABLE inventory_transfers (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sparepart_id UUID NOT NULL,
  source_location_id UUID NOT NULL,
  destination_location_id UUID NOT NULL,
  quantity NUMERIC(18,2) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING_APPROVAL',
  requested_by UUID,
  reviewed_by UUID,
  rejection_reason TEXT,
  reviewed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_inventory_transfers_sparepart FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_transfers_source_location FOREIGN KEY (source_location_id) REFERENCES inventory_locations(id) ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_transfers_destination_location FOREIGN KEY (destination_location_id) REFERENCES inventory_locations(id) ON DELETE RESTRICT,
  CONSTRAINT ck_inventory_transfers_quantity_positive CHECK (quantity > 0),
  CONSTRAINT ck_inventory_transfers_status CHECK (status IN ('PENDING_APPROVAL','APPROVED','REJECTED')),
  CONSTRAINT ck_inventory_transfers_locations_differ CHECK (source_location_id <> destination_location_id)
);

CREATE INDEX idx_inventory_transfers_sparepart ON inventory_transfers(sparepart_id);
CREATE INDEX idx_inventory_transfers_status ON inventory_transfers(status);

CREATE TABLE inventory_reservations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  sparepart_id UUID NOT NULL,
  location_id UUID NOT NULL,
  quantity NUMERIC(18,2) NOT NULL,
  remaining_quantity NUMERIC(18,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  reference_type VARCHAR(50),
  reference_id VARCHAR(64),
  requested_by UUID,
  consumed_by UUID,
  cancelled_by UUID,
  expires_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_inventory_reservations_sparepart FOREIGN KEY (sparepart_id) REFERENCES spareparts(id) ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_reservations_location FOREIGN KEY (location_id) REFERENCES inventory_locations(id) ON DELETE RESTRICT,
  CONSTRAINT fk_inventory_reservations_requested_by FOREIGN KEY (requested_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_inventory_reservations_consumed_by FOREIGN KEY (consumed_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_inventory_reservations_cancelled_by FOREIGN KEY (cancelled_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT ck_inventory_reservations_quantity_positive CHECK (quantity > 0),
  CONSTRAINT ck_inventory_reservations_remaining_non_negative CHECK (remaining_quantity >= 0),
  CONSTRAINT ck_inventory_reservations_status CHECK (status IN ('ACTIVE','CONSUMED','CANCELLED','EXPIRED'))
);

CREATE INDEX idx_inventory_reservations_sparepart ON inventory_reservations(sparepart_id);
CREATE INDEX idx_inventory_reservations_status ON inventory_reservations(status);
CREATE INDEX idx_inventory_reservations_reference ON inventory_reservations(reference_type, reference_id);

-- ============================================================================
-- 21. Blueprint Modul G: KPI materialization (G1..G7)
-- ============================================================================

CREATE TABLE kpi_targets (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  month DATE NOT NULL,
  monthly_breakdown_target INT,
  mtbf_target_days NUMERIC(12,2),
  mttr_target_minutes NUMERIC(12,2),
  oee_quality_percent NUMERIC(5,2),
  oee_performance_percent NUMERIC(5,2),
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_targets_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_targets_plant_month UNIQUE (plant_id, month)
);

CREATE TABLE kpi_monthly_breakdowns (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  month DATE NOT NULL,
  count INT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_monthly_breakdowns_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_monthly_breakdowns_plant_month UNIQUE (plant_id, month)
);

CREATE TABLE kpi_mtbf_monthlies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  machine_id UUID NOT NULL,
  month DATE NOT NULL,
  mtbf_days NUMERIC(12,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_mtbf_monthlies_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT fk_kpi_mtbf_monthlies_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_mtbf_monthlies_machine_month UNIQUE (machine_id, month)
);

CREATE INDEX idx_kpi_mtbf_monthlies_plant_month ON kpi_mtbf_monthlies(plant_id, month);

CREATE TABLE kpi_mttr_monthlies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  month DATE NOT NULL,
  wall_clock_mttr_minutes NUMERIC(12,2),
  actual_working_mttr_minutes NUMERIC(12,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_mttr_monthlies_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_mttr_monthlies_plant_month UNIQUE (plant_id, month)
);

CREATE TABLE kpi_mar_monthlies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  month DATE NOT NULL,
  planned_available_minutes INT NOT NULL DEFAULT 0,
  downtime_minutes INT NOT NULL DEFAULT 0,
  mar_percent NUMERIC(5,2),
  source_status VARCHAR(20),
  source_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_mar_monthlies_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_mar_monthlies_plant_month UNIQUE (plant_id, month)
);

CREATE TABLE kpi_technician_monthlies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  technician_id UUID NOT NULL,
  month DATE NOT NULL,
  average_rating NUMERIC(4,2),
  total_wo INT NOT NULL DEFAULT 0,
  first_time_fix_rate NUMERIC(5,2),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_technician_monthlies_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT fk_kpi_technician_monthlies_technician FOREIGN KEY (technician_id) REFERENCES auth_users(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_technician_monthlies UNIQUE (plant_id, technician_id, month)
);

CREATE TABLE kpi_pm_completion_monthlies (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  month DATE NOT NULL,
  completion_rate NUMERIC(5,2),
  completed_count INT NOT NULL DEFAULT 0,
  planned_count INT NOT NULL DEFAULT 0,
  source_status VARCHAR(20),
  source_message TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_kpi_pm_completion_monthlies_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_kpi_pm_completion_monthlies_plant_month UNIQUE (plant_id, month)
);

CREATE TABLE kpi_aggregate_refresh_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  refresh_key VARCHAR(200) NOT NULL,
  refreshed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  status VARCHAR(20) NOT NULL,
  message TEXT,
  CONSTRAINT uq_kpi_aggregate_refresh_logs_refresh_key UNIQUE (refresh_key),
  CONSTRAINT ck_kpi_aggregate_refresh_logs_status CHECK (status IN ('RUNNING','SUCCESS','FAILED'))
);

-- ============================================================================
-- 22. Blueprint Modul H: IATF compliance (H1..H7)
-- ============================================================================

CREATE TABLE non_conformances (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id VARCHAR(64),
  work_order_id VARCHAR(50),
  machine_id UUID,
  nc_number VARCHAR(50) NOT NULL,
  description TEXT NOT NULL,
  root_cause TEXT,
  corrective_action TEXT,
  responsible_id UUID,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  target_close_date DATE,
  closed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_non_conformances_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE SET NULL,
  CONSTRAINT fk_non_conformances_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE SET NULL,
  CONSTRAINT fk_non_conformances_responsible FOREIGN KEY (responsible_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_non_conformances_nc_number UNIQUE (nc_number),
  CONSTRAINT ck_non_conformances_status CHECK (status IN ('OPEN','IN_PROGRESS','CLOSED','VERIFIED'))
);

CREATE INDEX idx_non_conformances_status ON non_conformances(status);
CREATE INDEX idx_non_conformances_machine ON non_conformances(machine_id);

CREATE TABLE eight_d_reports (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  nc_id UUID NOT NULL,
  report_number VARCHAR(50) NOT NULL,
  d1_team JSONB,
  d2_description TEXT,
  d3_containment TEXT,
  d4_root_cause JSONB,
  d5_ca_permanent TEXT,
  d6_implementation TEXT,
  d7_lesson_learned TEXT,
  d8_closure_notes TEXT,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  effectiveness_verified_at TIMESTAMPTZ,
  pdf_artifact_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_eight_d_reports_nc FOREIGN KEY (nc_id) REFERENCES non_conformances(id) ON DELETE CASCADE,
  CONSTRAINT uq_eight_d_reports_nc UNIQUE (nc_id),
  CONSTRAINT uq_eight_d_reports_report_number UNIQUE (report_number),
  CONSTRAINT ck_eight_d_reports_status CHECK (status IN ('DRAFT','IN_PROGRESS','CLOSED','EFFECTIVE','INEFFECTIVE'))
);

CREATE TABLE calibration_instruments (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  instrument_code VARCHAR(64) NOT NULL,
  name VARCHAR(255) NOT NULL,
  model VARCHAR(255),
  serial_number VARCHAR(255),
  location VARCHAR(255),
  calibration_frequency_days INT NOT NULL,
  last_calibration_date DATE,
  next_calibration_date DATE NOT NULL,
  calibration_body VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'VALID',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_calibration_instruments_code UNIQUE (instrument_code),
  CONSTRAINT ck_calibration_instruments_frequency_positive CHECK (calibration_frequency_days > 0),
  CONSTRAINT ck_calibration_instruments_status CHECK (status IN ('VALID','EXPIRING_SOON','EXPIRED'))
);

CREATE TABLE calibration_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  instrument_id UUID NOT NULL,
  calibration_date DATE NOT NULL,
  next_calibration_date DATE NOT NULL,
  calibration_body VARCHAR(255),
  certificate_number VARCHAR(255),
  certificate_url TEXT,
  result VARCHAR(255),
  notes TEXT,
  recorded_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_calibration_records_instrument FOREIGN KEY (instrument_id) REFERENCES calibration_instruments(id) ON DELETE CASCADE,
  CONSTRAINT fk_calibration_records_recorded_by FOREIGN KEY (recorded_by) REFERENCES auth_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_calibration_records_instrument ON calibration_records(instrument_id);

-- Deferred FK from Modul F (pm_checklist_items.calibration_instrument_id).
ALTER TABLE pm_checklist_items ADD CONSTRAINT fk_pm_checklist_items_calibration_instrument
  FOREIGN KEY (calibration_instrument_id) REFERENCES calibration_instruments(id) ON DELETE SET NULL;

CREATE TABLE equipment_change_notices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  ecn_number VARCHAR(50) NOT NULL,
  machine_id UUID NOT NULL,
  title VARCHAR(255) NOT NULL,
  description TEXT,
  change_type VARCHAR(50),
  justification TEXT,
  status VARCHAR(16) NOT NULL DEFAULT 'DRAFT',
  submitted_by UUID,
  reviewed_by UUID,
  approved_by UUID,
  effective_date DATE,
  executed_wo_id VARCHAR(50),
  sign_off_at TIMESTAMPTZ,
  before_photo_url TEXT,
  after_photo_url TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_equipment_change_notices_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE RESTRICT,
  CONSTRAINT fk_equipment_change_notices_submitted_by FOREIGN KEY (submitted_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_equipment_change_notices_reviewed_by FOREIGN KEY (reviewed_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_equipment_change_notices_approved_by FOREIGN KEY (approved_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_equipment_change_notices_executed_wo FOREIGN KEY (executed_wo_id) REFERENCES work_orders(id) ON DELETE SET NULL,
  CONSTRAINT uq_equipment_change_notices_ecn_number UNIQUE (ecn_number),
  CONSTRAINT ck_equipment_change_notices_status
    CHECK (status IN ('DRAFT','UNDER_REVIEW','APPROVED','EXECUTED','CLOSED'))
);

CREATE INDEX idx_equipment_change_notices_machine ON equipment_change_notices(machine_id);

CREATE TABLE machine_setup_baselines (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL,
  ecn_id UUID,
  version INT NOT NULL,
  parameters JSONB,
  validated_by UUID,
  validated_at TIMESTAMPTZ,
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_setup_baselines_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT fk_machine_setup_baselines_ecn FOREIGN KEY (ecn_id) REFERENCES equipment_change_notices(id) ON DELETE SET NULL,
  CONSTRAINT fk_machine_setup_baselines_validated_by FOREIGN KEY (validated_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_machine_setup_baselines_machine_ecn_version UNIQUE (machine_id, ecn_id, version),
  CONSTRAINT uq_machine_setup_baselines_ecn UNIQUE (ecn_id)
);

CREATE INDEX idx_machine_setup_baselines_machine ON machine_setup_baselines(machine_id);

CREATE TABLE lesson_learned (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id VARCHAR(64) NOT NULL,
  machine_id UUID,
  project_type VARCHAR(50),
  title VARCHAR(255) NOT NULL,
  problem_summary TEXT NOT NULL,
  root_cause TEXT,
  solution TEXT,
  spareparts_used JSONB,
  duration_days INT,
  re_cycle_count INT,
  tags JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_lesson_learned_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE SET NULL,
  CONSTRAINT uq_lesson_learned_project UNIQUE (project_id)
);

CREATE INDEX idx_lesson_learned_machine ON lesson_learned(machine_id);

CREATE TABLE historical_machine_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  machine_id UUID NOT NULL,
  import_batch_id VARCHAR(64),
  source_file_name VARCHAR(255),
  source_row_number INT,
  happened_at TIMESTAMPTZ,
  title VARCHAR(255) NOT NULL,
  problem_summary TEXT,
  root_cause TEXT,
  solution TEXT,
  lesson_learned TEXT,
  downtime_minutes INT,
  tags JSONB,
  raw_payload JSONB,
  imported_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_historical_machine_records_machine FOREIGN KEY (machine_id) REFERENCES machines(id) ON DELETE CASCADE,
  CONSTRAINT fk_historical_machine_records_imported_by FOREIGN KEY (imported_by) REFERENCES auth_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_historical_machine_records_machine ON historical_machine_records(machine_id);
CREATE INDEX idx_historical_machine_records_batch ON historical_machine_records(import_batch_id);

-- ============================================================================
-- 23. Blueprint Modul I: signatures, auth audit, phone verification, webhooks,
--     WhatsApp logs (I1..I5) — user_signatures + signature_uses replace
--     workorder_signatures (DP4).
-- ============================================================================

CREATE TABLE user_signatures (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  bucket VARCHAR(255) NOT NULL,
  object_key VARCHAR(512) NOT NULL,
  content_type VARCHAR(100),
  signature_failed_attempts INT NOT NULL DEFAULT 0,
  signature_blocked_until TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_user_signatures_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
  CONSTRAINT uq_user_signatures_user UNIQUE (user_id)
);

CREATE TABLE signature_uses (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  signer_id UUID,
  signature_id UUID,
  module VARCHAR(50) NOT NULL,
  subject_type VARCHAR(50) NOT NULL,
  subject_id VARCHAR(50) NOT NULL,
  action VARCHAR(100) NOT NULL,
  reason TEXT,
  signature_bucket VARCHAR(255),
  signature_object_key VARCHAR(512),
  signature_sha256 VARCHAR(64),
  signed_artifact_bucket VARCHAR(255),
  signed_artifact_key VARCHAR(512),
  ip_address VARCHAR(64),
  user_agent TEXT,
  signed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_signature_uses_signer FOREIGN KEY (signer_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT fk_signature_uses_signature FOREIGN KEY (signature_id) REFERENCES user_signatures(id) ON DELETE SET NULL
);

CREATE INDEX idx_signature_uses_subject ON signature_uses (module, subject_type, subject_id);
CREATE INDEX idx_signature_uses_signer ON signature_uses(signer_id);
CREATE INDEX idx_signature_uses_signed_at ON signature_uses(signed_at);
-- One workorder signature per subject (legacy uq_workorder_signatures_work_order).
CREATE UNIQUE INDEX uq_signature_uses_work_order ON signature_uses (subject_id)
  WHERE subject_type = 'WORK_ORDER';

CREATE TABLE auth_login_audits (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID,
  identifier VARCHAR(255) NOT NULL,
  ip_address VARCHAR(64),
  user_agent TEXT,
  was_success BOOLEAN NOT NULL,
  failure_reason VARCHAR(255),
  occurred_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_auth_login_audits_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE SET NULL
);

CREATE INDEX idx_auth_login_audits_user_id ON auth_login_audits(user_id);
CREATE INDEX idx_auth_login_audits_identifier ON auth_login_audits(identifier);
CREATE INDEX idx_auth_login_audits_occurred_at ON auth_login_audits(occurred_at);

CREATE TABLE phone_verification_challenges (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL,
  pending_phone VARCHAR(32) NOT NULL,
  otp_hash VARCHAR(255) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  attempt_count INT NOT NULL DEFAULT 0,
  max_attempts INT NOT NULL DEFAULT 5,
  resend_available_at TIMESTAMPTZ,
  consumed_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_phone_verification_challenges_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
  CONSTRAINT ck_phone_verification_challenges_max_attempts CHECK (max_attempts > 0),
  CONSTRAINT ck_phone_verification_challenges_attempt_non_negative CHECK (attempt_count >= 0)
);

CREATE INDEX idx_phone_verification_challenges_user ON phone_verification_challenges(user_id);

CREATE TABLE webhook_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  direction VARCHAR(12) NOT NULL,
  event_types JSONB,
  endpoint_url TEXT,
  hmac_secret VARCHAR(512),
  is_active BOOLEAN NOT NULL DEFAULT TRUE,
  created_by UUID,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_webhook_configs_created_by FOREIGN KEY (created_by) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_webhook_configs_name UNIQUE (name),
  CONSTRAINT ck_webhook_configs_direction CHECK (direction IN ('INBOUND','OUTBOUND'))
);

CREATE TABLE webhook_delivery_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  webhook_config_id UUID NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB,
  response_code INT,
  response_body TEXT,
  latency_ms INT,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempt_count INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_webhook_delivery_logs_config FOREIGN KEY (webhook_config_id) REFERENCES webhook_configs(id) ON DELETE CASCADE,
  CONSTRAINT ck_webhook_delivery_logs_status CHECK (status IN ('PENDING','DELIVERED','FAILED','RETRYING','DLQ'))
);

CREATE INDEX idx_webhook_delivery_logs_config ON webhook_delivery_logs(webhook_config_id);
CREATE INDEX idx_webhook_delivery_logs_status ON webhook_delivery_logs(status, next_retry_at);

CREATE TABLE whatsapp_message_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  waha_message_id VARCHAR(255) NOT NULL,
  session VARCHAR(100),
  chat_id VARCHAR(255),
  from_phone VARCHAR(32),
  text TEXT,
  attachment_url TEXT,
  payload JSONB,
  work_order_id VARCHAR(50),
  user_id UUID,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_whatsapp_message_logs_work_order FOREIGN KEY (work_order_id) REFERENCES work_orders(id) ON DELETE SET NULL,
  CONSTRAINT fk_whatsapp_message_logs_user FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE SET NULL,
  CONSTRAINT uq_whatsapp_message_logs_waha_message_id UNIQUE (waha_message_id)
);

CREATE INDEX idx_whatsapp_message_logs_work_order ON whatsapp_message_logs(work_order_id);
CREATE INDEX idx_whatsapp_message_logs_received_at ON whatsapp_message_logs(received_at);

-- ============================================================================
-- 24. Telemetry quarantine & WAHA templates (legacy V9/V21)
-- ============================================================================

CREATE TABLE telemetry_quarantine (
  id UUID PRIMARY KEY,
  trace_id VARCHAR(255) NOT NULL,
  topic TEXT NOT NULL,
  raw_payload TEXT NOT NULL,
  rejection_reason VARCHAR(100) NOT NULL,
  rejection_field VARCHAR(100),
  received_at TIMESTAMPTZ NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_telemetry_quarantine_received_at ON telemetry_quarantine(received_at DESC);
CREATE INDEX idx_telemetry_quarantine_trace_id ON telemetry_quarantine(trace_id);

CREATE TABLE waha_templates (
  id UUID PRIMARY KEY,
  template_key VARCHAR(64) NOT NULL,
  body TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE UNIQUE INDEX waha_templates_template_key_idx ON waha_templates (template_key);

-- ============================================================================
-- 25. Sparepart alerts (legacy V19/V33/V35/V41 final state)
-- ============================================================================

CREATE TABLE sparepart_alerts (
  id UUID PRIMARY KEY,
  machine_id UUID NOT NULL,
  machine_sparepart_installation_id UUID NOT NULL,
  alert_type VARCHAR(24) NOT NULL DEFAULT 'THRESHOLD_PERCENTAGE',
  threshold_percentage INT,
  current_counter_snapshot BIGINT,
  consumed_production_count_snapshot BIGINT,
  consumed_percentage_snapshot NUMERIC(7,2),
  lead_time_hours NUMERIC(12, 2),
  rate_per_operating_hour NUMERIC(18, 2),
  calculation_basis VARCHAR(24),
  projected_depletion_at TIMESTAMPTZ,
  trace_id VARCHAR(64) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN',
  status_reason VARCHAR(255),
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT fk_sparepart_alerts_machine FOREIGN KEY (machine_id) REFERENCES machines(id),
  CONSTRAINT fk_sparepart_alerts_installation FOREIGN KEY (machine_sparepart_installation_id) REFERENCES machine_sparepart_installations(id),
  CONSTRAINT chk_sparepart_alerts_threshold_percentage CHECK (threshold_percentage BETWEEN 0 AND 100),
  CONSTRAINT chk_sparepart_alerts_alert_type
    CHECK (alert_type IN ('THRESHOLD_PERCENTAGE', 'PROCUREMENT_RISK')),
  CONSTRAINT chk_sparepart_alerts_threshold_required
    CHECK (alert_type = 'PROCUREMENT_RISK' OR threshold_percentage IS NOT NULL),
  CONSTRAINT chk_sparepart_alerts_procurement_evidence
    CHECK (alert_type <> 'PROCUREMENT_RISK' OR (
      lead_time_hours IS NOT NULL
      AND rate_per_operating_hour IS NOT NULL
      AND calculation_basis IS NOT NULL
      AND projected_depletion_at IS NOT NULL
    )),
  CONSTRAINT chk_sparepart_alerts_calc_basis
    CHECK (calculation_basis IS NULL OR calculation_basis IN ('ROLLING_30_DAY', 'FULL_HISTORY')),
  CONSTRAINT chk_sparepart_alerts_threshold_snapshots
    CHECK (alert_type = 'PROCUREMENT_RISK' OR (
      current_counter_snapshot IS NOT NULL
      AND consumed_production_count_snapshot IS NOT NULL
      AND consumed_percentage_snapshot IS NOT NULL
    )),
  CONSTRAINT chk_sparepart_alerts_proc_threshold_null
    CHECK (alert_type <> 'PROCUREMENT_RISK' OR threshold_percentage IS NULL),
  CONSTRAINT chk_sparepart_alerts_proc_snapshots_null
    CHECK (alert_type <> 'PROCUREMENT_RISK' OR (
      current_counter_snapshot IS NULL
      AND consumed_production_count_snapshot IS NULL
      AND consumed_percentage_snapshot IS NULL
    ))
);

-- THRESHOLD dedupe: one non-RESOLVED threshold alert per (installation, threshold).
CREATE UNIQUE INDEX sparepart_alerts_dedup_idx
  ON sparepart_alerts (machine_sparepart_installation_id, threshold_percentage)
  WHERE status != 'RESOLVED';
-- PROCUREMENT_RISK dedupe: one non-RESOLVED procurement-risk alert per installation.
CREATE UNIQUE INDEX sparepart_alerts_proc_risk_dedup_idx
  ON sparepart_alerts (machine_sparepart_installation_id)
  WHERE status != 'RESOLVED' AND alert_type = 'PROCUREMENT_RISK';
CREATE INDEX sparepart_alerts_machine_id_idx ON sparepart_alerts (machine_id);

-- ============================================================================
-- 26. Notifications (legacy V24..V30/V34/V36/V62 + V60 message_body)
-- ============================================================================

CREATE TABLE notification_jobs (
  id UUID NOT NULL,
  alert_id UUID,
  escalation_level VARCHAR(16) NOT NULL,
  status VARCHAR(24) NOT NULL,
  recipient_user_id UUID,
  recipient_phone VARCHAR(32),
  message_body TEXT,
  idempotency_key VARCHAR(128) NOT NULL,
  trace_id VARCHAR(64),
  error_detail VARCHAR(512),
  sent_at TIMESTAMPTZ,
  attempt_count INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMPTZ,
  max_attempts INT NOT NULL DEFAULT 3,
  version BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT pk_notification_jobs PRIMARY KEY (id),
  CONSTRAINT fk_notification_jobs_alert FOREIGN KEY (alert_id) REFERENCES sparepart_alerts(id),
  CONSTRAINT uq_notification_jobs_alert_level UNIQUE (alert_id, escalation_level),
  CONSTRAINT chk_notification_jobs_max_attempts CHECK (max_attempts > 0),
  CONSTRAINT chk_notification_jobs_status_allowed
    CHECK (status IN ('PENDING', 'ROUTING_FAILED', 'SENT', 'EXHAUSTED', 'ESCALATED', 'CANCELLED', 'RATE_LIMITED')),
  CONSTRAINT chk_notification_jobs_sent_requires_sent_at
    CHECK (status <> 'SENT' OR sent_at IS NOT NULL)
);

CREATE UNIQUE INDEX uq_notification_jobs_idempotency_key
  ON notification_jobs (idempotency_key);
CREATE INDEX idx_notification_jobs_alert_id ON notification_jobs(alert_id);
CREATE INDEX idx_notification_jobs_status_next_attempt
  ON notification_jobs (status, next_attempt_at);
CREATE INDEX idx_notification_jobs_status_sent_at ON notification_jobs (status, sent_at);
CREATE INDEX idx_notification_jobs_alert_status ON notification_jobs (alert_id, status);

CREATE TABLE notification_attempts (
  id UUID NOT NULL,
  job_id UUID NOT NULL,
  attempt_number INT NOT NULL,
  status VARCHAR(16) NOT NULL,
  attempted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  response_detail VARCHAR(512),
  trace_id VARCHAR(64),
  CONSTRAINT pk_notification_attempts PRIMARY KEY (id),
  CONSTRAINT fk_notification_attempts_job FOREIGN KEY (job_id) REFERENCES notification_jobs (id),
  CONSTRAINT uq_notification_attempts_job_attempt UNIQUE (job_id, attempt_number)
);

CREATE INDEX idx_notification_attempts_job_id ON notification_attempts (job_id);
CREATE INDEX idx_notification_attempts_status_attempted_at
  ON notification_attempts (status, attempted_at);

-- ============================================================================
-- 27. Sparepart requests (legacy V57/V59) & escalation configs (V60/V67 seed)
-- ============================================================================

CREATE TABLE sparepart_requests (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_type VARCHAR(20) NOT NULL,
  work_order_id VARCHAR(50),
  machine_id UUID,
  sparepart_id UUID,
  material_code VARCHAR(64),
  quantity SMALLINT NOT NULL,
  est_price_id UUID,
  est_unit_price NUMERIC(18,2),
  purchase_reference_url VARCHAR(2048),
  status VARCHAR(20) NOT NULL DEFAULT 'REQUESTED',
  requested_by UUID NOT NULL,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_sparepart_requests_work_order FOREIGN KEY (work_order_id)
    REFERENCES work_orders(id) ON DELETE CASCADE,
  CONSTRAINT fk_sparepart_requests_machine FOREIGN KEY (machine_id)
    REFERENCES machines(id) ON DELETE SET NULL,
  CONSTRAINT fk_sparepart_requests_sparepart FOREIGN KEY (sparepart_id)
    REFERENCES spareparts(id) ON DELETE SET NULL,
  CONSTRAINT fk_sparepart_requests_price_entry FOREIGN KEY (est_price_id)
    REFERENCES sparepart_price_entries(id) ON DELETE SET NULL,
  CONSTRAINT ck_sparepart_requests_type CHECK (request_type IN ('SPAREPART','CONSUMABLE','SERVICE_EXTERNAL')),
  CONSTRAINT ck_sparepart_requests_status CHECK (status IN
    ('REQUESTED','PENDING_COMPLETION','ACKED','PROCESSING','READY',
     'PURCHASE_REQUESTED','PART_RECEIVED','PICKED_UP','CLOSED')),
  CONSTRAINT ck_sparepart_requests_quantity CHECK (quantity > 0)
);

CREATE INDEX idx_sparepart_requests_work_order ON sparepart_requests(work_order_id);
CREATE INDEX idx_sparepart_requests_status ON sparepart_requests(status);
CREATE INDEX idx_sparepart_requests_machine ON sparepart_requests(machine_id);

CREATE TABLE sparepart_request_timeline (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  request_id UUID NOT NULL REFERENCES sparepart_requests(id) ON DELETE CASCADE,
  from_status VARCHAR(20),
  to_status VARCHAR(20) NOT NULL,
  actor UUID NOT NULL,
  action VARCHAR(20) NOT NULL,
  mre_code VARCHAR(64),
  note VARCHAR(500),
  trace_id VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_sparepart_request_timeline_action CHECK (action IN ('TRANSITION','MRE_RECORDED'))
);

CREATE INDEX idx_sparepart_request_timeline_request ON sparepart_request_timeline(request_id);

CREATE TABLE escalation_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  scope VARCHAR(20) NOT NULL,
  step VARCHAR(30) NOT NULL,
  min_cost NUMERIC(18,2),
  max_cost NUMERIC(18,2),
  duration_minutes INTEGER NOT NULL,
  approval_role VARCHAR(30),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_escalation_configs_scope CHECK (scope IN ('SPAREPART_REQUEST','WORKORDER')),
  CONSTRAINT uq_escalation_configs_scope_step UNIQUE (scope, step)
);

-- ============================================================================
-- 28. Sync pipeline (legacy V63/V64/V65)
-- ============================================================================

CREATE TABLE sync_watermarks (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  last_sheet_no VARCHAR(50),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_sync_watermarks_single_row CHECK (id = '00000000-0000-0000-0000-000000000001')
);

CREATE TABLE sync_runs (
  id UUID PRIMARY KEY,
  started_at TIMESTAMPTZ NOT NULL,
  completed_at TIMESTAMPTZ,
  status VARCHAR(20) NOT NULL,
  rows_read INT NOT NULL DEFAULT 0,
  rows_upserted INT NOT NULL DEFAULT 0,
  rows_rejected INT NOT NULL DEFAULT 0,
  error_message TEXT,
  CONSTRAINT ck_sync_runs_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED'))
);

CREATE INDEX idx_sync_runs_started_at ON sync_runs(started_at);

CREATE TABLE sync_field_mappings (
  field_name VARCHAR(64) PRIMARY KEY,
  domain VARCHAR(16) NOT NULL,
  CONSTRAINT ck_sync_field_mappings_domain CHECK (domain IN ('MASTER', 'OPERATIONAL'))
);

CREATE TABLE sync_quarantine (
  id UUID PRIMARY KEY,
  sheet_no VARCHAR(50),
  reason VARCHAR(64) NOT NULL,
  raw_payload JSONB,
  trace_id VARCHAR(36),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sync_quarantine_sheet_no ON sync_quarantine(sheet_no);

-- ============================================================================
-- 29. Settings (legacy V66) — singleton company settings row
-- ============================================================================

CREATE TABLE settings (
  singleton_key SMALLINT PRIMARY KEY DEFAULT 1,
  logo_object_key VARCHAR(512),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT ck_settings_singleton CHECK (singleton_key = 1)
);

-- ============================================================================
-- 29b. OPA authz decision log (legacy V46) — one row per PolicyDecisionPoint
--      evaluate() call (FR-164 / NFR-P2-7). Masked by construction: the OPA input
--      schema carries only user + derived scope + action.
-- ============================================================================

CREATE TABLE authz_decisions (
  id UUID PRIMARY KEY,
  decision_id UUID,
  policy_revision TEXT,
  allowed BOOLEAN NOT NULL,
  degraded BOOLEAN NOT NULL,
  subject_user_id UUID,
  action VARCHAR(255) NOT NULL,
  resource_type VARCHAR(64),
  decided_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_authz_decisions_decided_at ON authz_decisions(decided_at);

-- ============================================================================
-- 30. Audit log (legacy V16/V44/V68 final state) — entity_type CHECK carries the
--     full adapted + new-module value set. Only forward INSERT is allowed: the
--     immutability trigger covers every column (DW-128).
-- ============================================================================

CREATE TABLE audit_log (
  id UUID PRIMARY KEY,
  actor_id UUID NOT NULL,
  actor_name VARCHAR(255) NOT NULL,
  action VARCHAR(16) NOT NULL,
  entity_type VARCHAR(32) NOT NULL,
  entity_id UUID NOT NULL,
  entity_label VARCHAR(255) NOT NULL,
  plant_id UUID,
  previous_value TEXT,
  new_value TEXT,
  decision_id UUID,
  created_at TIMESTAMPTZ NOT NULL,
  CONSTRAINT ck_audit_log_action CHECK (action IN ('CREATE', 'UPDATE', 'DELETE')),
  CONSTRAINT ck_audit_log_entity_type CHECK (entity_type IN
    ('PLANT', 'MACHINE_GROUP', 'MACHINE', 'SPAREPART_TAXONOMY',
     'SPAREPART', 'INSTALLATION', 'RESPONSIBILITY', 'ALERT',
     'SPAREPART_PRICE_ENTRY', 'SECTION', 'TEAM', 'WORK_ORDER_CATEGORY',
     'WORK_ORDER', 'REPAIR_SESSION', 'WORKORDER_ATTACHMENT',
     'WORK_ORDER_TODO', 'WORKORDER_RATING', 'RATING_DIMENSION',
     'PREVENTIVE_PROGRAM', 'PREVENTIVE_SCHEDULE',
     'PREVENTIVE_CHECKLIST', 'PREVENTIVE_ATTACHMENT',
     'SPAREPART_REQUEST', 'DEPARTMENT', 'DEPARTMENT_USER', 'USER',
     'INVENTORY_LOCATION', 'INVENTORY_STOCK_BALANCE',
     'INVENTORY_TRANSFER', 'INVENTORY_RESERVATION',
     'MACHINE_AREA', 'JOB_TITLE', 'SYSTEM_ROLE', 'ROLE_PERMISSION_MAPPING',
     'MENU_FEATURE', 'DOMAIN_CONTEXT', 'USER_JOB_BINDING', 'USER_ROLE_BINDING',
     'PLANT_WORKING_CALENDAR', 'SIGNATURE_USE',
     'SPAREPART_STOCK', 'SYNC_RUN', 'SYNC_QUARANTINE', 'WORKORDER_SIGNATURE')),
  CONSTRAINT fk_audit_log_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE SET NULL
);

CREATE INDEX idx_audit_log_entity_type ON audit_log(entity_type);
CREATE INDEX idx_audit_log_actor_name ON audit_log(actor_name);
CREATE INDEX idx_audit_log_plant_id ON audit_log(plant_id);
CREATE INDEX idx_audit_log_created_at ON audit_log(created_at);

CREATE FUNCTION prevent_audit_log_mutation() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'audit_log is immutable';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_log_immutable_before_update
  BEFORE UPDATE OF id, actor_id, actor_name, action, entity_type, entity_id, entity_label,
                  plant_id, previous_value, new_value, created_at, decision_id
  ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

CREATE TRIGGER audit_log_immutable_before_delete
  BEFORE DELETE ON audit_log
  FOR EACH ROW EXECUTE FUNCTION prevent_audit_log_mutation();

-- ============================================================================
-- 31. Blueprint A12: plant working calendars (after machines/kpi — no FK back)
-- ============================================================================

CREATE TABLE plant_working_calendars (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  plant_id UUID NOT NULL,
  year INT NOT NULL,
  workweek_mode VARCHAR(12) NOT NULL DEFAULT 'FIVE_DAY',
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_plant_working_calendars_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE CASCADE,
  CONSTRAINT uq_plant_working_calendars_plant_year UNIQUE (plant_id, year),
  CONSTRAINT ck_plant_working_calendars_workweek_mode CHECK (workweek_mode IN ('FIVE_DAY', 'SIX_DAY')),
  CONSTRAINT ck_plant_working_calendars_year CHECK (year BETWEEN 2000 AND 2999)
);

CREATE TABLE plant_working_calendar_dates (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  working_calendar_id UUID NOT NULL,
  date DATE NOT NULL,
  reason VARCHAR(255),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_plant_working_calendar_dates_calendar FOREIGN KEY (working_calendar_id)
    REFERENCES plant_working_calendars(id) ON DELETE CASCADE,
  CONSTRAINT uq_plant_working_calendar_dates_calendar_date UNIQUE (working_calendar_id, date)
);

-- ============================================================================
-- 32. Migration-owned seeds (from V22/V53/V56/V60/V64/V66/V67 — data the app
--     expects to exist right after migrate)
-- ============================================================================

INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
    gen_random_uuid(),
    'alert_notification',
    'PERINGATAN SPAREPART - {plantCode}

Mesin: {machineCode} ({machineName})
Grup: {machineGroup}
Sparepart: {sparepartName}

Konsumsi saat ini telah mencapai {thresholdPercent}% dari batas lifetime.
Jumlah produksi saat ini: {currentCount}

Waktu peringatan: {alertTime}

Segera lakukan pengecekan dan jadwalkan penggantian sparepart.',
    now(),
    now()
);

-- Legacy V13 idiom: the required sparepart CATEGORY dimension rows. The pilot
-- seed links its BRAND/KIND/TYPE rows to the ELECTRIC category (V15 linkage
-- rule: non-CATEGORY rows always carry a category_id).
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

INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'workorder_lifecycle',
  'PERUBAHAN STATUS WORKORDER - {machineCode}

WO: {workOrderId}
Status: {status}
Kejadian: {eventLabel}
Waktu: {transitionedAt}

Silakan lakukan tindakan lanjutan.',
  now(), now()
);

INSERT INTO waha_templates (id, template_key, body, created_at, updated_at)
VALUES (
  gen_random_uuid(),
  'workorder_ack',
  'PERINGATAN AKUISISI - WORKORDER {workOrderId}

Mesin: {machineCode}
Deadline konfirmasi: {ackDeadline}

Klik link berikut untuk konfirmasi:
{ackLink}',
  now(), now()
);

INSERT INTO rating_dimensions (code, label, sort_order, created_by)
VALUES ('SPEED', 'Speed', 1, '00000000-0000-0000-0000-000000000000'),
       ('WORK_QUALITY', 'Work Quality', 2, '00000000-0000-0000-0000-000000000000'),
       ('TIDINESS', 'Tidiness', 3, '00000000-0000-0000-0000-000000000000')
ON CONFLICT (code) DO NOTHING;

INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at)
VALUES (gen_random_uuid(), '02', 'Preventive', NULL, NOW(), NOW())
ON CONFLICT (code) DO NOTHING;

INSERT INTO escalation_configs (scope, step, min_cost, max_cost, duration_minutes, approval_role)
VALUES
  ('SPAREPART_REQUEST', 'SECTION_LEADER_APPROVAL',    NULL,          5000000,     0, 'SECTION_LEADER'),
  ('SPAREPART_REQUEST', 'MAINTENANCE_LEADER_APPROVAL', 5000000,      50000000,    0, 'MAINTENANCE_LEADER'),
  ('SPAREPART_REQUEST', 'MANAGER_APPROVAL',            50000000,     NULL,        0, 'MANAGER_MAINTENANCE'),
  ('SPAREPART_REQUEST', 'ACK_WAITING',      NULL, NULL,  480, NULL),
  ('SPAREPART_REQUEST', 'PROCESS_WAITING',  NULL, NULL, 1440, NULL),
  ('SPAREPART_REQUEST', 'PURCHASE_WAITING', NULL, NULL, 2880, NULL),
  ('WORKORDER',         'ACK_WAITING',      NULL, NULL,  480, NULL)
ON CONFLICT (scope, step) DO NOTHING;

INSERT INTO sync_field_mappings (field_name, domain) VALUES
  ('status', 'MASTER'),
  ('machine_id', 'MASTER'),
  ('category_id', 'MASTER'),
  ('parent_id', 'MASTER'),
  ('description', 'MASTER'),
  ('sync_version', 'MASTER'),
  ('report_chronological', 'OPERATIONAL'),
  ('report_analyze', 'OPERATIONAL'),
  ('report_corrective', 'OPERATIONAL'),
  ('report_preventive', 'OPERATIONAL'),
  ('cp_cp_lower', 'OPERATIONAL'),
  ('cp_cp_upper', 'OPERATIONAL'),
  ('cpk', 'OPERATIONAL'),
  ('cpk_pdf_object_key', 'OPERATIONAL'),
  ('fmea_failure_type', 'OPERATIONAL'),
  ('stop_time_reason', 'OPERATIONAL'),
  ('stop_time_detail', 'OPERATIONAL'),
  ('mttr_minutes', 'OPERATIONAL'),
  ('response_time_minutes', 'OPERATIONAL'),
  ('done_reason', 'OPERATIONAL')
ON CONFLICT (field_name) DO NOTHING;

INSERT INTO settings (singleton_key) VALUES (1) ON CONFLICT DO NOTHING;

