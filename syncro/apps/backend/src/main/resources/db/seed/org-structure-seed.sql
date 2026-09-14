-- ============================================================================
-- Syncro org-structure seed (leader review 2026-09-14)
-- ============================================================================
-- Purpose:
--   Adds the organizational master data the workorder review asked for:
--   plants GM1 + GM2, department "Maintenance" on both, and the machinery
--   machine groups "Forming - Rolling" and "Machining - CNC" under each
--   plant's MACHINERY section. NOTE: sections.code is CHECK-constrained to
--   MACHINERY/UTILITY/WORKSHOP (V1), one per plant, so the Rolling/CNC split
--   correctly lives in machine_groups (free-text name, unique per plant) —
--   the same shape pilot-seed uses for "Forming".
--   GM2 is otherwise empty, so it also gets its own machine (BF-08420/JBF21),
--   recipients (technician/staff/leader .gm2), plant assignments, machine
--   responsibilities (so the WO technician picker's machine-responsibility
--   scope resolves), working calendar, and default store — mirroring the
--   pilot-seed pattern.
--
-- Preconditions: Flyway V1..V20 applied + pilot-seed.sql already run (the
--   shared bcrypt hash / user idiom). NOT a Flyway migration; manual artifact.
--
-- Apply (idempotent — guarded INSERT...WHERE NOT EXISTS on natural keys; a
--   re-run inserts zero rows):
--   docker compose -f syncro/infra/docker-compose.yml --env-file syncro/.env exec -T postgres \
--     sh -c 'PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
--     < syncro/apps/backend/src/main/resources/db/seed/org-structure-seed.sql
-- ============================================================================

BEGIN;

-- 1. Plant GM2 (GM1 already exists via pilot-seed; guarded anyway)
INSERT INTO plants (id, code, name, created_at, updated_at)
SELECT 'c312500e-b17b-4f14-b073-5bb4bab4aadd'::uuid, 'GM1', 'Plant GM1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM plants WHERE code = 'GM1');

INSERT INTO plants (id, code, name, created_at, updated_at)
SELECT 'd412500e-b17b-4f14-b073-5bb4bab4aadd'::uuid, 'GM2', 'Plant GM2', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM plants WHERE code = 'GM2');

-- 2. MACHINERY section per plant (GM1 has one from pilot-seed; GM2 needs its own)
INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at)
SELECT 'b1c2d3e4-f5a6-7890-abcd-ef1234567891'::uuid, p.id, 'MACHINERY', 'Machinery', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM2'
  AND NOT EXISTS (SELECT 1 FROM sections s WHERE s.plant_id = p.id AND s.code = 'MACHINERY');

-- 3. Machine groups "Forming - Rolling" + "Machining - CNC" under the MACHINERY
--    section, for GM1 and GM2 (machine_groups name is free-text, unique per plant)
-- gen_random_uuid (not fixed) because the two-plant SELECT inserts two rows.
INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'Forming - Rolling', s.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE p.code IN ('GM1', 'GM2')
  AND NOT EXISTS (SELECT 1 FROM machine_groups g WHERE g.plant_id = p.id AND lower(g.name) = 'forming - rolling');

INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at)
SELECT gen_random_uuid(), p.id, 'Machining - CNC', s.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE p.code IN ('GM1', 'GM2')
  AND NOT EXISTS (SELECT 1 FROM machine_groups g WHERE g.plant_id = p.id AND lower(g.name) = 'machining - cnc');

-- 4. Department "Maintenance" on GM1 + GM2 (uq is exact-case; guard on lower())
INSERT INTO departments (id, plant_id, name, active, created_at, updated_at)
SELECT 'a2b3c4d5-e6f7-4890-abcd-ef1234567892'::uuid, p.id, 'Maintenance', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM1'
  AND NOT EXISTS (SELECT 1 FROM departments d WHERE d.plant_id = p.id AND lower(d.name) = 'maintenance');

INSERT INTO departments (id, plant_id, name, active, created_at, updated_at)
SELECT 'a3b4c5d6-e7f8-4901-abcd-ef1234567893'::uuid, p.id, 'Maintenance', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM2'
  AND NOT EXISTS (SELECT 1 FROM departments d WHERE d.plant_id = p.id AND lower(d.name) = 'maintenance');

-- 5. GM2 recipients (shared pilot bcrypt hash of "syncro-pilot-dev")
INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '4c1dfdf2-5103-456a-a71b-121a7174ec11'::uuid, 'technician.gm2@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'TECHNICIAN', TRUE, '6281234567811', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'technician.gm2@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '5d1dfdf2-5103-456a-a71b-121a7174ec12'::uuid, 'staff.gm2@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'STAFF_MAINTENANCE', TRUE, '6281234567812', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'staff.gm2@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '6e1dfdf2-5103-456a-a71b-121a7174ec13'::uuid, 'leader.gm2@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'SECTION_LEADER', TRUE, '6281234567813', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'leader.gm2@syncro.dev');

-- 6. GM2 demo machine under "Machining - CNC" so WO create + the machine-responsibility
--    technician picker work out of the box for GM2
INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, installed_at, created_at, updated_at)
SELECT '7f1d78ce-615b-4965-ae27-12e400524ed2'::uuid, p.id, g.id, 'BF-08420', 'JBF21', 'ACTIVE', 'Juki', DATE '2026-06-15', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN machine_groups g ON g.plant_id = p.id AND lower(g.name) = 'machining - cnc'
WHERE p.code = 'GM2'
  AND NOT EXISTS (SELECT 1 FROM machines m WHERE m.plant_id = p.id AND lower(m.code) = 'bf-08420');

-- 7. Plant assignments: GM2 recipients → GM2; GM1 recipients → GM1 (adopt existing)
INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
SELECT u.id, p.id, CURRENT_TIMESTAMP
FROM plants p
JOIN auth_users u ON (p.code = 'GM2' AND u.login_identifier IN ('technician.gm2@syncro.dev', 'staff.gm2@syncro.dev', 'leader.gm2@syncro.dev'))
                OR (p.code = 'GM1' AND u.login_identifier IN ('technician.gm1@syncro.dev', 'staff.gm1@syncro.dev', 'leader.gm1@syncro.dev'))
WHERE NOT EXISTS (SELECT 1 FROM auth_user_plant_assignments a WHERE a.auth_user_id = u.id AND a.plant_id = p.id);

-- 8. Machine responsibilities on GM2/BF-08420 (TECHNICIAN/STAFF/LEADER), matching
--    pilot-seed §9 for GM1
INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT '8a1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1d'::uuid, m.id, u.id, 'TECHNICIAN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'technician.gm2@syncro.dev'
WHERE p.code = 'GM2' AND lower(m.code) = 'bf-08420'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT '8b1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1e'::uuid, m.id, u.id, 'STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'staff.gm2@syncro.dev'
WHERE p.code = 'GM2' AND lower(m.code) = 'bf-08420'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT '8c1b1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c1f'::uuid, m.id, u.id, 'LEADER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'leader.gm2@syncro.dev'
WHERE p.code = 'GM2' AND lower(m.code) = 'bf-08420'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

-- 9. Department membership: plant recipients → their plant's Maintenance department
--    (department_users.assigned_by NOT NULL — assignees self-own the row here)
INSERT INTO department_users (department_id, user_id, assigned_by, assigned_at)
SELECT d.id, u.id, u.id, CURRENT_TIMESTAMP
FROM departments d
JOIN plants p ON p.id = d.plant_id
JOIN auth_users u ON (p.code = 'GM1' AND u.login_identifier IN ('technician.gm1@syncro.dev', 'staff.gm1@syncro.dev', 'leader.gm1@syncro.dev'))
                 OR (p.code = 'GM2' AND u.login_identifier IN ('technician.gm2@syncro.dev', 'staff.gm2@syncro.dev', 'leader.gm2@syncro.dev'))
WHERE lower(d.name) = 'maintenance'
  AND NOT EXISTS (SELECT 1 FROM department_users du WHERE du.department_id = d.id AND du.user_id = u.id);

-- 10. GM2 working calendar + default store (pilot-seed §7.8/§11 parity)
INSERT INTO plant_working_calendars (id, plant_id, year, workweek_mode, created_at, updated_at)
SELECT gen_random_uuid(), p.id, EXTRACT(YEAR FROM CURRENT_TIMESTAMP)::int, 'FIVE_DAY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM2'
  AND NOT EXISTS (SELECT 1 FROM plant_working_calendars c WHERE c.plant_id = p.id AND c.year = EXTRACT(YEAR FROM CURRENT_TIMESTAMP)::int);

INSERT INTO inventory_locations (id, plant_id, code, name, description, is_active, created_at, updated_at)
SELECT '9d1f1c1d-1e1f-1a1b-1c1d-1e1f1a1b1c20'::uuid, p.id, 'GUDANG-UTAMA', 'GUDANG UTAMA', 'Default plant store', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM2'
  AND NOT EXISTS (SELECT 1 FROM inventory_locations l WHERE l.plant_id = p.id AND lower(l.code) = 'gudang-utama');

COMMIT;
