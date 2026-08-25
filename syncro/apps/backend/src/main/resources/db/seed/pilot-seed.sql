-- ============================================================================
-- Syncro canonical pilot seed (Epic 7 / PRD §12 "Pilot Validation Scenario")
-- ============================================================================

-- Purpose:
--   Creates the canonical pilot dataset every Epic 7 validation story relies
--   on, so evidence from stories 7-2 to 7-7 is comparable and repeatable:
--   plant GM1, machine group Forming, machine BF-08410 / JBF19, sparepart
--   Electric · PLC · Wecon · LX5 installed with a 90% threshold, and the
--   TECHNICIAN/STAFF/LEADER escalation recipients with WhatsApp routing.
--
-- Canonical values (fixed by PRD §12 and architecture.md):
--   Plant           code GM1, name "Plant GM1"
--   Machine group   name "Forming" under GM1
--   Machine         code BF-08410, name JBF19, status ACTIVE, brand Juki,
--                   installed_at 2026-05-27 (canonical test conventions)
--   Taxonomy        CATEGORY ELECTRIC (already guaranteed by migrations
--                   V13/V15 - this seed only links to it) plus BRAND WECON,
--                   KIND PLC, TYPE LX5, all linked to the ELECTRIC category
--   Sparepart       name "Electric · PLC · Wecon · LX5", code
--                   BF-08410GM1ELEPLCWEC000 - exactly what the backend
--                   create path generates (SparepartService.bomPrefix uses
--                   the RAW machine code including the dash, plus plant
--                   code, codePart ELECTRIC->ELE, PLC->PLC, WECON->WEC,
--                   series 000, per nextBomCode/codePart/sparepartLabel)
--   Installation    expected_production_count 1000, baseline_counter 0,
--                   threshold_percentage 90, function_name "Primary"
--   Recipients      technician.gm1@syncro.dev / staff.gm1@syncro.dev /
--                   leader.gm1@syncro.dev (VIEWER, enabled, WhatsApp
--                   placeholders 6281234567801/02/03)
--
-- Preconditions:
--   Flyway migrations V1..V30 must already be applied. Boot the backend once
--   against the local stack or run `mvn -f syncro/apps/backend/pom.xml
--   flyway:migrate` first. This file is NOT a Flyway migration and is NOT on
--   spring.flyway.locations (which stays classpath:db/migration) - it is a
--   deliberate, manually applied local-dev/pilot artifact.
--
-- Apply (local dev stack; runs INSIDE the container so POSTGRES_USER /
--   POSTGRES_DB expand with the container's own environment from
--   syncro/infra/docker-compose.yml - no host-shell sourcing needed.
--   ON_ERROR_STOP aborts on the first failed statement and the file's
--   BEGIN/COMMIT wrapper rolls the whole apply back, so a half-applied
--   seed cannot persist; PGCLIENTENCODING protects the UTF-8 label):
--   docker compose -f syncro/infra/docker-compose.yml exec -T postgres \
--     sh -c 'PGCLIENTENCODING=UTF8 psql -v ON_ERROR_STOP=1 \
--       -U "$POSTGRES_USER" -d "$POSTGRES_DB"' \
--     < syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql
--
-- Re-run safety:
--   Every statement is a guarded INSERT ... SELECT ... WHERE NOT EXISTS on
--   natural keys (the V13/V15 idempotent-insert idiom). Re-running inserts
--   zero rows. Rows this seed does not own (pre-existing users, taxonomy)
--   are resolved by natural-key lookup at apply time, so a re-run can never
--   violate a foreign key.
--
-- Known pre-existing-data edges (single-operator manual flow; verified by
-- PilotSeedTest.seedResolvesPreExistingRowsWithoutPartialApplication):
--   * Machine / sparepart / machine-group resolution matches the guards'
--     case-insensitive semantics (lower(code) / lower(name)), so a
--     case-variant pre-existing row is ADOPTED, never silently orphaning
--     the dependent rows. plants.code uniqueness is exact-case by schema,
--     so a pre-existing case-variant plant simply coexists - the canonical
--     GM1 row is still created.
--   * A pre-existing pilot login is adopted AS-IS: application_role,
--     enabled, and whatsapp_number are never overwritten or validated.
--     A disabled/non-VIEWER/whatsapp-less pre-existing user yields dead
--     WhatsApp routing - verify before a live pilot.
--   * A pre-existing machine responsibility for the same (machine, user)
--     with a DIFFERENT level is not overwritten (V14 uniqueness is
--     machine+user), so that escalation level stays without its pilot
--     recipient - resolve manually if this matters.
--   * A pre-existing taxonomy row with the same (dimension, lower(name))
--     but a different code fails LOUDLY on the unique name index and the
--     whole apply rolls back. A row with the right code but linked to a
--     different category is adopted silently - verify the linkage if your
--     database predates this seed.
--
-- Pilot counter math (pins the Story 7-2 fixture boundaries):
--   consumed     = CountingDeltaCalculator.delta(baseline, counting)
--                = floorMod(counting - 0, 65536)
--   consumedPct  = consumed * 100 / 1000 (HALF_UP, 2 decimals, per
--                SparepartLifetimeEvaluator)
--   counting 890 -> 89.00% < 90  -> no alert (Story 7-4)
--   counting 900 -> 90.00% >= 90 -> exactly one OPEN alert and a TECHNICIAN
--                notification job (Story 7-5, SparepartAlertService fires at
--                consumedPercentage >= thresholdPercentage)
--   Both values are positive, so they pass the telemetry out_of_range
--   plausible-value check (negative runtimeHours/counting only).
--
-- Pilot password:
--   All three recipient users share the password "syncro-pilot-dev"
--   (documented local-dev secret, never production). The hash below is a
--   bcrypt $2a$ hash generated with Spring Security BCryptPasswordEncoder
--   and is verified by PilotSeedTest.embeddedHashMatchesDocumentedPassword
--   so silent hash drift fails the build.
--
-- WhatsApp caveat:
--   6281234567801/02/03 are PLACEHOLDERS. Replace them with real numbers
--   before running a live WAHA pilot - otherwise WAHA delivery will fail
--   (jobs still queue PENDING and record attempt failure evidence, which
--   keeps stories 7-5/7-6 observable).
--
-- Runtime-owned tables are never touched: machine_counter_states,
-- sparepart_alerts, notification_jobs, notification_attempts,
-- telemetry_quarantine, audit_log. Only lifecycle rows a user could have
-- created through the backend are represented, and the local admin user
-- (admin@syncro.dev) stays owned by LocalAdminBootstrap.
-- ============================================================================

-- The whole file runs as one transaction: with psql -v ON_ERROR_STOP=1 (or
-- ScriptUtils, which fails on the first error) any failed statement rolls
-- everything back instead of leaving a half-applied seed.
BEGIN;

-- 1. Plant GM1
INSERT INTO plants (id, code, name, created_at, updated_at)
SELECT 'b212500e-b17b-4f14-b073-5bb4bab4aadd'::uuid, 'GM1', 'Plant GM1', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM plants WHERE code = 'GM1');

-- 1.5 Section MACHINERY under GM1 (story 9-1; the Forming group below is assigned to it)
INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at)
SELECT 'a1b2c3d4-e5f6-7890-abcd-ef1234567890'::uuid, p.id, 'MACHINERY', 'Machinery', TRUE, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
WHERE p.code = 'GM1'
  AND NOT EXISTS (SELECT 1 FROM sections s WHERE s.plant_id = p.id AND s.code = 'MACHINERY');

-- 2. Machine group Forming under GM1, assigned to the MACHINERY section
INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at)
SELECT '604310bd-2930-4220-bb80-cd6d506e5e62'::uuid, p.id, 'Forming', s.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
LEFT JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE p.code = 'GM1'
  AND NOT EXISTS (SELECT 1 FROM machine_groups g WHERE g.plant_id = p.id AND lower(g.name) = 'forming');

-- 2.5 Adopt a pre-existing Forming group (e.g. seeded before story 9.1) into the
--     MACHINERY section — guarded so a re-run never reassigns an existing section.
UPDATE machine_groups g
SET section_id = s.id, updated_at = CURRENT_TIMESTAMP
FROM plants p
JOIN sections s ON s.plant_id = p.id AND s.code = 'MACHINERY'
WHERE g.plant_id = p.id AND p.code = 'GM1' AND lower(g.name) = 'forming' AND g.section_id IS NULL;

-- 3. Machine BF-08410 / JBF19 (one machine: code and name; V5 uniqueness is
--    (plant_id, lower(code)) - guard and downstream resolution share it)
INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, brand, installed_at, notes, optional_telemetry_fields, created_at, updated_at)
SELECT '6c1d78ce-615b-4965-ae27-12e400524ed2'::uuid, p.id, g.id, 'BF-08410', 'JBF19', 'ACTIVE', 'Juki', DATE '2026-05-27', NULL, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM plants p
JOIN machine_groups g ON g.plant_id = p.id AND lower(g.name) = 'forming'
WHERE p.code = 'GM1'
  AND NOT EXISTS (SELECT 1 FROM machines m WHERE m.plant_id = p.id AND lower(m.code) = 'bf-08410');

-- 4. Sparepart taxonomy: BRAND WECON / KIND PLC / TYPE LX5, linked to the
--    existing V13/V15 CATEGORY 'ELECTRIC' row (ck_sparepart_taxonomy_category_link
--    and SparepartService.validateLinkedTaxonomy both require the linkage)
INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at)
SELECT 'e9a3ac73-c8f5-4d39-a071-3f5f9ead4d74'::uuid, 'BRAND', 'WECON', 'Wecon', electric_category.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sparepart_taxonomy electric_category
WHERE electric_category.dimension = 'CATEGORY' AND electric_category.code = 'ELECTRIC'
  AND NOT EXISTS (SELECT 1 FROM sparepart_taxonomy t WHERE t.dimension = 'BRAND' AND t.code = 'WECON');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at)
SELECT 'd0c3b57e-9f80-429f-ba75-a99cc3c9c959'::uuid, 'KIND', 'PLC', 'PLC', electric_category.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sparepart_taxonomy electric_category
WHERE electric_category.dimension = 'CATEGORY' AND electric_category.code = 'ELECTRIC'
  AND NOT EXISTS (SELECT 1 FROM sparepart_taxonomy t WHERE t.dimension = 'KIND' AND t.code = 'PLC');

INSERT INTO sparepart_taxonomy (id, dimension, code, name, category_id, created_at, updated_at)
SELECT 'b602c7c9-ea6b-459e-afa8-8ac4e42012a2'::uuid, 'TYPE', 'LX5', 'LX5', electric_category.id, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM sparepart_taxonomy electric_category
WHERE electric_category.dimension = 'CATEGORY' AND electric_category.code = 'ELECTRIC'
  AND NOT EXISTS (SELECT 1 FROM sparepart_taxonomy t WHERE t.dimension = 'TYPE' AND t.code = 'LX5');

-- 5. Sparepart with the backend-exact generated code and label. The material code is a stable,
--    globally-unique literal (uq_spareparts_material_code is case-insensitive, multi-null safe)
--    and lead_time_hours (72 = 3 days, operating hours) drives the story 8-6 lead-time
--    consumption projection and the story 8-7 procurement-risk window.
INSERT INTO spareparts (id, code, name, machine_id, category_id, category_dimension, brand_id, brand_dimension, kind_id, kind_dimension, type_id, type_dimension, material_code, lead_time_hours, created_at, updated_at)
SELECT
  'eb6090e5-436c-40aa-b427-640b1abaf9da'::uuid,
  'BF-08410GM1ELEPLCWEC000',
  'Electric · PLC · Wecon · LX5',
  m.id,
  electric_category.id, 'CATEGORY',
  brand.id, 'BRAND',
  kind.id, 'KIND',
  type.id, 'TYPE',
  'BF08410GM1ELEPLCWECLX5',
  72,
  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN sparepart_taxonomy electric_category ON electric_category.dimension = 'CATEGORY' AND electric_category.code = 'ELECTRIC'
JOIN sparepart_taxonomy brand ON brand.dimension = 'BRAND' AND brand.code = 'WECON'
JOIN sparepart_taxonomy kind ON kind.dimension = 'KIND' AND kind.code = 'PLC'
JOIN sparepart_taxonomy type ON type.dimension = 'TYPE' AND type.code = 'LX5'
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND NOT EXISTS (
    SELECT 1 FROM spareparts sp
    WHERE sp.machine_id = m.id
      AND sp.category_id = electric_category.id
      AND sp.brand_id = brand.id
      AND sp.kind_id = kind.id
      AND sp.type_id = type.id
  );

-- 6. Installation on BF-08410 / JBF19 with the pilot threshold math
INSERT INTO machine_sparepart_installations (id, machine_id, sparepart_id, expected_production_count, baseline_counter, threshold_percentage, function_name, installed_at, created_at, updated_at)
SELECT '591f669e-69f4-46a5-839f-e68c5c3aa730'::uuid, m.id, sp.id, 1000, 0, 90, 'Primary', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN spareparts sp ON sp.machine_id = m.id
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND lower(sp.code) = 'bf-08410gm1eleplcwec000'
  AND NOT EXISTS (
    SELECT 1 FROM machine_sparepart_installations i
    WHERE i.machine_id = m.id
      AND i.sparepart_id = sp.id
      AND lower(i.function_name) = 'primary'
  );

-- 7. Pilot recipient users (shared bcrypt hash of "syncro-pilot-dev")
INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '2b67b210-61a6-4184-9454-47443c416e84'::uuid, 'technician.gm1@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'VIEWER', TRUE, '6281234567801', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'technician.gm1@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '7f2991a5-0f4c-43ce-b550-058003e88b74'::uuid, 'staff.gm1@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'VIEWER', TRUE, '6281234567802', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'staff.gm1@syncro.dev');

INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled, whatsapp_number, created_at, updated_at)
SELECT '391dfdf2-5103-456a-a71b-121a7174ec18'::uuid, 'leader.gm1@syncro.dev', '$2a$10$DuNkwH3TJ5QEjynPGceUTeBCT2IwUEIDhTL9T4FNa5XSWdm.M5ux2', 'VIEWER', TRUE, '6281234567803', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM auth_users u WHERE u.login_identifier = 'leader.gm1@syncro.dev');

-- 7.5 IDR price-entry example for the pilot sparepart (story 8-3 / validation data for the
--     procurement workflow). Idempotent per sparepart; entered_by resolves to the technician
--     pilot user (the seed's own fixed UUID, or a pre-existing row adopted via natural key).
INSERT INTO sparepart_price_entries (id, sparepart_id, amount, currency, kurs_to_idr, idr_amount, entered_by, entered_at, version)
SELECT
  'd4a62e5f-8f4b-4d0e-8d5a-9e2b1c3f4a5b'::uuid,
  sp.id,
  3500000.00,
  'IDR',
  NULL,
  3500000.00,
  u.id,
  CURRENT_TIMESTAMP,
  0
FROM spareparts sp
JOIN machines m ON m.id = sp.machine_id
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'technician.gm1@syncro.dev'
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND lower(sp.code) = 'bf-08410gm1eleplcwec000'
  AND NOT EXISTS (
    SELECT 1 FROM sparepart_price_entries pe WHERE pe.sparepart_id = sp.id
  );

-- 8. Plant assignments to GM1 for all three recipients (enables the 7-6
--    acknowledge path for any authenticated user with plant access)
INSERT INTO auth_user_plant_assignments (auth_user_id, plant_id, created_at)
SELECT u.id, p.id, CURRENT_TIMESTAMP
FROM plants p
JOIN auth_users u ON u.login_identifier IN ('technician.gm1@syncro.dev', 'staff.gm1@syncro.dev', 'leader.gm1@syncro.dev')
WHERE p.code = 'GM1'
  AND NOT EXISTS (SELECT 1 FROM auth_user_plant_assignments a WHERE a.auth_user_id = u.id AND a.plant_id = p.id);

-- 9. Machine responsibilities: one TECHNICIAN, one STAFF, one LEADER
--    (V14 has no created_at/updated_at defaults - set them explicitly)
INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT 'c7d3adc9-8737-4c69-8426-366b79da2f76'::uuid, m.id, u.id, 'TECHNICIAN', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'technician.gm1@syncro.dev'
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT 'dc659146-421b-43c4-85f6-ee6a39796861'::uuid, m.id, u.id, 'STAFF', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'staff.gm1@syncro.dev'
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

INSERT INTO machine_responsibilities (id, machine_id, user_id, level, created_at, updated_at)
SELECT '48b87048-9f8b-4fbb-a59c-a5994a0dab5a'::uuid, m.id, u.id, 'LEADER', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM machines m
JOIN plants p ON p.id = m.plant_id
JOIN auth_users u ON u.login_identifier = 'leader.gm1@syncro.dev'
WHERE p.code = 'GM1'
  AND lower(m.code) = 'bf-08410'
  AND NOT EXISTS (SELECT 1 FROM machine_responsibilities r WHERE r.machine_id = m.id AND r.user_id = u.id);

COMMIT;
