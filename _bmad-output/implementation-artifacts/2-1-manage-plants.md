---
baseline_commit: f5138c755cacbef317fef9c0bf6a2d4b2c9c648b
---

# Story 2.1: Manage Plants

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN or MANAGE user,
I want to create, view, edit, and delete plants,
So that machine data can be organized by plant.

## Acceptance Criteria

1. Given authenticated user has permitted role, when user creates plant with valid data, then plant is persisted in PostgreSQL.
2. Given a plant is created or updated, when the plant management list is loaded, then the plant appears with current code/name metadata and timestamps where useful.
3. Given duplicate or invalid plant data is submitted, when backend validates the request, then the API returns the standard safe error shape with `code`, `message`, `timestamp`, `traceId`, and `fieldErrors` where relevant.
4. Given plant create/update request DTOs are used, when blank, null, too-long, badly formatted, malformed JSON, or type-mismatched payloads are sent, then Jakarta Bean Validation and malformed JSON handling return safe `400` errors without Java/SQL detail.
5. Given VIEWER is authenticated, when plant management is opened, then VIEWER can view permitted plant list but cannot create, edit, or delete.
6. Given VIEWER calls create, update, or delete plant APIs directly, when backend authorizes the request, then API returns `403` with standard safe error shape.
7. Given unauthenticated user calls any plant API, when backend authorizes the request, then API returns `401`, not `403`.
8. Given MANAGE or VIEWER has assigned plant scope, when plant list is requested, then backend returns only assigned plants; SUPER_ADMIN can list all plants.
9. Given MANAGE requests update or delete for plant outside assigned scope, when backend evaluates the target plant, then API returns `403` with safe error shape.
10. Given a plant is deleted, when related assignments exist, then deletion behavior is explicit and safe: either blocked with a domain error or cascades only where existing schema intentionally permits it; tests prove chosen behavior.
11. Given plant table UI loads, when data, empty, loading, error, read-only, or forbidden states occur, then UI displays correct state without showing unauthorized actions.
12. Given Story 2.1 is complete, when evidence is mapped, then every acceptance criterion has automated test, command output, browser evidence, or explicit deferred scope note.

## Tasks / Subtasks

- [x] Task 1: Extend backend plant master model safely (AC: #1, #2, #8, #10)
  - [x] Reuse existing `plants` table from Story 1.7; do not create a parallel plant table.
  - [x] Add forward-only Flyway migration only if Plant CRUD needs additional columns beyond `id`, `code`, `name`, `created_at`, `updated_at`.
  - [x] Do not edit `V1__create_auth_baseline.sql` or `V2__create_plant_scope_foundation.sql`.
  - [x] Keep PostgreSQL naming rules: plural `snake_case` tables, `idx_*` indexes, `uq_*` unique constraints, explicit checks for non-blank bounded values.
  - [x] Make delete behavior deliberate and tested against `auth_user_plant_assignments`.

- [x] Task 2: Add Plant CRUD API under `/api/v1/plants` (AC: #1-#10)
  - [x] Add controller in `com.syncro.masterdata.api` or agreed master-data module; avoid growing auth controller with CRUD ownership.
  - [x] Add application service for create/list/get/update/delete and permission decisions.
  - [x] Use Java record DTOs with suffixes `Request`, `Response`, or `View`; never serialize JPA entities.
  - [x] Request DTOs must use `@Valid`, explicit `@NotBlank`, `@Size`, and plant-code format rules.
  - [x] Recommended endpoints: `GET /api/v1/plants`, `GET /api/v1/plants/{plantId}`, `POST /api/v1/plants`, `PUT /api/v1/plants/{plantId}`, `DELETE /api/v1/plants/{plantId}`.
  - [x] Return safe standard errors for validation, malformed JSON, duplicate code, forbidden role, out-of-scope target, and not found.
  - [x] Keep frontend-selected plant scope as query preference only; backend must derive and enforce effective scope.

- [x] Task 3: Add Springdoc/OpenAPI baseline inside Story 2.1 (AC: #12)
  - [x] Add Springdoc dependency/config to backend with minimal plant-focused OpenAPI generation.
  - [x] Ensure generated OpenAPI includes Plant CRUD paths, request DTOs, response DTOs, validation metadata where supported, and error responses.
  - [x] Keep OpenAPI config environment-safe and local/dev friendly; do not expose secrets or internal stack traces.
  - [x] Add command or script evidence for generating/fetching the OpenAPI spec.

- [x] Task 4: Add Orval + TanStack Query baseline in frontend (AC: #2, #5, #8, #11, #12)
  - [x] Add TanStack Query dependency/provider if absent; keep Zustand only for shell/local state.
  - [x] Add Orval config that consumes backend OpenAPI and generates TypeScript client functions plus TanStack Query hooks.
  - [x] Keep generated files isolated, for example under `syncro/apps/web/src/lib/api/generated/`; do not hand-edit generated output.
  - [x] Keep handwritten wrappers/components separate from generated client.
  - [x] Do not duplicate Plant API types by hand after generated types exist.
  - [x] Define plant-scope-aware query key convention; include active plant scope for plant-scoped data.
  - [x] Manual fetch remains acceptable for auth/session shell endpoints only, including existing `syncro-api.ts` auth and plant-scope calls.

- [x] Task 5: Build Plant management UI (AC: #2, #5, #11)
  - [x] Replace placeholder in `syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx` with route composition only.
  - [x] Put client logic, table, forms, dialogs, and mutations under `features/master-data` or a focused plant feature folder.
  - [x] Use generated TanStack Query hooks/client for Plant CRUD.
  - [x] Use existing shadcn/Radix/Tailwind patterns; no new UI kit; prefer non-native selects/dropdowns/pickers.
  - [x] Support loading, empty, error, read-only VIEWER, forbidden, create, edit, delete confirmation, mutation pending, success, and backend validation error states.
  - [x] Preserve app shell, role-aware navigation/search, and `PlantScopeSelector` behavior from Stories 1.6 and 1.7.

- [x] Task 6: Add backend tests (AC: #1, #3, #4, #6-#10, #12)
  - [x] Add API/MockMvc tests for SUPER_ADMIN, MANAGE, VIEWER, unauthenticated, duplicate, invalid payload, malformed JSON, forbidden role, and out-of-scope target.
  - [x] Add service tests for scope and role decisions where useful.
  - [x] Use Testcontainers/real PostgreSQL for migration, uniqueness, delete behavior, and repository proof; do not mock persistence for schema/constraint behavior.
  - [x] Assert standard error fields: `code`, `message`, `timestamp`, `traceId`, and `fieldErrors` for validation failures.

- [x] Task 7: Add frontend verification and browser evidence (AC: #2, #5, #11, #12)
  - [x] Run `npm --prefix syncro/apps/web run check`.
  - [x] Run `npm --prefix syncro/apps/web run build`.
  - [x] Run `npm --prefix syncro/apps/web run lint`.
  - [x] Start backend and frontend locally; verify Plant management create/list/edit/delete and VIEWER read-only behavior in browser.
  - [x] Verify loading, empty, error/forbidden, validation error, and delete confirmation states in browser or component-level equivalent if browser path is blocked.

- [x] Task 8: Run full baseline validation and map evidence (AC: #12)
  - [x] Run `mvn -f syncro/apps/backend/pom.xml test`.
  - [x] Run `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1`.
  - [x] Record exact commands, test names, browser URLs, and acceptance-criteria evidence in Dev Agent Record before moving to review.

## Dev Notes

### Scope Boundary

Story 2.1 owns Plant CRUD plus minimal API contract generation/server-state baseline for Epic 2. It must not implement machine groups, machines, sparepart taxonomy, spareparts, installations, responsibilities, audit log UI, telemetry, alerts, WAHA, or full ABAC/job-scope rules.

Springdoc/OpenAPI + Orval + TanStack Query belongs in this story by user decision from Epic 1 retrospective. Do not split into separate prep story.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql` already creates `plants(id, code, name, created_at, updated_at)` and `auth_user_plant_assignments`.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantEntity.java` currently maps minimal plant identity under `auth` because Story 1.7 introduced plant scope foundation.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantRepository.java` currently has only `JpaRepository<PlantEntity, UUID>`.
- `syncro/apps/backend/src/main/java/com/syncro/auth/application/PlantScopeService.java` is existing source of truth for effective plant scope and out-of-scope decisions.
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthController.java` exposes `/api/v1/auth/plant-scope`; keep this session/scope endpoint compatible.
- `syncro/apps/backend/src/main/java/com/syncro/auth/api/AuthExceptionHandler.java` currently handles validation and plant access errors. Story 2.1 should avoid duplicating incompatible error shapes; shared/common error handling may be extracted if needed.
- `syncro/apps/web/src/lib/api/syncro-api.ts` owns current handwritten auth/session and plant-scope API calls. After Orval exists, domain CRUD should use generated client/hooks, while auth/session can remain handwritten.
- `syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx` is currently a placeholder guarded for `SUPER_ADMIN` and `MANAGE`; Story 2.1 must allow VIEWER read-only access if route guard/navigation currently blocks it.

### Backend Implementation Guardrails

- Backend remains source of truth for role and plant-scope authorization.
- `SUPER_ADMIN` can create/view/update/delete all plants.
- `MANAGE` can create plants and view/update/delete plants only according to current product decision and effective scope. If creating a new plant should also create assignment or be SUPER_ADMIN-only, record and test the chosen rule; do not leave ambiguous.
- `VIEWER` can list/view permitted plants and cannot mutate.
- Use service-level permission decisions, not frontend-only action hiding.
- Keep application roles separate from job scopes; do not introduce `TECHNICIAN`, `STAFF`, `LEADER`, `SPV`, or `MANAGER` as app authorities.
- Use `/api/v1` REST JSON with camelCase fields and ISO-8601 UTC timestamps.
- Use safe standard error response. No SQL constraint names, Java exception names, stack traces, secrets, auth headers, cookies, or connection strings in response or logs.
- Prefer module ownership under `masterdata` for Plant CRUD. If existing `PlantEntity` remains under `auth` for Story 2.1 to minimize movement, document boundary and avoid creating duplicate entity/table.
- Do not rely on JWT plant IDs or browser state for effective scope.

### Frontend Implementation Guardrails

- `app/` route files remain composition only.
- Generated Orval code must be isolated from handwritten UI. Do not hand edit generated files.
- Query keys must include active plant scope for plant-scoped data. For all-plants/unrestricted scope, use a stable value such as `all`; for no assignment, avoid firing queries that imply access.
- Mutations must invalidate affected plant list/detail query keys explicitly.
- Use backend-provided authorization and generated hooks; frontend hiding is not security.
- Unknown handwritten API responses must be narrowed. Generated client types can be trusted only as generated from current OpenAPI.
- Use existing shadcn/Radix components for forms, dialogs, dropdowns, toasts, and confirmation UI.
- Do not make UI imply machine/telemetry readiness; this story is plant master data only.

### Data Model Notes

Existing schema from Story 1.7:

```sql
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
```

For CRUD, expected request constraints:

- `code`: required, trimmed, uppercase or normalized by backend decision, bounded length, safe topic-segment/plant-code format.
- `name`: required, trimmed, bounded length.
- Optional description/location fields only if needed by AC and migration; do not add extra fields for hypothetical future workflows.

Deletion must consider `auth_user_plant_assignments` existing FK with `ON DELETE CASCADE`. If cascade remains allowed, tests must prove assignment cleanup. If delete is blocked for assigned plants, add service-level check and test domain error.

### API Contract Baseline Requirements

Add minimal Springdoc/OpenAPI setup in backend and Orval setup in frontend as part of this story.

Generated-client boundary:

```text
syncro/apps/web/src/lib/api/generated/      # generated only
syncro/apps/web/src/lib/api/                # handwritten auth/session wrappers may remain
syncro/apps/web/src/features/master-data/   # plant UI, hooks wrappers, forms, tables
```

Plant CRUD frontend must consume generated client/query hooks, not ad-hoc fetch or duplicate handwritten Plant API types.

### Testing Requirements

Minimum backend negative-path matrix:

- unauthenticated list/create/update/delete: `401`
- VIEWER create/update/delete: `403`
- MANAGE out-of-scope update/delete: `403`
- duplicate code: safe `400` or conflict-style standard error as chosen consistently
- blank/null/too-long/bad code: `400 VALIDATION_ERROR` with `fieldErrors`
- malformed JSON/type mismatch: safe `400`
- missing plant ID or nonexistent plant ID: safe standard not-found/error behavior
- delete assigned plant behavior: blocked or cascaded, with Testcontainers proof

Minimum frontend/browser evidence:

- SUPER_ADMIN plant list and create/edit/delete path.
- MANAGE permitted plant list and mutation path according to backend rule.
- VIEWER read-only plant list with no mutation actions.
- Empty list state.
- Validation error display.
- Forbidden/error state.
- Loading state does not flash unrestricted actions before authorization resolves.

### Previous Story Intelligence

Story 1.7 delivered plant scope foundation but explicitly deferred real domain endpoint adoption. Story 2.1 is first real domain CRUD endpoint and must adopt the service/guard for actual Plant CRUD authorization.

Review lessons from Story 1.7 to avoid repeats:

- Do not report load/auth failures as valid empty authorization.
- Do not render unrestricted content while scope is loading.
- Do not let `SUPER_ADMIN` authorization for resource identity skip existence checks.
- Do not overclaim placeholder or service/guard tests as real endpoint enforcement.
- Map each AC to direct evidence before moving story to review.

Epic 1 retrospective lessons to apply:

- Security and validation negative paths caused most review churn.
- Evidence must distinguish reusable foundation tests from production endpoint coverage.
- Standard error shape needs tests.
- Testcontainers/real PostgreSQL are required for migration, uniqueness, FK, and repository behavior.

### Project Structure Notes

Expected changed areas:

- Backend:
  - `syncro/apps/backend/pom.xml`
  - `syncro/apps/backend/src/main/resources/db/migration/V3__*.sql` only if schema extension needed
  - `syncro/apps/backend/src/main/java/com/syncro/masterdata/**`
  - possibly existing `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantEntity.java` / `PlantRepository.java` if reused or moved carefully
  - `syncro/apps/backend/src/test/java/com/syncro/masterdata/**`
- Frontend:
  - `syncro/apps/web/package.json`
  - Orval config file under `syncro/apps/web/`
  - generated files under `syncro/apps/web/src/lib/api/generated/`
  - `syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx`
  - `syncro/apps/web/src/features/master-data/**` or focused plant feature folder
  - TanStack Query provider location in app shell/root provider
- Artifacts:
  - `_bmad-output/implementation-artifacts/2-1-manage-plants.md`
  - `_bmad-output/implementation-artifacts/sprint-status.yaml`

No worktree or branch unless user explicitly asks.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.1: Manage Plants]
- [Source: _bmad-output/planning-artifacts/prds/prd-Syncro-2026-05-22/prd.md#FR-006]
- [Source: _bmad-output/planning-artifacts/architecture.md#Authentication & Security]
- [Source: _bmad-output/planning-artifacts/architecture.md#API & Communication Patterns]
- [Source: _bmad-output/planning-artifacts/architecture.md#Frontend Architecture]
- [Source: _bmad-output/planning-artifacts/architecture.md#Implementation Patterns & Consistency Rules]
- [Source: _bmad-output/implementation-artifacts/epic-1-retro-2026-05-27.md#Decisions Captured]
- [Source: _bmad-output/implementation-artifacts/1-7-implement-plant-scoped-data-access.md#Review Findings]
- [Source: syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql]
- [Source: syncro/apps/backend/src/main/java/com/syncro/auth/application/PlantScopeService.java]
- [Source: syncro/apps/web/src/lib/api/syncro-api.ts]
- [Source: syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx]

## Dev Agent Record

### Agent Model Used

Claude Code (cx/gpt-5.5)

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml "-Dtest=PlantControllerTest,PlantServiceIntegrationTest" test` — PASS, 15 tests.
- `mvn -f syncro/apps/backend/pom.xml test` — PASS, 45 tests.
- `npm --prefix syncro/apps/web run generate:api` — PASS, Orval generated client from `http://localhost:8080/v3/api-docs`.
- `npm --prefix syncro/apps/web run check` — PASS, 85 files.
- `npm --prefix syncro/apps/web run lint` — PASS, 85 files.
- `npm --prefix syncro/apps/web run build` — PASS.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` — PASS.
- Browser evidence at `http://localhost:3001/dashboard/master-data/plants`: SUPER_ADMIN empty, create, duplicate validation, edit, delete; VIEWER read-only state.

### Completion Notes List

- Reused existing Story 1.7 `plants` table and `auth_user_plant_assignments` cascade behavior; no schema migration added.
- Added Plant CRUD backend service/controller/DTO/error handling under `masterdata` while reusing existing plant-scope enforcement.
- Added Springdoc/OpenAPI backend baseline and Orval/TanStack Query frontend baseline.
- Added Plant management UI with generated Orval hooks, plant-scope-aware query key, read-only VIEWER state, validation message display, and delete confirmation.
- Added backend MockMvc and Testcontainers coverage for roles, validation, duplicate code, scope filtering, out-of-scope mutation, and assignment cascade delete.

### Acceptance Criteria Evidence

1. AC1 — `PlantServiceIntegrationTest.manageCreatesNormalizedPlant`; browser SUPER_ADMIN create path.
2. AC2 — `PlantControllerTest.superAdminCanListPlants`; browser list after create/edit.
3. AC3 — `PlantControllerTest.invalidPlantRequestReturnsFieldErrors`, `duplicatePlantCodeReturnsSafeValidationError`; browser duplicate validation message.
4. AC4 — `PlantControllerTest.invalidPlantRequestReturnsFieldErrors`, `malformedJsonReturnsSafeError`.
5. AC5 — Plant UI route allows VIEWER and browser shows `Read-only` without mutation actions.
6. AC6 — `PlantControllerTest.viewerCannotCreatePlant`, `viewerCannotDeletePlant`.
7. AC7 — `PlantControllerTest.listPlantsRequiresAuthentication`.
8. AC8 — `PlantServiceIntegrationTest.viewerCanListAssignedPlantsOnly`; SUPER_ADMIN list path covered in controller test and browser.
9. AC9 — `PlantServiceIntegrationTest.manageCannotUpdateOutOfScopePlant`, `PlantControllerTest.outOfScopeUpdateReturnsSafeForbiddenError`.
10. AC10 — `PlantServiceIntegrationTest.deleteCascadesExistingPlantAssignments` proves existing FK cascade behavior.
11. AC11 — Browser evidence covers empty, data, validation error, read-only, create, edit, delete confirmation; loading/error states implemented in `PlantManagement`.
12. AC12 — Full backend/frontend/baseline validations plus OpenAPI fetch and Orval generation passed.

### File List

- `_bmad-output/implementation-artifacts/2-1-manage-plants.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/backend/pom.xml`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantRepository.java`
- `syncro/apps/backend/src/main/java/com/syncro/config/OpenApiConfig.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantController.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/application/PlantService.java`
- `syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java`
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/api/PlantControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/application/PlantServiceIntegrationTest.java`
- `syncro/apps/web/biome.json`
- `syncro/apps/web/orval.config.ts`
- `syncro/apps/web/package.json`
- `syncro/apps/web/package-lock.json`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/plants/page.tsx`
- `syncro/apps/web/src/app/layout.tsx`
- `syncro/apps/web/src/features/master-data/plants/plant-management.tsx`
- `syncro/apps/web/src/lib/api/generated/model/index.ts`
- `syncro/apps/web/src/lib/api/generated/model/*.ts`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`
- `syncro/apps/web/src/lib/api/orval-mutator.ts`
- `syncro/apps/web/src/providers/query-provider.tsx`

### Change Log

- 2026-05-27 — Created Story 2.1 with Plant CRUD plus Springdoc/OpenAPI, Orval, and TanStack Query baseline requirements.
- 2026-05-27 — Implemented Plant CRUD backend, OpenAPI contract, generated frontend client, TanStack Query provider, Plant UI, tests, and evidence.

## Story Completion Status

Story complete and ready for review.
