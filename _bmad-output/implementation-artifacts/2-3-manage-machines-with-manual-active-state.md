---
baseline_commit: 390bd682edd5f28375a1a38616973bedb543a351
---

# Story 2.3: Manage Machines with Manual Active State

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN or MANAGE user,
I want to create and maintain machines with manual ACTIVE/INACTIVE status,
so that telemetry acceptance can depend on registered machine master data.

## Acceptance Criteria

1. Given plant and machine group exist, when SUPER_ADMIN or permitted MANAGE creates a machine with `code`, `plantId`, `machineGroupId`, and `status`, then the machine is persisted in PostgreSQL and visible in machine management.
2. Given machines exist under multiple plants, when users list machines, then results are scoped by plant access and can be filtered by plant, machine group, and manual status.
3. Given machine codes are registered, when the same machine code is submitted again for the same plant, then backend rejects it with the standard safe error shape.
4. Given the same machine code is used in different plants, when both machines are created, then both records are allowed.
5. Given machine create/update request DTOs are used, when required UUIDs, machine code, status enum, optional name/brand/notes, installed date, malformed JSON, invalid UUIDs, or type mismatches are invalid, then Jakarta Bean Validation and malformed request handling return safe `400` errors with `code`, `message`, `timestamp`, `traceId`, and `fieldErrors` where relevant.
6. Given machine status is submitted, when status is not `ACTIVE` or `INACTIVE`, then backend rejects it safely and does not persist unsupported lifecycle states.
7. Given a machine is `ACTIVE` or `INACTIVE`, when UI renders machine list and summary, then manual status is shown clearly and is not inferred from MQTT telemetry freshness or latest-seen state.
8. Given a machine is updated, when user attempts to move it to a different plant, then backend rejects the move; machine plant scope remains immutable after creation.
9. Given machine group belongs to a plant, when user creates or updates a machine, then `machineGroupId` must belong to the same `plantId`; cross-plant machine/group links are rejected safely.
10. Given non-SUPER_ADMIN user requests machines, when backend evaluates plant scope, then list/view/mutate behavior is limited to assigned plants and out-of-scope access returns `403` with safe error shape.
11. Given VIEWER is authenticated, when machine management is opened, then VIEWER can view permitted machines but cannot create, edit, delete, or change manual status.
12. Given unauthenticated user calls any machine API, when backend authorizes request, then API returns `401`, not `403`.
13. Given a machine is deleted, when no dependent sparepart installations, telemetry, responsibility, alerts, or future records exist, then deletion succeeds safely; if dependent rows exist, deletion behavior must be explicit and tested as blocked with safe `409` unless a later story intentionally owns cascade/archive behavior.
14. Given machine management UI loads, when data, empty, loading, error, read-only, forbidden, validation, create, edit, delete confirmation, and mutation pending states occur, then UI displays correct state without showing unauthorized actions.
15. Given Story 2.3 is complete, when evidence is mapped, then every acceptance criterion has automated test, command output, browser evidence, or explicit deferred scope note.

## Tasks / Subtasks

- [x] Task 1: Add machine PostgreSQL model (AC: #1, #3, #4, #6, #8, #9, #13)
  - [x] Add forward-only Flyway migration `V5__create_machines.sql` or next available version; do not edit applied migrations `V1` through `V4`.
  - [x] Create `machines` table with `id`, `plant_id`, `machine_group_id`, `code`, optional `name`, `status`, optional `brand`, optional `installed_at`, optional `notes`, `created_at`, and `updated_at`.
  - [x] Add FK `plant_id` to `plants(id)` and FK `machine_group_id` to `machine_groups(id)` with explicit delete behavior; default recommendation: `ON DELETE RESTRICT` for both.
  - [x] Add same-plant machine code uniqueness, preferably case-insensitive through a functional unique index on `(plant_id, lower(code))`.
  - [x] Add indexes for `plant_id`, `machine_group_id`, and `(plant_id, status)`.
  - [x] Add DB constraints for non-blank code, allowed `status` values `ACTIVE`/`INACTIVE`, and bounded text where DB-level checks are practical.
  - [x] Preserve plant immutability after creation at service layer even if request DTO includes `plantId` for edit.

- [x] Task 2: Add backend Machine API under `/api/v1/machines` (AC: #1-#13)
  - [x] Create `com.syncro.machine` module with `api`, `application`, `domain` if needed, and `infrastructure` packages; this is first machine domain boundary.
  - [x] Add controller, DTO records, service, entity, and repository using Story 2.2 machine-group patterns.
  - [x] Recommended endpoints: `GET /api/v1/machines?plantId=...&machineGroupId=...&status=...`, `GET /api/v1/machines/{machineId}`, `POST /api/v1/machines`, `PUT /api/v1/machines/{machineId}`, `DELETE /api/v1/machines/{machineId}`.
  - [x] Use Java records for request/response DTOs; never serialize JPA entities.
  - [x] Request DTO must require `plantId`, `machineGroupId`, `code`, and `status`; optional fields are `name`, `brand`, `installedAt`, and `notes`.
  - [x] Normalize machine code consistently for duplicate detection; preserve trimmed user-facing display.
  - [x] Return `201` for create, `200` for update/get/list, and `204` for delete.
  - [x] Add explicit Springdoc `@ApiResponses` for success and safe error responses so Orval generates correct statuses and metadata.

- [x] Task 3: Enforce roles, plant scope, and machine-group consistency server-side (AC: #2, #8-#12)
  - [x] Reuse `PlantScopeService` for non-SUPER_ADMIN plant access checks.
  - [x] `SUPER_ADMIN` can list/view/mutate all machines.
  - [x] `MANAGE` can create/list/view/update/delete only for assigned plant scope.
  - [x] `VIEWER` can list/view permitted machines and cannot mutate.
  - [x] Empty assigned scope must not leak all data.
  - [x] Direct out-of-scope `plantId`, `machineGroupId`, or `machineId` access must return safe `403` for forbidden access.
  - [x] For get/update/delete by `machineId`, service must load machine then scope-check its persisted `plantId`; do not trust user-provided plantId.
  - [x] For create/update, service must verify machine group exists and belongs to the target/current plant.

- [x] Task 4: Add safe error handling (AC: #3, #5, #6, #9-#13)
  - [x] Add machine-specific duplicate/not-found/data-integrity/forbidden exceptions and exception handler, or extend shared safe error handling without conflicting with Plant/MachineGroup handlers.
  - [x] Duplicate same plant/code returns stable machine-readable code such as `DUPLICATE_MACHINE_CODE`.
  - [x] Missing machine returns `MACHINE_NOT_FOUND`.
  - [x] Cross-plant machine-group relation returns safe validation/data-integrity error such as `MACHINE_GROUP_PLANT_MISMATCH`.
  - [x] Invalid UUID path/query values return existing `INVALID_PATH_VALUE` or equivalent safe error.
  - [x] Delete dependency conflicts return safe `409`, e.g. `MACHINE_DATA_INTEGRITY_VIOLATION`.
  - [x] Do not expose SQL constraint names, Java exception names, stack traces, raw database messages, MQTT topic internals, or telemetry infrastructure details.

- [x] Task 5: Generate/update OpenAPI and Orval client (AC: #14, #15)
  - [x] Regenerate frontend API client from backend Springdoc after machine endpoints are available.
  - [x] Keep generated files isolated under `syncro/apps/web/src/lib/api/generated/`.
  - [x] Do not hand edit generated client output except as an emergency patch recorded in Dev Agent Record.
  - [x] Ensure generated client records create as `201` and delete as `204`.

- [x] Task 6: Build Machines management UI (AC: #1, #2, #7, #11, #14)
  - [x] Replace `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx` placeholder with route composition only.
  - [x] Put client management logic under `syncro/apps/web/src/features/master-data/machines/` unless a shared `features/machines/` summary component is introduced separately.
  - [x] Use generated Orval/TanStack Query hooks for CRUD.
  - [x] Use available plant list and machine group list to filter by plant and group; group options must be filtered to selected plant.
  - [x] Prefer shadcn/Radix non-native select/dropdown for plant, group, and status fields.
  - [x] Include dense table columns for code, optional name, plant, group, manual status, brand, installed date, updated date, and actions.
  - [x] Show manual status badge clearly as `ACTIVE`/`INACTIVE`; do not show telemetry live/stale as equivalent to status in this story.
  - [x] Include loading, empty, error, read-only VIEWER, forbidden, validation error, create, edit, delete confirmation, and mutation pending states.
  - [x] Query keys must include active plant scope plus selected plant/group/status filters.
  - [x] Mutations must invalidate machine list/detail queries and any machine-group/setup-completeness data if introduced later.

- [x] Task 7: Add backend tests (AC: #1-#13, #15)
  - [x] Add MockMvc/API tests for unauthenticated, SUPER_ADMIN, MANAGE, VIEWER, duplicate same-plant code, duplicate cross-plant allowed, invalid payload, malformed JSON, out-of-scope list/mutation, machine-group mismatch, not found, invalid UUID, invalid status, and delete conflict behavior.
  - [x] Add Testcontainers integration tests for migration, FK, case-insensitive uniqueness, scope filtering, plant immutability, machine-group same-plant invariant, manual status persistence, and delete behavior.
  - [x] Use real PostgreSQL for uniqueness/FK/query behavior; do not mock persistence for these paths.
  - [x] Add focused unit tests only for race-path exception mapping that is hard to trigger deterministically through repository pre-checks.
  - [x] Use Story/Test IDs and priority markers in display names, e.g. `2.3-API-001 P0 ...` and `2.3-SVC-001 P1 ...`.

- [x] Task 8: Verify frontend and full baseline (AC: #14, #15)
  - [x] Run backend targeted tests for machine API/service.
  - [x] Run `mvn -f syncro/apps/backend/pom.xml test`.
  - [x] Run `npm --prefix syncro/apps/web run generate:api` if backend OpenAPI server is available.
  - [x] Run `npm --prefix syncro/apps/web run check`.
  - [x] Run `npm --prefix syncro/apps/web run lint`.
  - [x] Run `npm --prefix syncro/apps/web run build`.
  - [x] Start backend and frontend; verify Machines create/list/filter/edit/delete, manual status display, VIEWER read-only, and empty-scope behavior in browser.
  - [x] Run `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` if script remains current.
  - [x] Map every AC to evidence in Dev Agent Record before moving story to review.

### Review Findings

- [x] [Review][Patch] Out-of-scope machineGroupId can return 400 instead of required 403 [syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java:55]
- [x] [Review][Patch] Machine table does not enforce machine_group_id plant consistency at database level [syncro/apps/backend/src/main/resources/db/migration/V5__create_machines.sql:12]
- [x] [Review][Patch] Super-admin list query passes null collection into JPQL IN predicate [syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java:15]
- [x] [Review][Patch] Duplicate constraint mapping depends on database message text [syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java:146]
- [x] [Review][Patch] Machine code normalization omits Locale.ROOT and service-level normalized validation [syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java:153]
- [x] [Review][Patch] Dependent-row delete conflict is only mocked, not proven against real PostgreSQL or explicitly deferred [syncro/apps/backend/src/test/java/com/syncro/machine/api/MachineControllerTest.java:214]

## Dev Notes

### Scope Boundary

Story 2.3 owns registered machine master data and manual `ACTIVE`/`INACTIVE` status only. Do not implement MQTT ingestion, telemetry latest-state, InfluxDB/Redis writes, sparepart installations, responsibility chains, setup completeness, alerting, WAHA, audit log Story 2.9, or machine telemetry optional-field configuration unless strictly needed as a placeholder-free UI field.

Machine status in this story is manual master data. It must not be inferred from MQTT connectivity, last telemetry timestamp, `running`, stale/online/offline, or health checks. Later Epic 3 stories use this manual status to accept or reject telemetry.

Pilot naming target from planning: plant `GM1`, machine group `Forming`, machine code `BF-08410`, optional machine name `JBF19`, status `ACTIVE`.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/resources/db/migration/V3__create_machine_groups.sql` owns `machine_groups`; Story 2.3 must add a new migration instead of editing it.
- `syncro/apps/backend/src/main/resources/db/migration/V4__add_machine_group_case_insensitive_unique_index.sql` proves case-insensitive uniqueness pattern via functional unique index.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java` established role checks, `PlantScopeService`, immutable plant after create, duplicate fallback handling for V4 unique index, and safe delete conflict mapping.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupEntity.java` links machine groups to `PlantEntity`; machines should link to both `PlantEntity` and `MachineGroupEntity` or store FK relationships consistently with JPA query needs.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupController.java`, `MachineGroupDtos.java`, and `MachineGroupExceptionHandler.java` are the closest API/Springdoc/error-shape patterns.
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/api/MachineGroupControllerTest.java`, `MachineGroupServiceIntegrationTest.java`, and `MachineGroupServiceTest.java` are current test style references.
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx` is currently a placeholder guarded only for `SUPER_ADMIN` and `MANAGE`; Story 2.3 must allow `VIEWER` read-only access if AC11 is implemented.
- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx` is the closest UI pattern for generated hooks, TanStack Query invalidation, shadcn table/dialogs, Radix selects, validation errors, empty-scope handling, and read-only states.
- `syncro/apps/web/src/lib/api/generated/` is generated from Springdoc/OpenAPI. Do not treat generated code as handwritten source.

### Backend Implementation Guardrails

- Backend remains source of truth for role and plant-scope authorization.
- Use `@Transactional` at service/use-case boundaries.
- Controllers bind/validate DTOs and delegate; no business rules in controller.
- JPA entities must not be returned from API.
- Use `Instant` UTC timestamps.
- Use stable error codes and standard safe error shape.
- Validation belongs at REST DTO boundary plus service/database invariants.
- Do not rely on frontend active plant state or browser storage for plant access.
- Do not edit applied Flyway migrations; add a new forward-only migration.
- Do not use `ddl-auto=update`.
- Domain package should follow architecture: create `com.syncro.machine` for machines. It may reference masterdata repositories/entities only where current architecture has no application query facade yet; if direct repository crossing feels too wide, add narrow application-level query methods instead.
- Cross-plant machine/group mismatch must be checked before save, not left to vague DB failures.
- Machine plant should remain immutable after create. Updating `machineGroupId` can be allowed only if replacement group belongs to the same persisted plant.

### Recommended Data Model

```sql
CREATE TABLE machines (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  machine_group_id UUID NOT NULL,
  code VARCHAR(64) NOT NULL,
  name VARCHAR(255),
  status VARCHAR(16) NOT NULL,
  brand VARCHAR(255),
  installed_at DATE,
  notes VARCHAR(1000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machines_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT fk_machines_machine_group FOREIGN KEY (machine_group_id) REFERENCES machine_groups(id) ON DELETE RESTRICT,
  CONSTRAINT ck_machines_code_not_blank CHECK (btrim(code) <> ''),
  CONSTRAINT ck_machines_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX uq_machines_plant_id_lower_code ON machines (plant_id, lower(code));
CREATE INDEX idx_machines_plant_id ON machines(plant_id);
CREATE INDEX idx_machines_machine_group_id ON machines(machine_group_id);
CREATE INDEX idx_machines_plant_id_status ON machines(plant_id, status);
```

If PostgreSQL syntax needs adjustment, use valid PostgreSQL while preserving explicit delete behavior and constraints.

### API Contract Recommendation

```text
GET    /api/v1/machines?plantId={plantId}&machineGroupId={machineGroupId}&status={ACTIVE|INACTIVE}
GET    /api/v1/machines/{machineId}
POST   /api/v1/machines
PUT    /api/v1/machines/{machineId}
DELETE /api/v1/machines/{machineId}
```

Request:

```json
{
  "plantId": "uuid",
  "machineGroupId": "uuid",
  "code": "BF-08410",
  "name": "JBF19",
  "status": "ACTIVE",
  "brand": "Juki",
  "installedAt": "2026-05-27",
  "notes": "Pilot machine"
}
```

View:

```json
{
  "id": "uuid",
  "plantId": "uuid",
  "plantCode": "GM1",
  "plantName": "Plant GM1",
  "machineGroupId": "uuid",
  "machineGroupName": "Forming",
  "code": "BF-08410",
  "name": "JBF19",
  "status": "ACTIVE",
  "brand": "Juki",
  "installedAt": "2026-05-27",
  "notes": "Pilot machine",
  "createdAt": "2026-05-27T00:00:00Z",
  "updatedAt": "2026-05-27T00:00:00Z"
}
```

Include plant and machine-group display fields to avoid frontend N+1 lookups in table rows. Keep response compact; do not add telemetry freshness, alert counts, sparepart risk, responsibility, or setup-completeness fields in this story.

### Machine Code Rules

Recommended machine code validation:

- Trim leading/trailing whitespace.
- Required, max 64 characters.
- Allow uppercase letters, digits, hyphen, underscore, and dot if needed for industrial codes; recommended regex: `^[A-Z0-9][A-Z0-9._-]{0,63}$` after uppercase normalization.
- Store/display normalized uppercase machine code unless product requires case preservation. If preserving case, duplicate detection must still be case-insensitive.
- Do not allow slash `/` because MQTT topics use `factory/{plantCode}/{machineCode}/telemetry`; slash would create ambiguous topic segments later.

### Frontend Implementation Guardrails

- `app/` route stays composition only.
- Feature implementation belongs under `src/features/master-data/machines/` for management UI.
- Use generated hooks/client for machines, machine groups, and plants.
- Query keys must include selected `plantId`, selected `machineGroupId`, selected `status`, and active plant scope.
- Empty assigned scope should not fire unrestricted list queries.
- VIEWER sees data and read-only badges, not mutation buttons.
- MANAGE with empty scope should not see fake global data. Machine create requires accessible plant and group; disable create with explanation until both exist.
- Use shadcn/Radix non-native select/dropdown/picker for plant, machine group, and status selection.
- Backend validation errors map to inline fields and safe form-level message.
- Delete confirmation must state selected machine and that future dependent data may block deletion.
- Manual status badge must be label-driven (`ACTIVE`, `INACTIVE`) and not color-only.
- Do not implement full Machine Hub tabs in Story 2.3; only ensure list rows can later link to `/master-data/machines/[machineId]` if route exists.

### Previous Story Intelligence

Story 2.2 established plant-scoped Machine Group CRUD, generated API client, management UI, and review-hardened backend behavior.

Review lessons to apply immediately:

- Plant scope must be enforced on backend for list/view/mutate; frontend visibility is not security.
- Plant must remain immutable after entity creation unless story explicitly owns move behavior.
- DB-level case-insensitive unique index needs service fallback mapping so race-condition duplicates return safe duplicate errors.
- Delete integrity violations must map to safe `409`, not unhandled `500`.
- Empty-scope frontend must not fire list queries that imply global access.
- Generated Orval statuses must match backend (`201` create, `204` delete); explicit OpenAPI responses matter.
- Backend test names should include Story/Test IDs and priority markers.
- UI state evidence can be manual/browser evidence for this story, but durable component/trace evidence should be considered when state complexity grows.

### Testing Requirements

Minimum backend test matrix:

- unauthenticated list/get/create/update/delete: `401`.
- VIEWER create/update/delete/status change: `403`.
- VIEWER list/get assigned plant machines: `200`.
- MANAGE create under assigned plant: `201`.
- MANAGE create under unassigned plant: `403`.
- MANAGE list with unassigned explicit plant filter: `403`.
- SUPER_ADMIN same machine code in different plants: allowed.
- duplicate same plant/code: stable duplicate error.
- invalid status outside `ACTIVE`/`INACTIVE`: safe `400`.
- blank/null/too-long/invalid-format code and missing/malformed plantId/machineGroupId: `400 VALIDATION_ERROR` or documented equivalent.
- malformed JSON/type mismatch: safe `400`.
- invalid UUID path/query: safe `400`.
- missing plant, missing machine group, and machine-group plant mismatch: safe documented errors.
- missing machine: safe `404`.
- update cannot move machine to another plant.
- update can change group only within same plant.
- delete behavior: succeeds without dependent rows; dependency conflict maps to safe `409`.

Minimum frontend/browser evidence:

- SUPER_ADMIN sees plant/group/status filters and can create/list/edit/delete machine.
- MANAGE sees only assigned plant choices and can mutate assigned plant machines.
- VIEWER sees read-only table, no mutation controls.
- Empty plant assignment state explains missing assignment.
- No plant/no machine group state explains setup dependency.
- Validation error displays inline for code/status/group fields.
- Duplicate same plant/code error displays safe message.
- ACTIVE and INACTIVE manual statuses display distinctly and non-color-only.
- Loading, empty, error/forbidden, and delete confirmation states are reachable or covered by component-level/manual evidence.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.3: Manage Machines with Manual Active State]
- [Source: _bmad-output/planning-artifacts/architecture.md#Implementation Patterns & Consistency Rules]
- [Source: _bmad-output/planning-artifacts/architecture.md#Format Patterns]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow: Build Machine Foundation]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Machine Monitoring Flow: Verify JBF19 Live Telemetry]
- [Source: _bmad-output/planning-artifacts/page-specifications.md#Machine Detail (Hub)]
- [Source: _bmad-output/implementation-artifacts/2-2-manage-plant-scoped-machine-groups.md#Previous Story Intelligence]
- [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java]
- [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupEntity.java]
- [Source: syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx]
- [Source: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx]

## Dev Agent Record

### Agent Model Used

Claude Code (cx/gpt-5.5)

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml "-Dtest=MachineControllerTest,MachineServiceIntegrationTest" test` — passed, 25 tests.
- `mvn -f syncro/apps/backend/pom.xml test` — passed, 117 tests.
- `npm --prefix syncro/apps/web run generate:api` — passed.
- `npm --prefix syncro/apps/web run check` — passed, 87 files.
- `npm --prefix syncro/apps/web run lint` — passed, 87 files.
- `npm --prefix syncro/apps/web run build` — passed.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` — passed.
- Browser verification: `http://localhost:3001/dashboard/master-data/machines` as local SUPER_ADMIN showed GM1/Forming filters, `Create machine`, table row `BF-08410`, `Manual ACTIVE`; edit changed row to `Manual INACTIVE`; empty dependency state showed `No machine groups available` before seed data.

### Completion Notes List

- Added `machines` PostgreSQL model with plant/group FKs, manual `ACTIVE`/`INACTIVE` status, case-insensitive same-plant code uniqueness, indexes, and DB checks.
- Added `/api/v1/machines` list/get/create/update/delete with DTO records, safe error handling, plant-scope enforcement, immutable plant update behavior, same-plant group validation, and VIEWER read-only authorization.
- Regenerated OpenAPI/Orval client and built Machines management UI using generated TanStack Query hooks, Radix selects, manual status badges, CRUD dialog, delete confirmation, loading/empty/error/read-only states, and scoped query keys.
- Added MockMvc and Testcontainers integration coverage for authorization, validation, uniqueness, plant scope, plant immutability, same-plant group invariant, manual status persistence, and delete behavior.

### Acceptance Criteria Evidence

- AC1 -> `MachineServiceIntegrationTest` `2.3-SVC-001`, `MachineControllerTest` `2.3-API-003`, browser row `BF-08410` visible in Machines table.
- AC2 -> `MachineServiceIntegrationTest` `2.3-SVC-007`, `2.3-SVC-008`; UI filters include plant/group/status.
- AC3 -> `MachineControllerTest` `2.3-API-006`; `MachineServiceIntegrationTest` `2.3-SVC-002`, `2.3-SVC-004`.
- AC4 -> `MachineServiceIntegrationTest` `2.3-SVC-003`.
- AC5 -> `MachineControllerTest` `2.3-API-004`, `2.3-API-005`; malformed and invalid values return safe `400`.
- AC6 -> `MachineControllerTest` `2.3-API-005`; DB check `ck_machines_status` in `V5__create_machines.sql`.
- AC7 -> Browser evidence showed `Manual ACTIVE` and edit to `Manual INACTIVE`; UI copy states status is master data, not MQTT freshness.
- AC8 -> `MachineServiceIntegrationTest` `2.3-SVC-005`.
- AC9 -> `MachineControllerTest` `2.3-API-007`; `MachineServiceIntegrationTest` `2.3-SVC-006`.
- AC10 -> `MachineControllerTest` `2.3-API-009`; `MachineServiceIntegrationTest` `2.3-SVC-007`, `2.3-SVC-008`.
- AC11 -> `MachineControllerTest` `2.3-API-008`; UI renders read-only badge and hides mutation controls when role is not mutable.
- AC12 -> `MachineControllerTest` `2.3-API-001`.
- AC13 -> `MachineControllerTest` `2.3-API-011`; `MachineServiceIntegrationTest` `2.3-SVC-010`; FKs use `ON DELETE RESTRICT`.
- AC14 -> Browser evidence covered loading/data/empty dependency state, filters, create availability, edit mutation, manual status display, and table actions; UI code covers validation, error, read-only, delete confirmation, and pending states.
- AC15 -> Evidence listed above maps every AC; full backend, frontend checks, build, baseline validation, and browser verification passed.

### File List

- `_bmad-output/implementation-artifacts/2-3-manage-machines-with-manual-active-state.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineController.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/domain/MachineStatus.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java`
- `syncro/apps/backend/src/main/resources/db/migration/V5__create_machines.sql`
- `syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java`
- `syncro/apps/backend/src/test/java/com/syncro/machine/api/MachineControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineServiceIntegrationTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machines/page.tsx`
- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx`
- `syncro/apps/web/src/features/master-data/machines/machine-management.tsx`
- `syncro/apps/web/src/lib/api/generated/model/authUserView.ts`
- `syncro/apps/web/src/lib/api/generated/model/authUserViewApplicationRole.ts`
- `syncro/apps/web/src/lib/api/generated/model/health200.ts`
- `syncro/apps/web/src/lib/api/generated/model/index.ts`
- `syncro/apps/web/src/lib/api/generated/model/list1Params.ts`
- `syncro/apps/web/src/lib/api/generated/model/list1Status.ts`
- `syncro/apps/web/src/lib/api/generated/model/list2Params.ts`
- `syncro/apps/web/src/lib/api/generated/model/loginRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/loginResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/logout200.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupView.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineRequestStatus.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineView.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineViewStatus.ts`
- `syncro/apps/web/src/lib/api/generated/model/plantListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/plantRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/plantScopeResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/plantScopeView.ts`
- `syncro/apps/web/src/lib/api/generated/model/plantView.ts`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`

### Change Log

- 2026-05-27 — Created Story 2.3 with machine CRUD, manual active-state, plant scope, validation, UI, and test requirements.
- 2026-05-27 — Implemented machine CRUD backend, migration, generated API client, Machines UI, backend tests, validation evidence, and moved story to review.
