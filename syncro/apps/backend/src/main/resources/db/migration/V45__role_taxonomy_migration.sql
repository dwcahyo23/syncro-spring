-- Story 9-4 / AD-15: Phase 1 roles map into the PRD taxonomy. MANAGE becomes
-- MANAGER_MAINTENANCE and VIEWER becomes AUDITOR (the taxonomy's read-only role);
-- afterwards the CHECK accepts exactly the ten PRD roles. Pure value rename:
-- capabilities are unchanged (MANAGER_MAINTENANCE is NOT a global promotion,
-- plant assignments still bind per AD-2).
-- Order matters: the legacy CHECK (V1) rejects the NEW values, so the constraint
-- must be dropped BEFORE the UPDATEs and re-added AFTER them.
ALTER TABLE auth_users DROP CONSTRAINT IF EXISTS ck_auth_users_application_role;
UPDATE auth_users SET application_role = 'MANAGER_MAINTENANCE' WHERE application_role = 'MANAGE';
UPDATE auth_users SET application_role = 'AUDITOR' WHERE application_role = 'VIEWER';
ALTER TABLE auth_users ADD CONSTRAINT ck_auth_users_application_role
  CHECK (application_role IN ('SUPER_ADMIN','MANAGER_MAINTENANCE','MAINTENANCE_LEADER','SECTION_LEADER','STAFF_MAINTENANCE','TECHNICIAN','INVENTORY_MAINTENANCE','STOREKEEPER','PRODUCTION_LEADER','AUDITOR'));
