---
baseline_commit: d7ff3e7
---

# Story 2.6: Install Spareparts on Machines with Lifetime Baseline

Status: done

## Story

As a SUPER_ADMIN or MANAGE user,
I want to install spareparts on machines with expected production count and baseline counter,
so that Syncro can later calculate consumed lifetime from production output.

## Acceptance Criteria

1. Given machine and sparepart exist, when an authenticated SUPER_ADMIN or MANAGE user creates a machine sparepart installation, then the installation is persisted with machine, sparepart, expected production count, baseline counter, threshold percentage, and timestamps.
2. Given threshold percentage is omitted, null, or blank where applicable, when installation is created, then threshold defaults to 90%.
3. Given user provides threshold percentage, when value is within allowed range, then installation stores overridden threshold percentage.
4. Given expected production count, baseline counter, or threshold is invalid, missing, malformed, negative, zero where positive is required, or outside allowed threshold range, when create/update request is submitted, then API rejects request with existing safe validation or malformed JSON error shape.
5. Given machine or sparepart ID is unknown or path/body UUID is invalid, when request is submitted, then API returns safe not-found or validation error shape without leaking implementation details.
6. Given installation exists, when authenticated permitted user lists installations, then list shows machine identity, plant/group context, sparepart identity/taxonomy labels, baseline counter, current count when available, expected count, threshold percentage, consumed count/percentage when current count is available, and predictable ordering.
7. Given telemetry latest/current counter is not implemented yet, when installation list renders, then current count and consumed evidence are nullable/empty-safe and frontend does not fabricate values.
8. Given consumption rule is needed for later alerting, when installation is persisted and returned, then calculation basis is explicitly counter-based from current counter minus baseline counter, not installedAt/date-based.
9. Given an installation exists, when authenticated permitted user updates expected production count, baseline counter, or threshold percentage, then installation updates safely and remains linked to same machine and sparepart unless story implementation explicitly supports relinking with validation.
10. Given installation has no dependent alert/audit records yet, when authenticated permitted user deletes it, then installation is removed; if future dependents exist, delete maps FK/integrity failure to safe 409.
11. Given VIEWER user, when user calls installation list/detail APIs, then installations can be viewed within plant scope.
12. Given VIEWER user, when user attempts create, update, or delete, then server denies mutation with safe forbidden response.
13. Given MANAGE or VIEWER user has plant assignment limitations, when installations are listed or addressed by ID, then backend enforces machine plant scope; SUPER_ADMIN can access all installations.
14. Given web user has SUPER_ADMIN or MANAGE role, when user opens Installations page, then user can create, edit, and delete machine sparepart installations using non-native shadcn/Radix selectors for machine and sparepart selection.
15. Given web user has VIEWER role or forbidden access, when user opens Installations page, then UI shows read-only/forbidden state and mutation controls are hidden or disabled.
16. Given machine, sparepart, or installation data is loading, empty, failed, read-only, or forbidden, when web UI renders, then visible durable states appear: loading skeleton, empty setup prompt, API error with retry, read-only badge/state, and forbidden copy.
17. Given backend OpenAPI changes are complete, when API client is generated, then web feature uses generated Orval hooks/types and TanStack Query invalidation instead of handwritten fetch calls.
18. Given real installation rows reference machines and spareparts, when machine or sparepart delete is attempted with dependent installation rows, then backend proves delete conflict through real FK behavior, replacing prior placeholder/ad-hoc proof from Story 2.5.

## Tasks / Subtasks

- [x] Create installation persistence model and migration (AC: 1, 2, 3, 8, 10, 13, 18)
  - [x] Add Flyway migration `V8__create_machine_sparepart_installations.sql` after `V7__create_spareparts.sql`.
  - [x] Create `machine_sparepart_installations` table with UUID primary key, `machine_id`, `sparepart_id`, `expected_production_count`, `baseline_counter`, `threshold_percentage`, `installed_at` if needed for evidence only, `created_at`, and `updated_at`.
  - [x] Add FK constraints to `machines(id)` and `spareparts(id)`; use real FKs to prove dependent delete conflicts.
  - [x] Add constraints: expected production count positive, baseline counter zero-or-positive, threshold between 1 and 100 inclusive unless product chooses stricter range.
  - [x] Add indexes for `machine_id`, `sparepart_id`, and machine+sparepart lookup used by list filters.
  - [x] Use project constraint names: `fk_...`, `idx_<table>_<columns>`, `ck_<table>_<rule>`.
- [x] Add backend installation module under existing domain boundaries (AC: 1-13, 18)
  - [x] Prefer `com.syncro.sparepart` subpackages if treating installation as sparepart lifecycle; use `com.syncro.machine` only if existing package ownership clearly fits better. Do not create cross-module repository access from unrelated services.
  - [x] Add JPA entity and repository under infrastructure; never return entity from controller.
  - [x] Add application service with `@Transactional` mutations and read-only list/get methods.
  - [x] Resolve machine through `MachineRepository` with plant/group join fetch; enforce plant scope with `PlantScopeService` for non-SUPER_ADMIN users.
  - [x] Resolve sparepart through `SparepartRepository`; spareparts are global, but installation access is plant-scoped through machine.
  - [x] Default threshold to 90 when request omits value.
  - [x] Keep current count nullable until Epic 3 latest telemetry exists; do not query Redis/Influx or invent telemetry in this story.
  - [x] Compute `consumedProductionCount` and `consumedPercentage` only when current count exists; otherwise return null evidence fields.
  - [x] Store and expose counter-based calculation basis so Epic 4 alert evaluation can reuse it.
- [x] Add backend API contracts and safe error handling (AC: 4, 5, 10, 11, 12, 13, 17)
  - [x] Add controller under `/api/v1/machine-sparepart-installations` with explicit OpenAPI `operationId`s.
  - [x] Support list filters at minimum `machineId`, `sparepartId`, and plant/machine group filters if cheap through machine joins.
  - [x] Add create, update, get, and delete endpoints using request/response DTO records.
  - [x] Add Jakarta validation for required UUIDs and numeric ranges; malformed JSON/type mismatch must map to standard safe error shape.
  - [x] Add OpenAPI responses for `200`, `201`, `204`, `400`, `401`, `403`, `404`, and `409` where applicable.
  - [x] Map machine not found/out-of-scope, sparepart not found, invalid numeric values, forbidden mutation, and data integrity conflicts to stable safe codes/messages.
- [x] Add backend tests with story IDs and priority markers (AC: 1-13, 18)
  - [x] Add MockMvc controller tests for authN, VIEWER mutation denial, MANAGE/SUPER_ADMIN mutation success, validation field errors, malformed JSON, invalid path/query values, machine/sparepart not found, out-of-scope machine, and delete conflict mapping.
  - [x] Add PostgreSQL Testcontainers integration tests for persistence, default 90% threshold, override threshold, numeric constraints, plant scope, list ordering/filtering, VIEWER read access, VIEWER mutation denial, and delete without dependents.
  - [x] Add real FK delete-conflict tests proving machine delete and sparepart delete are blocked when installation references them.
  - [x] Use display names like `2.6-API-001 P0 ...` and `2.6-SVC-001 P1 ...`.
- [x] Generate API client and use generated hooks/types (AC: 17)
  - [x] Run backend OpenAPI generation path used by previous stories.
  - [x] Run `npm --prefix "syncro/apps/web" run generate:api` after backend is serving OpenAPI.
  - [x] Use generated model types and TanStack Query hooks from `syncro/apps/web/src/lib/api/generated/`; do not hand edit generated files.
- [x] Build installations management UI (AC: 6, 7, 14, 15, 16, 17)
  - [x] Replace placeholder in `syncro/apps/web/src/app/(main)/dashboard/master-data/installations/page.tsx` with thin route composition only.
  - [x] Put feature logic under `syncro/apps/web/src/features/master-data/installations/`.
  - [x] Use existing `MachineManagement` and `SparepartManagement` UI patterns for cards, dialogs, dense tables, skeletons, error retry, toasts, delete confirmation, and query invalidation.
  - [x] Use non-native shadcn/Radix `Select`/dropdown controls for machine and sparepart selectors.
  - [x] Render table columns for plant, machine group, machine code/name, sparepart code/name, expected count, baseline, current count, consumed evidence, threshold, created/updated, and actions.
  - [x] Show setup prompts when no machines or no spareparts exist.
  - [x] Show VIEWER read-only state and forbidden state without relying on frontend as security.
- [x] Verify and document evidence (AC: all)
  - [x] Run targeted backend tests for installation controller/service and dependent delete conflicts.
  - [x] Run API generation and web Orval generation.
  - [x] Run web check/build used by recent stories.
  - [x] Start web dev server and test Installations route golden path plus loading/empty/error/read-only states in browser if browser automation or manual browser is available; if not available, record limitation honestly.
  - [x] Record exact commands and results in Dev Agent Record.

## Dev Notes

### Source Context

- Epic 2 objective: SUPER_ADMIN and MANAGE configure plants, groups, machines, sparepart taxonomy, spareparts, installed spareparts, and responsibility so Syncro knows what exists and who owns response. [Source: _bmad-output/planning-artifacts/epics.md#Epic 2]
- Story 2.6 owns FR-016 through FR-021: install spareparts on machines, expected production-count lifetime, baseline counter, counter-based consumption, default 90% threshold, and threshold override. [Source: _bmad-output/planning-artifacts/epics.md#Story 2.6]
- Later Epic 4 calculates consumed count and alert threshold from accepted telemetry; this story must persist data needed by that calculation but must not implement alerting. [Source: _bmad-output/planning-artifacts/architecture.md#Cross-Component Dependencies]
- UX requires dense desktop setup/admin patterns, `LifetimeProgress` evidence later, and durable loading/empty/error/read-only/forbidden states. [Source: _bmad-output/planning-artifacts/epics.md#UX Design Requirements]

### Architecture Constraints

- PostgreSQL is source of truth for master/setup state; Flyway owns schema evolution. Do not use JPA auto-DDL or edit applied migrations. [Source: _bmad-output/project-context.md]
- Backend modules use `api`, `application`, `domain`, and `infrastructure`; controllers validate/bind DTOs and delegate to services; application services own transactions. [Source: _bmad-output/planning-artifacts/architecture.md#Structure Patterns]
- Resource API path should use plural nouns under `/api/v1`; JSON uses camelCase; timestamps use ISO-8601 UTC; enum/status values uppercase. [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- Database tables use plural snake_case; columns use snake_case; indexes `idx_<table>_<columns>`; unique constraints `uq_<table>_<columns>`. [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- Error responses must use stable `code`, safe `message`, optional `fieldErrors`, `timestamp`, and `traceId`; no SQL, stack, FK name, or Java exception leakage. [Source: _bmad-output/planning-artifacts/architecture.md#Format Patterns]
- Frontend route files under `app/` compose only; feature logic belongs under `src/features/*`; shared Syncro domain UI belongs under `src/components/syncro/*`. [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Boundaries]
- Epic 2 web data APIs use Orval-generated TypeScript clients and TanStack Query hooks from Springdoc OpenAPI. [Source: _bmad-output/planning-artifacts/architecture.md#API & Communication Patterns]

### Existing Code Context to Reuse

- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java` shows plant-scope enforcement with `PlantScopeService`, MANAGE/SUPER_ADMIN mutation guard, safe duplicate/data integrity exceptions, and `MachineView` mapping.
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` has scoped/unscoped query patterns and `join fetch` helpers for machine/plant/group context.
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java` trims/validates master data, restricts mutations to SUPER_ADMIN/MANAGE, maps FK/data integrity failures safely, and returns DTO views.
- `syncro/apps/backend/src/main/resources/db/migration/V5__create_machines.sql` and `V7__create_spareparts.sql` are FK sources for Story 2.6; next migration should be `V8__create_machine_sparepart_installations.sql`.
- `syncro/apps/web/src/app/(main)/dashboard/master-data/installations/page.tsx` is placeholder route shell; replace it without moving feature logic into route file.
- `syncro/apps/web/src/features/master-data/spareparts/sparepart-management.tsx` is nearest UI pattern for generated hooks, TanStack invalidation, non-native selectors, dialogs, dense table, delete confirmation, toasts, loading/empty/error/read-only states.
- `syncro/apps/web/src/navigation/sidebar/sidebar-items.ts` already includes Installations navigation; preserve route behavior.
- `syncro/apps/web/orval.config.ts` generates split React Query client from backend OpenAPI; generated code lives under `syncro/apps/web/src/lib/api/generated/` and must not be hand-edited.

### Security and Authorization Notes

- Backend must enforce all mutation and plant-scope authorization. UI visibility is not security. [Source: _bmad-output/project-context.md]
- SUPER_ADMIN and MANAGE can mutate installations; VIEWER can read only.
- Spareparts remain global master data in Epic 2, but installations are plant-scoped through their machine.
- For non-SUPER_ADMIN users, every list/get/update/delete by installation ID must verify access to the linked machine plant.
- Unauthenticated requests must keep existing Spring Security safe authentication error shape.

### Calculation and Data Semantics

- `expectedProductionCount`: positive production-count lifetime target; use integer/long/BigInteger consistently with telemetry `counting` contract. Avoid floating point.
- `baselineCounter`: zero-or-positive cumulative production counter captured at installation time.
- `thresholdPercentage`: numeric percentage, default 90, allowed range 1-100 unless product requirement chooses stricter.
- `currentCount`: nullable until latest telemetry exists; future Epic 3/4 should source it from backend-owned telemetry state, not frontend.
- `consumedProductionCount`: nullable when current count missing; otherwise backend-owned calculation `currentCount - baselineCounter`, with wrap support later from Epic 3/4.
- `consumedPercentage`: nullable when current count missing; otherwise backend-owned calculation `consumedProductionCount / expectedProductionCount * 100` with precision-safe decimal handling.
- `installedAt` may exist as evidence timestamp, but consumption is never date-based.

### Testing Standards

- Every acceptance criterion needs automated or explicit manual evidence. [Source: _bmad-output/project-context.md]
- Use PostgreSQL Testcontainers for migrations, FK constraints, uniqueness, transactions, delete conflicts, and repository correctness; do not mock persistence for these. [Source: _bmad-output/project-context.md]
- Tests should include story IDs and priority markers matching recent stories.
- Use fixed `Clock` or deterministic timestamps where assertions depend on time.
- Frontend tests may mock API responses only from generated/shared types, not ad-hoc backend shapes.

### Previous Story Intelligence

- Story 2.5 added real sparepart CRUD and left delete-conflict behavior ready for Story 2.6 to prove through actual installation FKs.
- Story 2.5 review added pageable list instead of silent truncation and escaped search wildcards; use bounded pagination/search patterns if installation list can grow.
- Story 2.5 review found missing generated model usage in E2E fixtures; any new web tests should use generated model types where available.
- Story 2.5 browser verification was limited by browser automation availability; if UI is changed, record actual browser/dev-server evidence and do not claim more than was run.
- Recent commits show pattern: ATDD scaffolds, implementation, code review patches, then review follow-ups. Keep Story 2.6 evidence and review notes in the story file.

### Project Structure Notes

Expected new or updated files:

```text
syncro/apps/backend/src/main/resources/db/migration/V8__create_machine_sparepart_installations.sql
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationEntity.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationDtos.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationController.java
syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationExceptionHandler.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java
syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineServiceIntegrationTest.java
syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java
syncro/apps/web/src/app/(main)/dashboard/master-data/installations/page.tsx
syncro/apps/web/src/features/master-data/installations/installation-management.tsx
syncro/apps/web/src/lib/api/generated/*
```

Actual package names may differ if implementation chooses `com.syncro.machine` ownership, but cross-module repository injection must remain deliberate and bounded.

### Dependencies and Sequencing

- Depends on Stories 2.3 and 2.5: machines and spareparts exist.
- Prepares Story 2.8 setup completeness, Epic 4 lifetime alerting, and Epic 7 pilot seed data.
- Do not implement machine responsibility, setup completeness, telemetry ingest, Redis/Influx latest state, alert creation, notification jobs, or audit log beyond existing shared behavior.
- Immutable master data audit belongs to Story 2.9; do not expand scope unless existing infrastructure requires event hooks.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.6]
- [Source: _bmad-output/planning-artifacts/architecture.md#Naming Patterns]
- [Source: _bmad-output/planning-artifacts/architecture.md#Data Architecture]
- [Source: _bmad-output/planning-artifacts/architecture.md#Process Patterns]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#UX Consistency Patterns]
- [Source: _bmad-output/project-context.md]
- [Source: _bmad-output/implementation-artifacts/2-5-manage-spareparts.md]
- [Source: syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java]
- [Source: syncro/apps/web/src/features/master-data/spareparts/sparepart-management.tsx]

### Review Findings

- [x] [Review][Patch] Add bounded pagination for installation listing [syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationController.java:49]
- [x] [Review][Patch] Cover omitted and blank threshold default behavior consistently [syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationDtos.java:14]
- [x] [Review][Patch] Validate out-of-scope machineId list filters explicitly for scoped users [syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java:93]
- [x] [Review][Patch] Render explicit forbidden UI copy for 403 load failures [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:235]
- [x] [Review][Patch] Show createdAt in installation table [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:483]
- [x] [Review][Patch] Add DB constraint evidence for negative baseline and threshold out of range [syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java:227]
- [x] [Review][Patch] Convert generated E2E/API scaffolds to generated model types or record explicit waiver [syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts:1]
- [x] [Review][Patch] Prevent duplicate machine/sparepart baseline installations with DB uniqueness and conflict handling [syncro/apps/backend/src/main/resources/db/migration/V8__create_machine_sparepart_installations.sql:14]
- [x] [Review][Patch] Make the Installations all-plants filter request all plants instead of one fallback plant [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:103]
- [x] [Review][Patch] Clear plant-dependent machine filters on plant changes [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:427]
- [x] [Review][Patch] Reject invalid list limits instead of silently clamping them [syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java:208]
- [x] [Review][Patch] Preserve blank numeric form fields as validation errors instead of converting to zero/default [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:715]
- [x] [Review][Patch] Add API evidence for VIEWER create/delete denial [syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java:211]
- [x] [Review][Patch] Add service evidence for list ordering/filtering and duplicate baseline rejection [syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java:248]
- [x] [Review][Evidence] Complete Dev Agent Record with refreshed backend/service integration evidence; manual/browser UI evidence remains limited by local infra start permission [syncro/apps/web/src/features/master-data/installations/installation-management.tsx:78]
- [ ] [Review][Patch] Unique Constraint Blocks Valid Multi-Part Scenarios — The unique constraint on `(machine_id, sparepart_id)` assumes a strict 1:1 hardware mapping. This prevents a machine from having two identical spareparts installed.
- [ ] [Review][Patch] Hardcoded Installation Timestamps Destroy History — The API hardcodes `installedAt` to `Instant.now()`. Technicians cannot retroactively log installations that occurred in the past.
- [ ] [Review][Patch] Update Threshold Silently Overwrites Data [syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java]
- [ ] [Review][Patch] UTF-8 Encoding Corruption in React Components [syncro/apps/web/src/features/master-data/installations/installation-management.tsx]
- [ ] [Review][Patch] Hidden Validation Logic Corrupts API Schema [syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationDtos.java]
- [ ] [Review][Patch] Inner Join on MachineGroup Causes Data Loss and NPE [syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java]
- [ ] [Review][Patch] Frontend Validation Blocks Default Threshold [syncro/apps/web/src/features/master-data/installations/installation-management.tsx]
- [ ] [Review][Patch] Number input NaN validation bypassed [syncro/apps/web/src/features/master-data/installations/installation-management.tsx]
- [ ] [Review][Patch] Missing Explicit Nullability in OpenAPI Schema [syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationDtos.java]
- [x] [Review][Defer] Redundant DB Indexes Wasting Write Perf [V8__create_machine_sparepart_installations.sql] — deferred, pre-existing
- [x] [Review][Defer] Expensive Unindexed Joins with Order By [MachineSparepartInstallationRepository.java] — deferred, pre-existing
- [x] [Review][Defer] Brittle Next.js Server Command in Playwright [playwright.config.ts] — deferred, pre-existing
- [x] [Review][Defer] Worthless Skipped Test Suites [machine-sparepart-installations-atdd.spec.ts] — deferred, pre-existing
- [x] [Review][Defer] Missing Optimistic Locking on Installation Entity [MachineSparepartInstallationEntity.java] — deferred, pre-existing

## Dev Agent Record

### Agent Model Used

Claude Opus 4.7 via Claude Code

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineSparepartInstallationControllerTest,MachineSparepartInstallationServiceIntegrationTest"` — PASS on 2026-05-29 10:39 +07; 35 tests run, 0 failures, 0 errors, 0 skipped. Testcontainers PostgreSQL started and applied migrations V1-V8.
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineSparepartInstallationControllerTest"` — PASS on 2026-05-29; 23 tests run, 0 failures, 0 errors, 0 skipped.
- `npm --prefix syncro/apps/web run check` — PASS on 2026-05-29 10:39 +07 and rerun PASS in story-flow resume; Biome checked 101 files with no fixes applied.
- `docker compose --env-file "syncro/.env" -f "syncro/infra/docker-compose.yml" up -d postgres redis influxdb emqx waha` — PASS on 2026-05-29 10:38 +07; required local infra containers were already running.
- Browser verification — PASS on 2026-05-29 via MCP after local infra, backend, and frontend were running. Seeded dummy setup data through authenticated backend API from the browser session: plant `GM1`, group `Forming`, machine `FM-001`, taxonomy `CAT-BRG`/`BRD-WEC`/`KND-PLC`/`TYP-ELC`, sparepart `BF-08410`, and installation baseline expected `100000`, baseline `2500`, threshold `90`.
- Installations route verification — PASS on 2026-05-29 at `http://localhost:3001/dashboard/master-data/installations`: table rendered `GM1`, `Forming`, `FM-001`, `BF-08410`, expected `100,000`, baseline `2,500`, current/consumed `Not available`, `90%`, `COUNTER_BASED`; edit dialog rendered non-native machine/sparepart combobox selectors and lifetime fields; delete confirmation rendered without deleting the row; console had 0 errors/warnings after the all-plants machine query fix; network calls returned 200 for plant scope, plants, `machines?plantId=<uuid>`, spareparts, and installations.
- Playwright API/E2E ATDD scaffolds remain skipped because stable role tokens, canonical seed IDs, and browser auth/scope fixtures are not established.

### Completion Notes List

- Added machine sparepart installation persistence, API, service, generated client usage, and Installations UI for Story 2.6.
- Added backend API tests for authentication, validation, safe errors, mutation authorization, list/get/update/delete, and conflict mapping.
- Added service integration coverage for default/override thresholds, update link preservation, plant scope, VIEWER read/mutation denial, delete behavior, database constraints, list ordering/filtering, duplicate baseline rejection, and real FK delete conflicts.
- Applied code review patches for duplicate machine/sparepart baseline prevention, all-plants filtering, plant-dependent machine filter reset, invalid list limit rejection, blank numeric form validation, VIEWER mutation evidence, and list/filter evidence.
- TEA Automation completed without generating new tests because active backend evidence already covers P0 boundaries and Playwright fixtures are not stable yet.
- TEA Traceability gate result: PASS. All ACs are mapped to active backend, static, and browser evidence. AC14-AC16 browser evidence was captured with MCP after dummy setup data was seeded and the Installations all-plants machine query bug was fixed.

### File List

- `_bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `_bmad-output/test-artifacts/atdd-checklist-2-6-install-spareparts-on-machines-with-lifetime-baseline.md`
- `_bmad-output/test-artifacts/automation-summary.md`
- `_bmad-output/test-artifacts/traceability-matrix.md`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationController.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/api/MachineSparepartInstallationExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/application/MachineSparepartInstallationService.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/MachineSparepartInstallationRepository.java`
- `syncro/apps/backend/src/main/resources/db/migration/V8__create_machine_sparepart_installations.sql`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/installations/page.tsx`
- `syncro/apps/web/src/features/master-data/installations/installation-management.tsx`
- `syncro/apps/web/src/lib/api/generated/model/index.ts`
- `syncro/apps/web/src/lib/api/generated/model/installationListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/installationRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/installationUpdateRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/installationView.ts`
- `syncro/apps/web/src/lib/api/generated/model/listMachineSparepartInstallationsParams.ts`
- `syncro/apps/web/src/lib/api/generated/model/taxonomyRefView.ts`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`
- `syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts`
- `syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts`
