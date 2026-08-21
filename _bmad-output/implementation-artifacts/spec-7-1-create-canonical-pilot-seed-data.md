---
title: 'Create Canonical Pilot Seed Data'
type: 'feature'
created: '2026-08-22'
status: 'ready-for-dev'
review_loop_iteration: 0
followup_review_recommended: false
baseline_commit: eb33f45
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** Epic 7 must prove Phase 1 end-to-end (SM-001, SM-004, SM-005, SM-006) against a repeatable industrial scenario, but no canonical pilot dataset exists. Every validation story (7-2 fixtures, 7-3 scripts, 7-4/7-5/7-6 behavior proofs, 7-7 documentation) needs the exact same master data — plant `GM1`, group `Forming`, machine `BF-08410` / `JBF19`, sparepart `Electric PLC Wecon LX5` installed with a 90% threshold, and `TECHNICIAN`/`STAFF`/`LEADER` escalation recipients — or their evidence cannot be compared or repeated. Today that data exists only as scattered hardcoded rows inside individual test classes (`TelemetryValidationIntegrationTest`, `MachineServiceIntegrationTest`, `MachineSparepartInstallationServiceIntegrationTest`), never as an applicable dataset for a running local PostgreSQL.

**Approach:** One canonical, idempotent SQL seed file at the architecture-prescribed path `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` (architecture.md directory tree names `db/seed/pilot-seed.sql` exactly; "Seed, Fixtures, and Tests" section: `apps/backend/src/main/resources/db/seed/` contains local dev and pilot demo seed data). The seed is NOT a Flyway migration and is NOT added to Flyway locations — `application.yml` pins `spring.flyway.locations: classpath:db/migration`, so the seed stays a deliberate, manually-applied local-dev artifact (Story 7-3's `seed-pilot.ps1` will automate application). A Testcontainers-backed verification test (`com.syncro.db.PilotSeedTest`, following the established `DbIndexHygieneAtddUpgradePathScaffoldTest` pattern: PostgreSQLContainer + Flyway migrate + JdbcTemplate, no Spring context) applies migrations then the seed and asserts every canonical row, linkage, backend-format fidelity, empty runtime-owned tables, and idempotent re-run.

## Boundaries & Constraints

**Always:**
- Exact canonical values fixed by PRD §12 "Pilot Validation Scenario" and architecture.md "Contract, Seed, and Time Patterns": plant `GM1`, group `Forming`, machine code `BF-08410` with name `JBF19` (one machine — code and name, NOT two machines; PRD §12 lists "Machine code: BF-08410 / Machine name: JBF19"), sparepart taxonomy `Electric`/`PLC`/`Wecon`/`LX5`, responsibility levels `TECHNICIAN`/`STAFF`/`LEADER`, threshold 90%.
- Idempotency via `INSERT ... SELECT ... WHERE NOT EXISTS` guarded on natural keys — the established pattern of `V13__update_categories.sql` and `V15__link_sparepart_taxonomy_to_category.sql`. Fresh rows use fixed deterministic UUIDs (so 7-3's verify script and later fixtures can reference them); dependent rows resolve FK ids by natural-key lookup (login_identifier / code) at apply time, never by cross-referencing a hardcoded UUID for rows the seed does not own.
- Sparepart row must mirror exactly what the backend create path would generate: code `BF08410GM1ELEPLCWEC000` (= raw `machine.getCode()` `BF-08410` + plant `GM1` + `codePart(category 'ELECTRIC')='ELE'` + `codePart(kind 'PLC')='PLC'` + `codePart(brand 'WECON')='WEC'` + series `000`, per `SparepartService.bomPrefix`/`nextBomCode`/`codePart`) and name `Electric · PLC · Wecon · LX5` (= `category.name + " · " + kind.name + " · " + brand.name + " · " + type.name`, per `SparepartService.sparepartLabel`). The epics' label "Electric PLC Wecon LX5" refers to this backend-exact row.
- Reuse the `CATEGORY 'ELECTRIC'` row that migration `V13` already guarantees in every database; only link to it. New taxonomy rows `BRAND 'WECON'`, `KIND 'PLC'`, `TYPE 'LX5'` must set `category_id` to the ELECTRIC category (V15 check constraint `ck_sparepart_taxonomy_category_link` requires non-CATEGORY rows to have a category; backend `SparepartService.validateLinkedTaxonomy` enforces the same linkage rule on create).
- All seeded values must satisfy existing CHECK constraints and be producible by backend rules ("Seed data must not include fake statuses that cannot happen through backend lifecycle rules" — architecture.md): machine `status='ACTIVE'` (`ck_machines_status`), installation `expected_production_count=1000 > 0`, `baseline_counter=0 >= 0`, `threshold_percentage=90` (BETWEEN 1 AND 100), `function_name='Primary'` (V12 default), responsibility levels from `ResponsibilityLevel` enum, `application_role='VIEWER'` (`ck_auth_users_application_role`).
- Pilot counter math must be pinned in the seed header so 7-2 fixtures align: `consumed = CountingDeltaCalculator.delta(baseline, counting)` = `floorMod(counting − 0, 65536)`; `consumedPercentage = consumed × 100 / 1000` (HALF_UP, 2dp, `SparepartLifetimeEvaluator`). `counting=890` → 89.00% < 90 → no alert (7-4); `counting=900` → 90.00% ≥ 90 → one OPEN alert + TECHNICIAN job (7-5, `SparepartAlertService` fires at `consumedPercentage >= thresholdPercentage`). Both values are positive, so they pass the `out_of_range` plausible-value check (negative runtimeHours/counting only).
- Three recipient users seeded with: email-style login identifiers `technician.gm1@syncro.dev`, `staff.gm1@syncro.dev`, `leader.gm1@syncro.dev`; `application_role='VIEWER'` (recipients need no management rights; acknowledge in 7-6 is "any authenticated user with plant access" per `SparepartAlertCommandService.acknowledge`); `enabled=true`; a `$2a$` bcrypt hash of the documented local-only password `syncro-pilot-dev` (generate once via Spring Security `new BCryptPasswordEncoder().encode("syncro-pilot-dev")` and embed; one shared hash is fine); non-null `whatsapp_number` placeholders (e.g. `6281234567801/02/03`) because `NotificationRoutingService`/`EscalationService` route a job to `ROUTING_FAILED` when the recipient's `whatsapp_number` is null/blank; plant assignment rows in `auth_user_plant_assignments` → GM1.
- One `machine_responsibilities` row per level, each resolving `user_id` by login identifier lookup; `created_at`/`updated_at` set explicitly (V14 has NO defaults — a bare insert without them fails).
- Seed file header comment must document: purpose, precondition (Flyway migrations applied — backend booted once or `flyway:migrate`), apply command (`docker compose -f syncro/infra/docker-compose.yml exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql`, credentials from `syncro/.env`; local defaults `syncro`/`syncro_dev`/`syncro` per `application-local.yml`), re-run safety, the pilot password, and that WhatsApp placeholders must be replaced with real numbers before a live WAHA pilot.
- `PilotSeedTest` must follow `DbIndexHygieneAtddUpgradePathScaffoldTest` exactly: `@Testcontainers` + `PostgreSQLContainer("postgres:17-alpine")` (the image the db test package already uses; docker-compose's `postgres:18` is compatible because the seed is plain SQL), full `Flyway.configure().dataSource(...).load().migrate()`, then apply the seed from the test classpath (`db/seed/pilot-seed.sql` is in `src/main/resources`, so it is on the test classpath) using `org.springframework.jdbc.datasource.init.ScriptUtils.executeDatabaseScript` (spring-jdbc is already a test dependency via JdbcTemplate usage). NO `@SpringBootTest` — that avoids the documented Testcontainers `influxdb:3-core` startup timeouts that block context-loading tests in this environment.
- Keep the seed free of PL/pgSQL blocks, `DO $$`, and multi-statement functions so `ScriptUtils`' default `;` statement splitting works unchanged.

**Block If:** No decisions require human input for this story. The free parameters (pilot password `syncro-pilot-dev`, placeholder WhatsApp numbers, counter baseline 0 / expected 1000, fixed UUIDs) are pinned by this spec with rationale; the canonical identifiers are fixed by PRD §12 / architecture.md.

**Never:**
- Never add `db/seed` to `spring.flyway.locations` or create a `V31__` seed migration — the seed must stay a deliberate local-dev artifact, not auto-applied schema evolution (Flyway migrations are forward-only and never edited; a seed that runs in every environment would pollute non-pilot databases).
- Never insert into runtime/backend-owned tables: `machine_counter_states` (V20, written by `TelemetryPersistenceService`), `sparepart_alerts` (V19, created only by `SparepartAlertService` from accepted telemetry), `notification_jobs`/`notification_attempts` (V24/V25, outbox-owned), `telemetry_quarantine` (V9), `audit_log` (V16 — migrations and seed are infra bootstrap, not user actions; V13/V15/V22 set the precedent of seeding without audit rows).
- Never seed the local admin user (`admin@syncro.dev`) — `LocalAdminBootstrap` owns its idempotent creation at backend startup (SUPER_ADMIN, `findByLoginIdentifierIgnoreCase` guard); a seed copy would fight the bootstrap's password source.
- Never touch `waha_templates` — `V22__seed_default_waha_template.sql` already seeds the `alert_notification` template in every database.
- Never use `MERGE`, CTE-wrapped multi-inserts, or expression-index `ON CONFLICT` targets — plain `INSERT ... SELECT ... WHERE NOT EXISTS` only, mirroring V13/V15.
- Never put real personal data, real WhatsApp numbers, or real credentials in the committed seed; placeholders and the documented local-dev password only. Never modify any existing migration, `application.yml`, `application-local.yml`, or production Java code — this story adds one SQL artifact and one test.
- Do not create `seed-pilot.ps1` (Story 7-3), MQTT payload fixtures (Story 7-2, root `syncro/tests/fixtures/`), `docs/pilot-validation.md` (Story 7-7), or the `support/PilotFixture.java` / `fixtures/pilot-fixture.json` helpers from the architecture tree — no consumer story requires them yet; canonical constants live in the seed and its test until 7-2/7-3 need a shared fixture class.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh migrated DB (test happy path) | Flyway V1–V30 applied, seed applied once | Exactly: 1 plant `GM1`, 1 group `Forming`→GM1, 1 machine `BF-08410`/`JBF19` ACTIVE, 1 sparepart `BF08410GM1ELEPLCWEC000`, 1 installation (1000/0/90, function `Primary`), 3 pilot users (VIEWER, enabled, whatsapp set), 3 plant assignments, 3 responsibilities (TECHNICIAN/STAFF/LEADER) | No error |
| Seed re-applied | `WHERE NOT EXISTS` guards hit on every row | Zero new rows; all counts identical (asserted in test by applying twice) | No error |
| V13 already seeded ELECTRIC category | Every DB has `CATEGORY 'ELECTRIC'/'Electric'` | Seed links to the existing row; `COUNT(*)` of CATEGORY rows named 'Electric' stays 1; `uq_sparepart_taxonomy_dimension_lower_name` never violated | No error |
| Pilot user already exists with different UUID (created manually earlier) | `auth_users` row with same login_identifier | User insert skipped; responsibilities and plant assignments resolve the ACTUAL id via login lookup — no FK violation | No error |
| Seed applied before migrations | Empty/schema-less database | FK failures fail loudly and atomically per statement; header documents precondition (migrations first); test proves the supported order | Loud failure is correct |
| Taxonomy WECON/PLC/LX5 pre-exists (UI-created) | Operator created e.g. BRAND 'WECON' linked to another category | Seed insert skipped (natural-key guard); sparepart still references the existing row; noted acceptable local-dev edge — backend taxonomy-linkage validation may reject later UI edits of that row | No error; documented |
| Pilot flow already ran on this DB | `sparepart_alerts`/`notification_jobs` have rows from a previous 7-5 run | Seed touches none of those tables; re-validation of a dirty DB is out of scope (DB reset requires explicit user confirmation per project rules) | No error |
| Backend boots after seed applied | `ddl-auto=validate`, Flyway at V30 | Startup unaffected; seed rows satisfy entity mappings; LocalAdminBootstrap still creates/keeps admin independently | No error |
| Placeholder WhatsApp numbers at live pilot | WAHA cannot deliver to `6281234567801` | Not a seed defect: jobs still queue PENDING and record attempt failure evidence (which 7-5/7-6 can observe); header instructs replacing numbers first | Documented |
| bcrypt hash drift | Operator edits the embedded hash without updating the comment | `PilotSeedTest` pins `BCryptPasswordEncoder.matches("syncro-pilot-dev", <shipped hash>)` so the committed hash is always verifiable | Test fails |
| Threshold boundary check (consumers) | 7-2 fixtures publish counting 890 then 900 | 890 → 89.00% < 90 → no alert; 900 → 90.00% ≥ 90 → exactly one OPEN alert (HALF_UP, `BigDecimal` compare in `SparepartAlertService`) | Math pinned in seed header |

</intent-contract>

## Code Map

**All paths relative to repo root; only NEW files in this story.**

- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- NEW -- the canonical seed. Creates the `db/seed/` directory (architecture tree; does not exist yet — only `db/migration/` does). Contents in dependency order, each statement idempotent:
  1. Header comment block (purpose, canonical values table, preconditions, apply command, pilot math, password + WhatsApp caveats, re-run safety).
  2. `plants`: fixed UUID, `code='GM1'`, `name='Plant GM1'` (test convention from `TelemetryValidationIntegrationTest`), guard `WHERE NOT EXISTS (code='GM1')`.
  3. `machine_groups`: fixed UUID, FK resolved from plants by code, `name='Forming'`, guard on `(plant, name)` (unique `uq_machine_groups_plant_id_name`).
  4. `machines`: fixed UUID, plant + group resolved by code/name, `code='BF-08410'`, `name='JBF19'`, `status='ACTIVE'`, `brand='Juki'` (canonical test convention), `installed_at DATE '2026-05-27'` (canonical test convention), `notes=NULL`, `optional_telemetry_fields=NULL` (V18 column), guard on `(plant, lower(code))`.
  5. `sparepart_taxonomy` — no new CATEGORY: resolve existing `ELECTRIC` id. Insert `BRAND 'WECON'/'Wecon'`, `KIND 'PLC'/'PLC'`, `TYPE 'LX5'/'LX5'` with `category_id` → ELECTRIC row, `created_at/updated_at=CURRENT_TIMESTAMP`, guards on `(dimension, code)`.
  6. `spareparts`: fixed UUID, `code='BF08410GM1ELEPLCWEC000'`, `name='Electric · PLC · Wecon · LX5'`, `machine_id` → BF-08410 machine, category/brand/kind/type ids resolved by `(dimension, code)`; rely on (or set explicitly) the dimension discriminator columns' defaults `'CATEGORY'/'BRAND'/'KIND'/'TYPE'` (V7). Guard: machine + taxonomy identity not already present.
  7. `machine_sparepart_installations`: fixed UUID, machine + sparepart resolved by code, `expected_production_count=1000`, `baseline_counter=0`, `threshold_percentage=90`, `function_name='Primary'`, `installed_at=CURRENT_TIMESTAMP`, guard on `(machine, sparepart, lower(function_name))` (V12 unique index).
  8. `auth_users` ×3: fixed UUIDs, logins `technician.gm1@syncro.dev` / `staff.gm1@syncro.dev` / `leader.gm1@syncro.dev`, shared embedded `$2a$` bcrypt hash of `syncro-pilot-dev`, `application_role='VIEWER'`, `enabled=TRUE`, `whatsapp_number='6281234567801'/'6281234567802'/'6281234567803'`, guard on `login_identifier`.
  9. `auth_user_plant_assignments` ×3: composite insert resolving both user id (by login) and plant id (by code), guard on the pair (V2 composite PK).
  10. `machine_responsibilities` ×3: fixed UUIDs, machine resolved by code, `user_id` resolved by login identifier, `level='TECHNICIAN'/'STAFF'/'LEADER'`, explicit `created_at`/`updated_at=CURRENT_TIMESTAMP` (V14 has no defaults), guard on `(machine, user)`.
- `syncro/apps/backend/src/test/java/com/syncro/db/PilotSeedTest.java` -- NEW -- verification test, package `com.syncro.db` beside the DbIndexHygiene tests. `@Testcontainers`, `PostgreSQLContainer("postgres:17-alpine")`, plain JDBC (no Spring context). Test methods:
  - `migrationsThenSeedProduceCanonicalRows` — Flyway full migrate → apply seed via `ScriptUtils.executeDatabaseScript(connection, "", seedSql)` → assert every row above by natural key (plant/group/machine status/sparepart code+name/installation values/function/taxonomy identity and linkage `category_id` of WECON/PLC/LX5 → the single 'Electric' CATEGORY row/users enabled+VIEWER+whatsapp/plant assignments/3 responsibility levels with 3 distinct users).
  - `seedCreatesNoRuntimeOwnedRows` — counts of `machine_counter_states`, `sparepart_alerts`, `notification_jobs`, `notification_attempts`, `telemetry_quarantine`, `audit_log` all 0; `waha_templates` still exactly the V22 row (`template_key='alert_notification'`).
  - `seedIsIdempotent` — snapshot counts of all touched tables → apply seed a second time → counts identical.
  - `embeddedHashMatchesDocumentedPassword` — `new BCryptPasswordEncoder().matches("syncro-pilot-dev", <hash read from the seeded user row via JDBC>)` is true (guards against silent hash drift).
- Read-only references (do not modify): `db/migration/V13__update_categories.sql` + `V15` (ELECTRIC category + linkage, idempotent-insert style to mirror), `V22` (repo's seed precedent), `application.yml` (Flyway locations stay `classpath:db/migration`), `SparepartService.java` (`bomPrefix`/`nextBomCode`/`codePart`/`sparepartLabel` — the formats the seed must reproduce), `SparepartLifetimeEvaluator.java` + `CountingDeltaCalculator.java` + `SparepartAlertService.java` (threshold math the counters must satisfy), `NotificationRoutingService.java` + `EscalationService.java` (why whatsapp_number and per-level assignments are mandatory), `MachineResponsibilityService.java` (assign semantics the rows mirror), `LocalAdminBootstrap.java` (why the seed must not create the admin), `DbIndexHygieneAtddUpgradePathScaffoldTest.java` + `DbIndexHygieneTestData.java` (test pattern and JdbcTemplate helper style), `ResponsibilityLevel.java` (enum values), `MachineSparepartInstallationService.java` (installation create semantics mirrored by the row).

## Tasks & Acceptance

**Execution:**

- [ ] `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- NEW -- header documentation block + the 10 idempotent statement groups from the Code Map, using fixed UUIDs for fresh rows, natural-key `WHERE NOT EXISTS` guards, login/code-resolved FKs for dependent rows, plain single statements only (ScriptUtils-compatible). [AC 7.1-1, AC 7.1-2, AC 7.1-3, AC 7.1-4, AC 7.1-5, AC 7.1-6, AC 7.1-7]
- [ ] Generate the bcrypt hash for the pilot password via Spring Security (`new BCryptPasswordEncoder().encode("syncro-pilot-dev")`, e.g. in a scratch test or jshell with `spring-security-crypto` from the local `~/.m2`), embed the `$2a$` literal in the seed, and record the plaintext in the header comment. [AC 7.1-5, AC 7.1-6]
- [ ] `syncro/apps/backend/src/test/java/com/syncro/db/PilotSeedTest.java` -- NEW -- the four test methods per Code Map, container image `postgres:17-alpine`, seed read from classpath `/db/seed/pilot-seed.sql`, no Spring context. [AC 7.1-1, AC 7.1-2, AC 7.1-3, AC 7.1-4, AC 7.1-5, AC 7.1-6, AC 7.1-7]
- [ ] Verify: run `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="PilotSeedTest"` → BUILD SUCCESS (requires Docker for Testcontainers; postgres-only, no Influx container involved). No frontend changes → no web checks needed; state this explicitly in the completion record. [all ACs]
- [ ] Manual evidence (optional, if local stack is up): apply the seed to the local dev database with the documented `docker compose ... psql` command and spot-check via pgAdmin (local/dev evidence tool only, not a product feature) that GM1/Forming/BF-08410 appear in the Machines master data and the installation shows 90%. [AC 7.1-1..7.1-5]

**Acceptance Criteria:**

- Given local PostgreSQL is running with Flyway migrations applied, when the pilot seed is applied, then the database contains plant `GM1` and machine group `Forming` under `GM1`. [AC 7.1-1]
- Given the seed is applied, then machine `BF-08410` (name `JBF19`) exists under GM1/Forming with `ACTIVE` status. [AC 7.1-2]
- Given the seed is applied, then sparepart `Electric · PLC · Wecon · LX5` (the epics' "Electric PLC Wecon LX5") exists with backend-exact generated code `BF08410GM1ELEPLCWEC000` and is installed on the BF-08410/JBF19 machine with function `Primary`. [AC 7.1-3]
- Given the seed is applied, then the installation carries `expected_production_count=1000`, `baseline_counter=0`, and `threshold_percentage=90`, such that `counting=890` yields 89% (no alert) and `counting=900` yields 90% (alert) — suitable for the 7-4/7-5 validation math pinned in the seed header. [AC 7.1-4]
- Given the seed is applied, then responsibility assignments on the machine include exactly one `TECHNICIAN`, one `STAFF`, and one `LEADER` recipient, each an enabled `VIEWER` user with a non-blank `whatsapp_number` and a GM1 plant assignment (enables the 7-6 acknowledge path and prevents `ROUTING_FAILED` notification jobs). [AC 7.1-5]
- Given the seed is applied twice, then no row counts change (idempotent), and the seed creates zero rows in runtime-owned tables (`machine_counter_states`, `sparepart_alerts`, `notification_jobs`, `notification_attempts`), zero audit rows, and does not touch the V22 WAHA template or the LocalAdminBootstrap admin user — only lifecycle states possible through backend rules are represented. [AC 7.1-6]
- Given CI or a developer machine with Docker, when `PilotSeedTest` runs, then it applies migrations + seed against a real PostgreSQL container and asserts every canonical row, taxonomy linkage, empty runtime tables, idempotency, and that the embedded bcrypt hash verifies against the documented pilot password. [AC 7.1-7]

## Spec Change Log

- 2026-08-22: Spec created (draft → ready-for-dev). Ultimate context engine analysis completed — comprehensive developer guide created.

## Design Notes

- **Why a standalone SQL seed, not a Flyway migration and not API-driven seeding:** the architecture's directory tree explicitly reserves `apps/backend/src/main/resources/db/seed/pilot-seed.sql`, and its "Seed, Fixtures, and Tests" section scopes `db/seed/` to "local dev and pilot demo seed data". A versioned migration would auto-apply pilot data to every environment and could never be edited once applied (forward-only rule); driving the seed through backend REST APIs would require auth bootstrapping and make 7-3's repeatable script brittle. Manual/psql application keeps the dev database the only target; 7-3's `seed-pilot.ps1` ("applies or documents pilot seed flow") will wrap exactly this file, so the file name and header contract here are load-bearing for that story.
- **Backend-format fidelity is the point of AC "lifecycle states possible through backend rules":** the sparepart code and the ` · `-separated label are not cosmetic — they are deterministic outputs of `SparepartService` (`bomPrefix` concatenates the RAW machine code including the dash: `BF-08410` + `GM1` + `ELE` + `PLC` + `WEC`, series `000`). A seed row that a user could never have created through the UI (wrong code format, prose name) would violate the architecture rule "Seed data must not include fake statuses that cannot happen through backend lifecycle rules" and would desync 7-3's verification queries.
- **Counter math chosen for readable boundaries:** `baseline_counter=0`, `expected_production_count=1000`, threshold 90 → the 7-2 fixtures can use round numbers 890/900 with the alert boundary exactly at 90.00% (`>=` per `SparepartAlertService`; percentage HALF_UP 2dp per `SparepartLifetimeEvaluator`; wrap-aware delta per `CountingDeltaCalculator` — irrelevant at baseline 0 but documented so fixtures stay correct if the baseline ever changes). Both counts are positive, satisfying the `out_of_range` plausible-value rejection (negatives only).
- **Recipients as VIEWER users with plant assignment:** `NotificationRoutingService` queues the initial job only when a TECHNICIAN exists for the machine AND has a non-blank `whatsapp_number` — otherwise the pilot's first evidence row is a `ROUTING_FAILED` job and 7-5 fails its "job is queued or sent" AC. `EscalationService` repeats the same for STAFF/LEADER on the next escalation tick. Acknowledge (7-6) requires only "any authenticated user with plant access", so VIEWER role + `auth_user_plant_assignments` → GM1 keeps least privilege while keeping every downstream story functional.
- **Why users are seeded via SQL at all:** Phase 1 has no user-management API (`AuthService` exposes login/current/list only; the only other user-creation path is `LocalAdminBootstrap` for the admin). SQL seeding of pilot recipients is therefore the only mechanism consistent with the codebase, and it matches how `DbIndexHygieneTestData.authUser(...)` creates users in tests.
- **Idempotency style follows V13/V15, not `ON CONFLICT`:** the repo's established idempotent-insert idiom is `INSERT ... SELECT ... WHERE NOT EXISTS`, and two unique constraints here are expression indexes (`uq_machines_plant_id_lower_code`, the V12 installation index on `lower(function_name)`) whose `ON CONFLICT` targeting is brittle. Natural-key guards sidestep both. Fixed UUIDs still give 7-3 deterministic references for rows the seed itself creates; rows the seed does NOT own (pre-existing users/taxonomy) are resolved by lookup at apply time so a re-run can never violate an FK.
- **Test placement and shape:** `com.syncro.db` already owns migration-level Testcontainers tests (`DbIndexHygieneMigrationTest`, `DbIndexHygieneAtddUpgradePathScaffoldTest`) — the seed is exactly that kind of artifact. The plain-JDBC pattern (no `@SpringBootTest`) is deliberate: spec-6-5/6-6/6-7 documented that context-loading Testcontainers tests stall on `influxdb:3-core` startup in this environment, while the postgres-only db tests run green.
- **No `PilotFixture.java` / `pilot-fixture.json` yet:** the architecture tree lists them under test support/fixtures, but no current consumer needs a shared fixture class — 7-2's deliverables live in root `syncro/tests/fixtures/` (MQTT payloads). Creating them now would be speculative; the seed file + test constants are the single source of canonical values until a story actually consumes a fixture (noted to prevent both reinvention and scope creep).
- **Grounding note (web research):** none required — every technical fact in this spec is pinned to the actual repo (migrations, services, tests, configs read at spec time); no new libraries, versions, or external APIs are introduced.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="PilotSeedTest"` -- expected: BUILD SUCCESS, all four test methods green (requires a running Docker daemon for the `postgres:17-alpine` Testcontainer; no Influx/Redis/EMQX containers involved).
- `git status --short` after implementation -- expected: exactly two new files under `syncro/apps/backend/src/` plus this spec/sprint-status artifacts; zero modifications under `syncro/apps/web/` (no frontend involvement).

**Manual checks (if no CLI Docker):**
- Apply the seed to the local dev stack (`docker compose -f syncro/infra/docker-compose.yml exec -T postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" < syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql`), verify rows via pgAdmin (local/dev evidence tool only) or `psql` counts: `SELECT code FROM plants WHERE code='GM1';` etc.; run the apply twice and confirm counts unchanged. If Docker/Testcontainers is unavailable in the runtime environment, state that explicitly and rely on the manual local-stack evidence.

## Dev Agent Record

### Agent Model Used

GLM-5.3 (ZCode, builtin:zai-start-plan/GLM-5.3)

### Debug Log References

### Completion Notes List

### File List
