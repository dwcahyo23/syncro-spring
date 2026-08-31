-- ============================================================================
-- Syncro work-order demo seed (plants 01/02, Forming machines, WO lifecycle)
-- ============================================================================
--
-- Purpose:
--   Demo dataset for the work-order feature: two plants (01, 02), one Forming
--   machine group each, machines BF-08409 / BF-08410 (plant 01) and BF-08411
--   (plant 02), maintenance users from MANAGER down to TECHNICIAN, and a spread
--   of dummy work orders covering the full lifecycle (OPEN -> IN_PROGRESS ->
--   PENDING_SPAREPART / PENDING_REVIEW / CLOSED / CANCELLED) with status history, repair
--   sessions and todos.
--
-- Canonical values:
--   Plants          code '01' ("Plant 01"), code '02' ("Plant 02")
--   Machine group   "Forming" per plant, assigned to the MACHINERY section
--   Machines        BF-08409 (JBF18), BF-08410 (JBF19) under 01;
--                   BF-08411 (JBF20) under 02 - all ACTIVE, brand Juki,
--                   installed_at 2026-05-27 (pilot convention)
--   Categories      01 Breakdown, 03 Repair (02 Preventive is seeded by V1)
--   Users           manager@syncro.dev (MANAGER_MAINTENANCE) and
--                   leader@syncro.dev (MAINTENANCE_LEADER) span both plants;
--                   leader/staff/technician.<n>@syncro.dev per plant.
--                   All share the documented local-dev password
--                   "syncro-pilot-dev" (same bcrypt hash as pilot-seed.sql).
--   Work orders     WO-260801001..01025 (25 rows) covering OPEN / IN_PROGRESS /
--                   PENDING_SPAREPART / PENDING_REVIEW / CLOSED / CANCELLED -
--                   deliberately ABOVE the generator space:
--                   workorder_id_sequences is primed to >= 1025 so the backend
--                   (which continues from last_seq) can never collide with
--                   seeded ids. A pre-existing WO-260800001 (created via the
--                   backend) is adopted untouched, never deleted.
--
-- Preconditions:
--   The fresh consolidated Flyway migration V1 (story 15-1) applied (backend booted once against the local
--   stack). This file is NOT a Flyway migration - apply manually, exactly like
--   pilot-seed.sql:
--   docker compose -f syncro/infra/docker-compose.yml exec -T postgres \
--     sh -c 'PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 \
--       -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
--     < syncro/apps/backend/src/main/resources/db/seed/workorder-demo-seed.sql
--
-- Re-run safety:
--   Every statement is a guarded INSERT ... SELECT ... WHERE NOT EXISTS on
--   natural keys (pilot-seed idiom). Re-running inserts zero rows.
--
-- Machine resolution note:
--   Every machine lookup is scoped by plant (JOIN plants p + p.code = '..')
--   because machines.code uniqueness is PER PLANT - the pilot seed's
--   GM1/BF-08410 and this seed's 01/BF-08410 coexist, and an unscoped
--   lookup would join both and duplicate the insert row.
--
-- Runtime-owned tables (workorder_acks, ratings, audit_log, alerts, counter
-- states) are never touched.
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. Plants 01 and 02
-- ---------------------------------------------------------------------------
INSERT INTO plants (id, code, name, created_at, updated_at)
SELECT '10000000-0000-4000-8000-000000000001'::uuid, '01', 'Plant 01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM plants WHERE code = '01');

INSERT INTO plants (id, code, name, created_at, updated_at)
SELECT '20000000-0000-4000-8000-000000000002'::uuid, '02', 'Plant 02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM plants WHERE code = '02');

-- ---------------------------------------------------------------------------
-- 2. Sections MACHINERY per plant
-- ---------------------------------------------------------------------------
INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at)
SELECT '11000000-0000-4000-8000-000000000001'::uuid, p.id, 'MACHINERY', 'Machinery', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = '01'
  AND NOT EXISTS (SELECT 1 FROM sections s WHERE s.plant_id = p.id AND s.code = 'MACHINERY');

INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at)
SELECT '22000000-0000-4000-8000-000000000002'::uuid, p.id, 'MACHINERY', 'Machinery', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = '02'
  AND NOT EXISTS (SELECT 1 FROM sections s WHERE s.plant_id = p.id AND s.code = 'MACHINERY');

-- ---------------------------------------------------------------------------
-- 3. Machine groups Forming per plant (assigned to MACHINERY)
-- ---------------------------------------------------------------------------
INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at)
SELECT '12000000-0000-4000-8000-000000000001'::uuid, p.id, 'Forming', s.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE p.code = '01'
  AND NOT EXISTS (SELECT 1 FROM machine_groups g WHERE g.plant_id = p.id AND lower(g.name) = 'forming');

INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at)
SELECT '23000000-0000-4000-8000-000000000002'::uuid, p.id, 'Forming', s.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE p.code = '02'
  AND NOT EXISTS (SELECT 1 FROM machine_groups g WHERE g.plant_id = p.id AND lower(g.name) = 'forming');

-- ---------------------------------------------------------------------------
-- 4. Machines: BF-08409 + BF-08410 under 01, BF-08411 under 02
-- ---------------------------------------------------------------------------
INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, installed_at, notes, optional_telemetry_fields, created_at, updated_at)
SELECT '13000000-0000-4000-8000-000000000001'::uuid, p.id, g.id, 'BF-08409', 'JBF18', 'ACTIVE', 'Juki', DATE '2026-05-27', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN machine_groups g ON g.plant_id = p.id AND lower(g.name) = 'forming'
WHERE p.code = '01'
  AND NOT EXISTS (SELECT 1 FROM machines m WHERE m.plant_id = p.id AND lower(m.code) = 'bf-08409');

INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, installed_at, notes, optional_telemetry_fields, created_at, updated_at)
SELECT '13000000-0000-4000-8000-000000000002'::uuid, p.id, g.id, 'BF-08410', 'JBF19', 'ACTIVE', 'Juki', DATE '2026-05-27', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN machine_groups g ON g.plant_id = p.id AND lower(g.name) = 'forming'
WHERE p.code = '01'
  AND NOT EXISTS (SELECT 1 FROM machines m WHERE m.plant_id = p.id AND lower(m.code) = 'bf-08410');

INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, installed_at, notes, optional_telemetry_fields, created_at, updated_at)
SELECT '24000000-0000-4000-8000-000000000003'::uuid, p.id, g.id, 'BF-08411', 'JBF20', 'ACTIVE', 'Juki', DATE '2026-05-27', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN machine_groups g ON g.plant_id = p.id AND lower(g.name) = 'forming'
WHERE p.code = '02'
  AND NOT EXISTS (SELECT 1 FROM machines m WHERE m.plant_id = p.id AND lower(m.code) = 'bf-08411');

-- ---------------------------------------------------------------------------
-- 5. Work-order categories 01 Breakdown and 03 Repair (02 Preventive is V56)
-- ---------------------------------------------------------------------------
INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at)
SELECT '30000000-0000-4000-8000-000000000001'::uuid, '01', 'Breakdown', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM work_order_categories WHERE code = '01');

INSERT INTO work_order_categories (id, code, label, created_by, created_at, updated_at)
SELECT '30000000-0000-4000-8000-000000000002'::uuid, '03', 'Repair', NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM work_order_categories WHERE code = '03');

-- ---------------------------------------------------------------------------
-- 6. Users: manager + maintenance leader span both plants; per-plant
--    section leader / staff / technician. Same bcrypt hash as pilot-seed.sql
--    ("syncro-pilot-dev").
-- ---------------------------------------------------------------------------
INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '40000000-0000-4000-8000-000000000001'::uuid, 'manager@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'MANAGER_MAINTENANCE', TRUE, '6281234567810', 'Manager', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'manager@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '40000000-0000-4000-8000-000000000002'::uuid, 'leader@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'MAINTENANCE_LEADER', TRUE, '6281234567811', 'Maintenance Leader', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'leader@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '41000000-0000-4000-8000-000000000001'::uuid, 'leader.01@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'SECTION_LEADER', TRUE, '6281234567801', 'Section Leader 01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'leader.01@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '41000000-0000-4000-8000-000000000002'::uuid, 'staff.01@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'STAFF_MAINTENANCE', TRUE, '6281234567802', 'Staff Maintenance 01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'staff.01@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '41000000-0000-4000-8000-000000000003'::uuid, 'technician.01@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'TECHNICIAN', TRUE, '6281234567803', 'Technician 01', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'technician.01@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '42000000-0000-4000-8000-000000000001'::uuid, 'leader.02@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'SECTION_LEADER', TRUE, '6281234567812', 'Section Leader 02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'leader.02@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '42000000-0000-4000-8000-000000000002'::uuid, 'staff.02@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'STAFF_MAINTENANCE', TRUE, '6281234567813', 'Staff Maintenance 02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'staff.02@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, display_name, created_at, updated_at)
SELECT '42000000-0000-4000-8000-000000000003'::uuid, 'technician.02@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'TECHNICIAN', TRUE, '6281234567814', 'Technician 02', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users WHERE login_identifier = 'technician.02@syncro.dev');

-- ---------------------------------------------------------------------------
-- 7. Plant assignments: manager + maintenance leader on both plants,
--    per-plant team on its own plant
-- ---------------------------------------------------------------------------
INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
SELECT u.id, p.id, CURRENT_TIMESTAMP
FROM auth_users u, plants p
WHERE u.login_identifier IN ('manager@syncro.dev', 'leader@syncro.dev')
  AND p.code IN ('01', '02')
  AND NOT EXISTS (SELECT 1 FROM auth_user_plant_assignments a WHERE a.auth_user_id = u.id AND a.plant_id = p.id);

INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
SELECT u.id, p.id, CURRENT_TIMESTAMP
FROM auth_users u, plants p
WHERE u.login_identifier LIKE '%.01@syncro.dev' AND p.code = '01'
  AND NOT EXISTS (SELECT 1 FROM auth_user_plant_assignments a WHERE a.auth_user_id = u.id AND a.plant_id = p.id);

INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
SELECT u.id, p.id, CURRENT_TIMESTAMP
FROM auth_users u, plants p
WHERE u.login_identifier LIKE '%.02@syncro.dev' AND p.code = '02'
  AND NOT EXISTS (SELECT 1 FROM auth_user_plant_assignments a WHERE a.auth_user_id = u.id AND a.plant_id = p.id);

-- ---------------------------------------------------------------------------
-- 8. Machine responsibilities: leader / staff / technician per machine
-- ---------------------------------------------------------------------------
INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT gen_random_uuid(), m.id, u.id, r.level, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN LATERAL (
  SELECT 'TECHNICIAN' AS level, 'technician.' || p.code || '@syncro.dev' AS login
  UNION ALL SELECT 'STAFF', 'staff.' || p.code || '@syncro.dev'
  UNION ALL SELECT 'LEADER', 'leader.' || p.code || '@syncro.dev'
) r ON TRUE
JOIN auth_users u ON u.login_identifier = r.login
WHERE p.code IN ('01', '02')
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities mr WHERE mr.machine_id = m.id AND mr.user_id = u.id);

-- ---------------------------------------------------------------------------
-- 8b. Default inventory location per plant ("GUDANG UTAMA", blueprint DP3) so
--     every demo plant can resolve its default store without a location UUID.
-- ---------------------------------------------------------------------------
INSERT INTO inventory_locations (id, plant_id, code, name, description, is_active, created_at, updated_at)
SELECT md5('demo-gudang-' || p.code)::uuid, p.id, 'GUDANG-UTAMA', 'GUDANG UTAMA', 'Default plant store', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code IN ('01', '02')
  AND NOT EXISTS (SELECT 1 FROM inventory_locations l WHERE l.plant_id = p.id AND lower(l.code) = 'gudang-utama');

-- ---------------------------------------------------------------------------
-- 9. Work orders across the lifecycle. IDs live in the 01001+ block: the
--    generator continues from workorder_id_sequences.last_seq (primed to
--    >= 1000 below), so seeded ids can never collide with generated ones.
--    Every machine lookup is scoped by plant (machines.code is unique per
--    plant - the pilot GM1/BF-08410 also exists).
-- ---------------------------------------------------------------------------
INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801001', 'INTERNAL', NULL, 'OPEN', c.id, m.id, 'Mesin mati total, indikator error E-07 saat running. Perlu pengecekan PLC dan kelistrikan.', 0, NULL, NULL, mgr.id, '2026-08-29T08:00:00Z', '2026-08-29T08:00:00Z'
FROM work_order_categories c, machines m, plants p, auth_users mgr
WHERE c.code = '01' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08410' AND mgr.login_identifier = 'manager@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801001');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801002', 'INTERNAL', NULL, 'IN_PROGRESS', c.id, m.id, 'Suara abnormal pada gearbox, getaran tinggi. Ditugaskan ke teknisi untuk investigasi.', 0, NULL, tech.id, leader.id, '2026-08-29T09:30:00Z', '2026-08-29T10:15:00Z'
FROM work_order_categories c, machines m, plants p, auth_users tech, auth_users leader
WHERE c.code = '01' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08410' AND tech.login_identifier = 'technician.01@syncro.dev' AND leader.login_identifier = 'leader.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801002');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801003', 'INTERNAL', NULL, 'IN_PROGRESS', c.id, m.id, 'Perbaikan pendinginan oli hidrolik. Penggantian filter dan flush sistem.', 0, NULL, tech.id, leader.id, '2026-08-29T11:00:00Z', '2026-08-29T11:20:00Z'
FROM work_order_categories c, machines m, plants p, auth_users tech, auth_users leader
WHERE c.code = '03' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08409' AND tech.login_identifier = 'technician.01@syncro.dev' AND leader.login_identifier = 'leader.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801003');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at, mttr_minutes, done_reason)
SELECT 'WO-260801004', 'INTERNAL', NULL, 'PENDING_REVIEW', c.id, m.id, 'Ganti solenoid valve pneumatik yang bocor. Test cycle normal kembali.', 0, NULL, tech.id, leader.id, '2026-08-28T07:00:00Z', '2026-08-28T09:00:00Z', 45, 'Selesai sesuai prosedur'
FROM work_order_categories c, machines m, plants p, auth_users tech, auth_users leader
WHERE c.code = '01' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08410' AND tech.login_identifier = 'technician.01@syncro.dev' AND leader.login_identifier = 'leader.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801004');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at, mttr_minutes, done_reason)
SELECT 'WO-260801005', 'INTERNAL', NULL, 'CLOSED', c.id, m.id, 'Perbaikan minor: pengencangan baut dan pelumasan slide. Ditutup setelah verifikasi.', 0, NULL, tech.id, leader.id, '2026-08-27T13:00:00Z', '2026-08-27T15:00:00Z', 30, 'Verifikasi leader selesai'
FROM work_order_categories c, machines m, plants p, auth_users tech, auth_users leader
WHERE c.code = '01' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08409' AND tech.login_identifier = 'technician.01@syncro.dev' AND leader.login_identifier = 'leader.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801005');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801006', 'INTERNAL', NULL, 'CANCELLED', c.id, m.id, 'Duplikat laporan. Dibatalkan, masalah sudah ditangani WO lain.', 0, NULL, NULL, mgr.id, '2026-08-26T14:00:00Z', '2026-08-26T14:30:00Z'
FROM work_order_categories c, machines m, plants p, auth_users mgr
WHERE c.code = '01' AND p.code = '01' AND m.plant_id = p.id AND m.code = 'BF-08410' AND mgr.login_identifier = 'manager@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801006');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801007', 'INTERNAL', NULL, 'OPEN', c.id, m.id, 'Mesin tidak bisa start, indikator safety relay aktif. Perlu cek rangkaian safety.', 0, NULL, NULL, mgr.id, '2026-08-30T08:00:00Z', '2026-08-30T08:00:00Z'
FROM work_order_categories c, machines m, plants p, auth_users mgr
WHERE c.code = '01' AND p.code = '02' AND m.plant_id = p.id AND m.code = 'BF-08411' AND mgr.login_identifier = 'manager@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801007');

INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at)
SELECT 'WO-260801008', 'INTERNAL', NULL, 'IN_PROGRESS', c.id, m.id, 'Pelumasan otomatis tidak berfungsi pada stasiun 3. Ditugaskan ke teknisi.', 0, NULL, tech.id, leader.id, '2026-08-30T09:00:00Z', '2026-08-30T09:45:00Z'
FROM work_order_categories c, machines m, plants p, auth_users tech, auth_users leader
WHERE c.code = '03' AND p.code = '02' AND m.plant_id = p.id AND m.code = 'BF-08411' AND tech.login_identifier = 'technician.02@syncro.dev' AND leader.login_identifier = 'leader.02@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM work_orders WHERE id = 'WO-260801008');

-- 9b. Remaining demo work orders (01009..01025) - compact bulk insert. Machine
--     and user resolution is per-row; the plant filter keeps BF-08410 scoped to
--     plant 01 (the pilot GM1/BF-08410 also exists).
INSERT INTO work_orders (id, source, parent_id, status, category_id, machine_id, description, sync_version, idempotency_key, assigned_technician_id, created_by, created_at, updated_at, mttr_minutes, done_reason)
SELECT v.id, 'INTERNAL', NULL, v.status, c.id, m.id, v.description, 0, NULL,
       tech.id, byu.id, v.created_at::timestamptz, v.updated_at::timestamptz, v.mttr, v.done_reason
FROM (VALUES
  ('WO-260801009', 'OPEN',           '01', 'BF-08409', '01', NULL,                       'Roller atas aus, quality reject meningkat.',             'manager@syncro.dev',   '2026-08-28T08:00:00Z', '2026-08-28T08:00:00Z', NULL, NULL),
  ('WO-260801010', 'IN_PROGRESS',       '01', 'BF-08410', '01', 'technician.01@syncro.dev', 'Kebocoran udara pada manifold pneumatik.',              'leader.01@syncro.dev', '2026-08-28T09:00:00Z', '2026-08-28T09:30:00Z', NULL, NULL),
  ('WO-260801011', 'IN_PROGRESS',    '03', 'BF-08409', '01', 'technician.01@syncro.dev', 'Kalibrasi sensor suhu zona curing.',                    'leader.01@syncro.dev', '2026-08-28T10:00:00Z', '2026-08-28T10:20:00Z', NULL, NULL),
  ('WO-260801012', 'PENDING_SPAREPART', '01', 'BF-08410', '01', 'technician.01@syncro.dev', 'Menunggu sparepart motor servo penggerak konveyor.',    'leader.01@syncro.dev', '2026-08-27T11:00:00Z', '2026-08-27T12:00:00Z', NULL, NULL),
  ('WO-260801013', 'PENDING_REVIEW',           '03', 'BF-08409', '01', 'technician.01@syncro.dev', 'Penggantian bearing motor utama.',                       'leader.01@syncro.dev', '2026-08-27T07:00:00Z', '2026-08-27T09:10:00Z', 70, 'Selesai, mesin jalan normal'),
  ('WO-260801014', 'PENDING_REVIEW',           '01', 'BF-08410', '01', 'technician.01@syncro.dev', 'Perbaikan kebocoran oli seal silinder hidrolik.',       'leader.01@syncro.dev', '2026-08-26T08:30:00Z', '2026-08-26T10:45:00Z', 85, 'Seal diganti, tidak ada kebocoran'),
  ('WO-260801015', 'CLOSED',         '03', 'BF-08409', '01', 'technician.01@syncro.dev', 'Preventive: pengencangan dan pelumasan berkala.',        'leader.01@syncro.dev', '2026-08-26T13:00:00Z', '2026-08-26T15:00:00Z', 35, 'Verifikasi selesai'),
  ('WO-260801016', 'CLOSED',         '01', 'BF-08410', '01', 'technician.01@syncro.dev', 'Ganti proximity sensor posisi stasiun 2.',               'leader.01@syncro.dev', '2026-08-25T09:00:00Z', '2026-08-25T10:30:00Z', 40, 'Selesai, kalibrasi ulang OK'),
  ('WO-260801017', 'CANCELLED',      '01', 'BF-08409', '01', NULL,                       'Tiket duplikat, ditutup manual.',                        'manager@syncro.dev',   '2026-08-25T14:00:00Z', '2026-08-25T14:15:00Z', NULL, NULL),
  ('WO-260801018', 'OPEN',           '01', 'BF-08411', '02', NULL,                       'Error alarm suhu overload pada heater.',                  'manager@syncro.dev',   '2026-08-29T07:30:00Z', '2026-08-29T07:30:00Z', NULL, NULL),
  ('WO-260801019', 'IN_PROGRESS',       '03', 'BF-08411', '02', 'technician.02@syncro.dev', 'Pengecekan kelistrikan panel kontrol.',                   'leader.02@syncro.dev', '2026-08-29T08:30:00Z', '2026-08-29T09:00:00Z', NULL, NULL),
  ('WO-260801020', 'IN_PROGRESS',    '01', 'BF-08411', '02', 'technician.02@syncro.dev', 'Perbaikan sistem clamp yang tidak presisi.',              'leader.02@syncro.dev', '2026-08-29T10:00:00Z', '2026-08-29T10:30:00Z', NULL, NULL),
  ('WO-260801021', 'PENDING_SPAREPART', '01', 'BF-08411', '02', 'technician.02@syncro.dev', 'Menunggu valve pneumatik pengganti.',                     'leader.02@syncro.dev', '2026-08-28T11:00:00Z', '2026-08-28T12:00:00Z', NULL, NULL),
  ('WO-260801022', 'PENDING_REVIEW',           '03', 'BF-08411', '02', 'technician.02@syncro.dev', 'Ganti filter udara kompresor.',                           'leader.02@syncro.dev', '2026-08-28T07:00:00Z', '2026-08-28T08:40:00Z', 55, 'Selesai, tekanan normal'),
  ('WO-260801023', 'CLOSED',         '01', 'BF-08411', '02', 'technician.02@syncro.dev', 'Perbaikan wiring putus pada kabel sensor.',               'leader.02@syncro.dev', '2026-08-27T13:30:00Z', '2026-08-27T15:10:00Z', 50, 'Verifikasi leader selesai'),
  ('WO-260801024', 'CLOSED',         '03', 'BF-08411', '02', 'technician.02@syncro.dev', 'Rutin: pelumasan rel slide dan rack.',                    'leader.02@syncro.dev', '2026-08-27T09:00:00Z', '2026-08-27T10:20:00Z', 30, 'Selesai sesuai jadwal'),
  ('WO-260801025', 'CANCELLED',      '01', 'BF-08411', '02', NULL,                       'Dibatalkan, tidak ditemukan kerusakan.',                  'manager@syncro.dev',   '2026-08-26T15:00:00Z', '2026-08-26T15:30:00Z', NULL, NULL)
) AS v(id, status, category_code, machine_code, plant_code, tech_login, description, created_by_login, created_at, updated_at, mttr, done_reason)
JOIN work_order_categories c ON c.code = v.category_code
JOIN machines m ON m.code = v.machine_code
JOIN plants p ON p.id = m.plant_id AND p.code = v.plant_code
JOIN auth_users byu ON byu.login_identifier = v.created_by_login
LEFT JOIN auth_users tech ON tech.login_identifier = v.tech_login
WHERE NOT EXISTS (SELECT 1 FROM work_orders wo WHERE wo.id = v.id);

-- ---------------------------------------------------------------------------
-- 10. Status history (DERIVED = system creation, MANUAL = user transition)
-- ---------------------------------------------------------------------------
INSERT INTO work_order_status_history (id, work_order_id, from_status, to_status, source, actor, trace_id, transitioned_at)
SELECT gen_random_uuid(), wo.id, NULL, wo.status, 'DERIVED', 'SYSTEM', '00000000-0000-4000-8000-000000000001', wo.created_at
FROM work_orders wo
WHERE wo.id LIKE 'WO-2608010%'
  AND NOT EXISTS (SELECT 1 FROM work_order_status_history h
                  WHERE h.work_order_id = wo.id AND h.source = 'DERIVED' AND h.from_status IS NULL);

-- MANUAL transitions derived from each status's lifecycle path; actors resolve
-- per plant (technician for work steps, leader for CLOSE, manager for CANCEL).
-- NOT EXISTS guards make this idempotent against rows already applied.
INSERT INTO work_order_status_history (id, work_order_id, from_status, to_status, source, actor, trace_id, transitioned_at)
WITH path(step, from_status, to_status, applies_to) AS (
  VALUES
    -- IN_PROGRESS (assigned + started): the assign edge itself is OPEN -> IN_PROGRESS
    (1, 'OPEN',         'IN_PROGRESS',       'IN_PROGRESS'),
    -- PENDING_SPAREPART: OPEN -> IN_PROGRESS -> PENDING_SPAREPART
    (1, 'OPEN',         'IN_PROGRESS',       'PENDING_SPAREPART'),
    (2, 'IN_PROGRESS',  'PENDING_SPAREPART', 'PENDING_SPAREPART'),
    -- PENDING_REVIEW: OPEN -> IN_PROGRESS -> PENDING_REVIEW
    (1, 'OPEN',         'IN_PROGRESS',       'PENDING_REVIEW'),
    (2, 'IN_PROGRESS',  'PENDING_REVIEW',    'PENDING_REVIEW'),
    -- CLOSED: OPEN -> IN_PROGRESS -> PENDING_REVIEW -> CLOSED
    (1, 'OPEN',         'IN_PROGRESS',       'CLOSED'),
    (2, 'IN_PROGRESS',  'PENDING_REVIEW',    'CLOSED'),
    (3, 'PENDING_REVIEW', 'CLOSED',          'CLOSED'),
    (1, 'OPEN',         'CANCELLED',         'CANCELLED')
)
SELECT gen_random_uuid(), wo.id, p.from_status, p.to_status, 'MANUAL',
       CASE WHEN p.to_status = 'CLOSED' THEN leader.id
            WHEN p.to_status = 'CANCELLED' THEN mgr.id
            ELSE tech.id END,
       '00000000-0000-4000-8000-00000000000' || p.step,
       wo.updated_at
FROM work_orders wo
JOIN path p ON p.applies_to = wo.status
JOIN machines m ON m.id = wo.machine_id
JOIN plants pl ON pl.id = m.plant_id
JOIN auth_users mgr ON mgr.login_identifier = 'manager@syncro.dev'
LEFT JOIN auth_users tech ON tech.login_identifier = 'technician.' || pl.code || '@syncro.dev'
LEFT JOIN auth_users leader ON leader.login_identifier = 'leader.' || pl.code || '@syncro.dev'
WHERE wo.id LIKE 'WO-2608010%'
  AND NOT EXISTS (SELECT 1 FROM work_order_status_history h
                  WHERE h.work_order_id = wo.id
                    AND h.from_status = p.from_status AND h.to_status = p.to_status);

-- ---------------------------------------------------------------------------
-- 11. Repair sessions: one open (IN_PROGRESS), one closed (DONE with MTTR)
-- ---------------------------------------------------------------------------
INSERT INTO repair_sessions (id, work_order_id, technician_id, description, started_at, ended_at, duration_minutes, created_at, updated_at)
SELECT gen_random_uuid(), 'WO-260801003', tech.id, 'Flush sistem hidrolik dan penggantian filter pendingin', '2026-08-29T11:30:00Z', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM auth_users tech
WHERE tech.login_identifier = 'technician.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM repair_sessions WHERE work_order_id = 'WO-260801003');

INSERT INTO repair_sessions (id, work_order_id, technician_id, description, started_at, ended_at, duration_minutes, created_at, updated_at)
SELECT gen_random_uuid(), 'WO-260801004', tech.id, 'Ganti solenoid valve dan test cycle', '2026-08-28T07:15:00Z', '2026-08-28T08:00:00Z', 45, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM auth_users tech
WHERE tech.login_identifier = 'technician.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM repair_sessions WHERE work_order_id = 'WO-260801004');

-- ---------------------------------------------------------------------------
-- 12. Todos on the IN_PROGRESS work order
-- ---------------------------------------------------------------------------
INSERT INTO workorder_todos (id, work_order_id, title, description, assigned_technician_id, status, sort_order, created_by, created_at, updated_at)
SELECT gen_random_uuid(), 'WO-260801003', 'Ganti filter oli', 'Filter oli hidrolik part #FLT-HY-01', tech.id, 'COMPLETED', 1, tech.id, '2026-08-29T11:30:00Z', '2026-08-29T12:00:00Z'
FROM auth_users tech
WHERE tech.login_identifier = 'technician.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM workorder_todos WHERE work_order_id = 'WO-260801003' AND title = 'Ganti filter oli');

INSERT INTO workorder_todos (id, work_order_id, title, description, assigned_technician_id, status, sort_order, created_by, created_at, updated_at)
SELECT gen_random_uuid(), 'WO-260801003', 'Verifikasi suhu oli normal', 'Pastikan suhu 40-60C setelah running 30 menit', tech.id, 'PENDING', 2, tech.id, '2026-08-29T11:30:00Z', '2026-08-29T11:30:00Z'
FROM auth_users tech
WHERE tech.login_identifier = 'technician.01@syncro.dev'
  AND NOT EXISTS (SELECT 1 FROM workorder_todos WHERE work_order_id = 'WO-260801003' AND title = 'Verifikasi suhu oli normal');

-- ---------------------------------------------------------------------------
-- 13. Prime the ID sequence past the seeded block (never lowers an already
--     higher value): the backend generator continues from last_seq, so the
--     next generated id is at least WO-260801026.
-- ---------------------------------------------------------------------------
INSERT INTO workorder_id_sequences (prefix, last_seq, updated_at)
SELECT '2608', GREATEST(1025, COALESCE((SELECT last_seq FROM workorder_id_sequences WHERE prefix = '2608'), 0)), CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM workorder_id_sequences WHERE prefix = '2608');

UPDATE workorder_id_sequences
SET last_seq = GREATEST(last_seq, 1025), updated_at = CURRENT_TIMESTAMP
WHERE prefix = '2608';

COMMIT;
