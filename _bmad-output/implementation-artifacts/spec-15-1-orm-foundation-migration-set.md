---
title: 'Story 15-1: Fresh Flyway V1.. Migration Set & Schema Reset'
type: 'refactor'
created: '2026-08-31'
status: 'done'
baseline_revision: 'e90588487ec10703023571b4f2b5e685244934ce'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The Flyway history is a 68-migration accumulation (V1..V68) that no longer matches the redesigned ORM target (orm-target-blueprint-2026-08-31.md). New module packages (org extensions, inventory, kpi, compliance, integration) have no schema. Per AD-22 the dev-phase DB may be reset — there is no backward-compatibility burden.

**Approach:** Replace the whole migration directory with one fresh consolidated `V1__orm_foundation_schema.sql` producing the blueprint base schema: all existing tables (adapted per resolved decisions) + every new blueprint table. Adapt the JPA entities/services that break (validate mode), update seeds, rewrite schema-asserting migration tests, reset the dev DB, reseed, and get the full test suite green.

## Boundaries & Constraints

**Always:**
- Flyway owns schema; `ddl-auto=validate` must pass after the reset; migrations apply cleanly from empty DB (Testcontainers postgres:17-alpine).
- Naming: snake_case plural tables, snake_case columns, `{singular}_id` FK columns, `uq_/idx_/ck_` named constraints; `CREATE EXTENSION IF NOT EXISTS btree_gist;` at top of V1 (repair_sessions EXCLUDE needs it).
- TIMESTAMPTZ + `Instant`, UUID PKs (`gen_random_uuid()` default) except `work_orders` VARCHAR(50), `BigDecimal` for decimals, enums uppercase via CHECK, JSONB via `jsonb`.
- Workorder dual-source kept: `work_orders.id` VARCHAR(50) PK; `source` CHECK `('EXTERNAL','INTERNAL')` (DP5); `status` CHECK 6 values `OPEN, IN_PROGRESS, PENDING_SPAREPART, PENDING_REVIEW, CLOSED, CANCELLED`.
- Resolved renames in V1: `department_members`→`department_users`; `sparepart_stock`→`inventory_stock_balances` (UUID PK, `sparepart_master_id`→`sparepart_id` FK + `location_id` FK, unique per pair, `available/reserved/consumed/minimum_stock`); `workorder_signatures`→`user_signatures` + `signature_uses` (DP3/DP4).
- All new blueprint tables created in V1: Modul A (system_roles, role_permission_mappings, menu_features, domain_contexts, user_job_bindings, user_role_bindings, machine_areas, plant_working_calendars(+_dates), departments/job_titles extended per A2/A4), Modul B (work_assignments, work_logs; status_history + actor_type), Modul C (work_log/work_order rating criteria + categories + quality ratings), Modul D (spareparts + BOM master columns: hierarchy_identity_key unique, bom_serial, bom_code unique, bom_code_version, review_status ck, rejection_reason), Modul E (inventory_locations/transfers/reservations), Modul F (pm_frequencies…pm_execution_items), Modul G (kpi_* tables), Modul H (non_conformances, eight_d_reports, calibration_*, equipment_change_notices, machine_setup_baselines, lesson_learned, historical_machine_records), Modul I (user_signatures, signature_uses, auth_login_audits, phone_verification_challenges, webhook_configs, webhook_delivery_logs, whatsapp_message_logs).
- Seed strategy: keep `db/seed/*.sql` as manually-applied psql scripts (NOT Flyway R__); update both to the new schema (status values, renamed tables/columns, `work_order_id` naming) while preserving canonical natural keys (GM1, BF-08410, JBF19, TECHNICIAN/STAFF/LEADER roles, 90% threshold).

**Block If:**
- Any existing integration test cannot be mapped to the new schema and no defensible adaptation exists → HALT blocked.
- `docker` unavailable for reset/Testcontainers → HALT blocked.
- A blueprint table proves un-writable (contradiction between proposal §4 and blueprint) → HALT blocked with the contradiction.

**Never:**
- No JPA auto-DDL, no editing/resequencing of applied migrations, no backward-compat V0/baseline shims.
- Do NOT delete the existing `preventive_programs/schedules/checklist_*` tables — keep them alongside the new PM tables; their replacement happens in the PM redesign epic.
- Do NOT reimplement workorder execution semantics (work_assignments/work_logs flows, ON_PROCUREMENT derivation) — 15-1 only aligns enum values/format; full execution redesign is a later epic.
- Do NOT change WAHA/MQTT contracts, OPA policy, auth model, or frontend.
- Do NOT create Flyway seed migrations; do not touch non-postgres compose volumes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh DB apply | empty postgres:17 container | V1 applies once; all tables/constraints/indexes exist; `flyway_schema_history` has exactly 1 row | migration error fails boot |
| Validate against stale entity | entity column/type ≠ schema | boot fails with clear Hibernate validation error | caught before tests merge |
| Reset dev DB | `DROP DATABASE syncro; CREATE DATABASE syncro;` | re-migrate + reseed succeed; pilot + demo seeds idempotent | seed script `ON_ERROR_STOP=1` aborts |
| Old DB re-migrate attempt | DB with legacy 68-row history | Flyway rejects (history mismatch) — dev DB is dropped first, never in-place | documented in Verification |

</intent-contract>

## Code Map

### Legacy final-state schema (source of truth for the consolidated V1)
- `syncro/apps/backend/src/main/resources/db/migration/V1..V68__*.sql` -- DELETE all 68 after V1 is written; their final state is the baseline. Key final states: auth_users (+V23 whatsapp_number, V45 role CHECK 10 values, V58 display_name/nik/phone_number/job_title_id/department_id), plants, auth_user_plant_assignments (composite PK), authz_decisions, sections (CHECK MACHINERY/UTILITY/WORKSHOP), teams/team_members/team_machines, job_titles (V58), departments + department_members (V58), machine_groups (composite uq (id,plant_id) backs machines FK), machines (CHECK ACTIVE/INACTIVE, uq lower(code), optional_telemetry_fields jsonb), machine_responsibilities, machine_{group_,}shift_windows (CHECK shift 1..3), machine_counter_states (PK machine_id), sparepart_taxonomy (4 dims, composite uq (id,dimension)), spareparts (4 taxonomy FKs + dimension cols, material_code unique, machine FK), machine_sparepart_installations (function_name, version), sparepart_price_entries, sparepart_stock (composite PK material_code+plant_id), sparepart_alerts (alert_type CHECK, V41 discriminator CHECKs, partial dedup idx), notification_jobs (status CHECK 7, idempotency_key unique, polymorphic nullable alert_id), notification_attempts, waha_templates, telemetry_quarantine, audit_log (entity_type CHECK 30 values, V68 immutability triggers), work_order_categories, work_orders (VARCHAR50 PK, CHECKs above, report/cpk/fmea/stop_time cols, preventive_schedule_id), work_order_status_history (source CHECK MANUAL/DERIVED/SYNC), workorder_id_sequences (prefix PK), repair_sessions (btree_gist EXCLUDE no-overlap), workorder_attachments, workorder_todos (col `workorder_id` — rename to `work_order_id`), rating_dimensions, workorder_ratings/scores, workorder_signatures (→ replaced), workorder_acks, preventive_programs/schedules/checklist_results/checklist_items/schedule_attachments (KEEP as-is), sparepart_requests (status CHECK 9), sparepart_request_timeline, escalation_configs, sync_watermarks (singleton CHECK), sync_runs, sync_field_mappings (PK field_name, 20 seeded rows), sync_quarantine (jsonb), settings (singleton CHECK).

### Blueprint tables to add (column-level spec)
- `_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md` -- authoritative column lists per module (A1–A13, B1–B5, C1–C6, D1–D3, E1–E4, F1–F8, G1–G7, H1–H7, I1–I5) + enum value table. Follow it literally; adapt names to conventions (plural snake_case, `{singular}_id`).

### Entities/repos that break (adapt in-place, minimal diff)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/.../domain/WorkOrderStatus.java` -- 8→6 values; update every switch/JPQL/CHECK reference.
- `WorkOrderEntity.java`, `WorkOrderStatusHistoryEntity.java` -- enum/source alignment.
- `WorkorderImportService.java` (~:48 `SOURCE_SYNCED="SYNCED"`) + `WorkOrderRepository.java` (:38 `w.source = 'INTERNAL'`) -- SYNCED→EXTERNAL.
- Workorder id generator (format `WO-YYMM-XXXXX`→`WO-YYMMXXXX` no dash) + `WorkOrderIdSequenceRepository`.
- `DepartmentMemberEntity` (org) -- re-anchor to `department_users` (rename table in @Table; class may keep name or rename to DepartmentUserEntity + `AuditEntityType.DEPARTMENT_MEMBER` value update).
- `SparepartStockEntity` + sparepart.stock module (entity/repo/services/tests) -- re-key from (material_code,plant_id) composite to UUID PK over (sparepart_id, location_id) against inventory_stock_balances + new inventory_locations; stock semantics (available/reserved/consumed/minimum_stock) replace stock_on_hand/order_point/order_qty; PICKED_UP decrement + reorder signal adapt to new columns (reorder rule maps to available ≤ minimum_stock → order qty signal).
- `WorkorderSignatureEntity` + signature flow -- re-anchor to `user_signatures` + `signature_uses(subject_type='WORK_ORDER')`.
- `AuditEntityType` -- extend CHECK + enum with new module values (DEPARTMENT_USER, MACHINE_AREA, JOB_TITLE, SYSTEM_ROLE, ROLE_PERMISSION_MAPPING, MENU_FEATURE, DOMAIN_CONTEXT, USER_JOB_BINDING, USER_ROLE_BINDING, PLANT_WORKING_CALENDAR, INVENTORY_LOCATION, INVENTORY_STOCK_BALANCE, ...only those needed by 16-x + renamed ones).
- Convention anchors to copy: `MachineEntity` (ManyToOne LAZY + JSONB + enum STRING style), `WorkOrderRepository` (JPQL text-block @Query style), layering `com.syncro.<module>.{api,application,domain,infrastructure(.db)}`.

### Package scaffolding (AC)
- New: `com.syncro.org.infrastructure.db` package-info for MachineArea/PlantWorkingCalendar/SystemRole/RolePermissionMapping/MenuFeature/DomainContext/UserJobBinding/UserRoleBinding (org package exists), `com.syncro.inventory`, `com.syncro.kpi`, `com.syncro.compliance`, `com.syncro.integration` (new top-level packages, `infrastructure.db/package-info.java` placeholder each). Entities/repositories themselves = story 15-2.

### Tests & verification wiring
- `src/test/java/com/syncro/AbstractPostgresIntegrationTest.java` -- base (postgres:17-alpine, reuse); boots full Flyway; unchanged.
- `com.syncro.db.*MigrationTest` (22 classes) -- DELETE per-story assertions; write ONE `V1BaseSchemaMigrationTest` asserting blueprint-critical constraints (work_orders status/source CHECK values, department_users + unique, inventory_stock_balances + unique, machine_areas + machines.area_id, user_signatures/signature_uses, kpi/compliance/pm tables exist, uq_/idx_/ck_ naming spot-checks, btree_gist extension present).
- `PilotSeedTest` -- update to new schema; keep canonical-row assertions.
- `DbIndexHygieneAtddUpgradePathScaffoldTest` -- DELETE (migrates to V16/V17; meaningless under fresh V1).
- `syncro/apps/backend/pom.xml` -- add `flyway-maven-plugin` (docs already reference `flyway:migrate`; plugin currently missing) with datasource from env.
- `syncro/scripts/seed-pilot.ps1` -- preflight unchanged (history count > 0); no edit expected unless schema asserts.
- `syncro/.env` (local, uncommitted) + `syncro/infra/docker-compose.yml` -- postgres:18 service `postgres`; reset = `DROP DATABASE syncro; CREATE DATABASE syncro;` via `docker compose exec postgres psql -U syncro -d postgres`, then boot or flyway:migrate, then `seed-pilot.ps1` + `workorder-demo-seed.sql`.
- Seeds: `db/seed/pilot-seed.sql` (plants GM1, sections MACHINERY, machine_groups Forming, machines BF-08410/JBF19, taxonomy WECON/PLC/LX5, spareparts + installations + price entries, auth_users, plant assignments, machine_responsibilities) and `db/seed/workorder-demo-seed.sql` (plants 01/02, categories 01/03, users, work_orders w/ old status values — remap to 6-value enum, status history, repair sessions, todos (col rename), id_sequences prime).

## Tasks & Acceptance

**Execution:**
1. `db/migration/` -- write `V1__orm_foundation_schema.sql` (single file): extension; all existing tables (adapted: department_users, work_order_id column, 6-status/source CHECKs, BOM columns on spareparts, machines.area_id, job_titles binding_scope+default_system_role_id, work_order_status_history.actor_type, audit_log.entity_type extended CHECK, FKs for new-module references); all blueprint Modul A–I tables with named constraints; KEEP preventive_* legacy tables unchanged.
2. `db/migration/V2..V68` -- DELETE all 68 legacy files.
3. Package scaffolding -- `com.syncro.{inventory,kpi,compliance,integration}` + org additions via `infrastructure.db/package-info.java` per module.
4. Breaking adaptations -- WorkOrderStatus enum (6 values) + every reference (services, repos JPQL, sync import EXTERNAL, ack/notification paths that excluded ON_PROCUREMENT now exclude PENDING_SPAREPART, state transitions remapped: DRAFT→OPEN, ASSIGNED→IN_PROGRESS, ON_PROCUREMENT→PENDING_SPAREPART, DONE→PENDING_REVIEW); id generator `WO-YYMMXXXX`; DepartmentMember→DepartmentUser; sparepart.stock→inventory_stock_balances re-key (entity/repo/service/test); workorder_signatures→user_signatures/signature_uses; AuditEntityType extension.
5. Seeds -- update pilot-seed.sql + workorder-demo-seed.sql to new schema/enum values; preserve canonical natural keys & fixed literal UUIDs.
6. Tests -- write `V1BaseSchemaMigrationTest`; update PilotSeedTest + module integration tests that assert old status values/table names; delete 22 per-story migration tests + DbIndexHygiene scaffold test.
7. `pom.xml` -- add flyway-maven-plugin (env-bound url/user/password).
8. Dev DB reset + reseed -- DROP/CREATE `syncro` DB; migrate; apply both seeds; boot health smoke.

**Acceptance Criteria:**
- Given an empty Testcontainers DB, when Flyway runs, then exactly one V1 row exists and every blueprint table + adapted existing table exists with named uq_/idx_/ck_ constraints (V1BaseSchemaMigrationTest green).
- Given `ddl-auto=validate` and the adapted entities, when the backend boots against the fresh schema, then validation passes (full `mvn test` green).
- Given the dev DB is dropped and recreated, when migrate + both seeds run, then canonical rows re-appear (PilotSeedTest assertions + seed script summary tripwires) and `GET /api/v1/health` returns OK.
- Given workorder code paths, when compiled, then no reference to removed status values (DRAFT/ASSIGNED/ON_PROCUREMENT/DONE) or `SYNCED` source remains (grep-clean build).

## Spec Change Log

## Review Triage Log

## Design Notes

- Consolidation order inside V1 follows FK dependency: extension → auth/plants → org → machines → spareparts → maintenance → preventive → requests/alerts/notifications → sync/settings → NEW modules (org-A tables need system_roles before job_titles.default_system_role_id FK; inventory before pm/kpi/compliance where referenced).
- Reorder signal semantics under new stock model: `available ≤ minimum_stock` replaces `stock_on_hand ≤ order_point` (POC proposal §4); order qty comes from the PR flow, not stored column — keep `consumed` running total for lifetime usage.
- Workorder ack-clock exclusion set changes ON_PROCUREMENT→PENDING_SPAREPART; derived-status writes to history use `source='DERIVED'`, `actor='SYSTEM'` as today (no new mechanism).
- Seeds keep BEGIN..COMMIT + `INSERT..SELECT..WHERE NOT EXISTS` guards; fixed UUID literals preserved so e2e/API tests keep natural-key stability.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="com.syncro.db.*"` -- expected: V1BaseSchemaMigrationTest + PilotSeedTest green.
- `mvn -f syncro/apps/backend/pom.xml test` -- expected: full suite green (Docker required).
- `docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml exec -T postgres psql -U syncro -d postgres -c "DROP DATABASE IF EXISTS syncro; CREATE DATABASE syncro;"` -- expected: clean recreate.
- `mvn -f syncro/apps/backend/pom.xml flyway:migrate` (env from syncro/.env) -- expected: 1 migration applied.
- `powershell -NoProfile -File syncro/scripts/seed-pilot.ps1` + apply workorder-demo-seed.sql via the documented psql pipe -- expected: idempotent inserts, summary tripwires pass.
- Boot backend (`syncro/scripts/start-backend.ps1`), `Invoke-RestMethod http://localhost:8080/api/v1/health` -- expected: OK.
- `git grep -nE "(DRAFT|ASSIGNED|ON_PROCUREMENT|'DONE'|SYNCED)" -- syncro/apps/backend/src/main/java` -- expected: only false-positive-safe hits (word boundaries verified manually); no live enum references.

**Manual checks (if no CLI):**
- Spot-check `flyway_schema_history` has 1 row in dev DB after reset.
