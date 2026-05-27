---
baseline_commit: 8f742fe49dbf61a39595c4e4e651408f89477d25
---

# Story 2.2: Manage Plant-Scoped Machine Groups

Status: review

## Story

As a SUPER_ADMIN or MANAGE user,
I want to manage machine groups within a plant,
So that process lines like Forming can be represented per plant.

## Acceptance Criteria

1. Given at least one plant exists, when SUPER_ADMIN or permitted MANAGE creates a machine group under a plant, then the machine group is persisted in PostgreSQL and linked to that plant.
2. Given machine groups exist under multiple plants, when the same machine group name is used in different plants, then both records are allowed.
3. Given a machine group already exists in one plant, when the same name is submitted again for that same plant, then backend rejects it with the standard safe error shape.
4. Given machine group create/update request DTOs are used, when required `plantId`, name, or bounded text fields are blank, null, malformed, too long, or type-mismatched, then Jakarta Bean Validation and malformed JSON handling return safe `400` errors with `code`, `message`, `timestamp`, `traceId`, and `fieldErrors` where relevant.
5. Given machine groups exist, when the list endpoint is requested with a plant filter, then only machine groups for that plant are returned.
6. Given non-SUPER_ADMIN user requests machine groups, when backend evaluates plant scope, then list/view/mutate behavior is limited to assigned plants and out-of-scope access returns `403` with safe error shape.
7. Given VIEWER is authenticated, when machine group management is opened, then VIEWER can view permitted machine groups but cannot create, edit, or delete.
8. Given VIEWER calls create, update, or delete machine group APIs directly, when backend authorizes the request, then API returns `403` with standard safe error shape.
9. Given unauthenticated user calls any machine group API, when backend authorizes the request, then API returns `401`, not `403`.
10. Given a machine group is deleted, when no dependent machines exist yet, then deletion succeeds safely; if future dependent machine rows exist, deletion behavior must be explicit and tested as blocked or cascaded by schema/service rule.
11. Given machine group table UI loads, when data, empty, loading, error, read-only, forbidden, validation, create, edit, delete confirmation, and mutation pending states occur, then UI displays correct state without showing unauthorized actions.
12. Given Story 2.2 is complete, when evidence is mapped, then every acceptance criterion has automated test, command output, browser evidence, or explicit deferred scope note.

## Tasks / Subtasks

- [x] Task 1: Add machine group PostgreSQL model (AC: #1, #2, #3, #10)
  - [x] Add forward-only Flyway migration `V3__create_machine_groups.sql` or next available version; do not edit `V1__create_auth_baseline.sql` or `V2__create_plant_scope_foundation.sql`.
  - [x] Create `machine_groups` table with `id`, `plant_id`, `name`, `created_at`, `updated_at`.
  - [x] Add FK `plant_id` to `plants(id)` and choose explicit delete behavior; default recommendation: block plant delete when machine groups exist unless service intentionally handles cascade later.
  - [x] Add unique constraint for same-name rejection within a plant, e.g. `uq_machine_groups_plant_id_name`.
  - [x] Add index `idx_machine_groups_plant_id`.
  - [x] Keep table/column names `snake_case`; constraints `uq_*`, `idx_*`, `fk_*`, `ck_*`.

- [x] Task 2: Add backend Machine Group API under `/api/v1/machine-groups` (AC: #1-#10)
  - [x] Add controller, DTOs, service, entity/repository under `com.syncro.masterdata.*` unless a clearer `machine` boundary is introduced and fully wired.
  - [x] Recommended endpoints: `GET /api/v1/machine-groups?plantId=...`, `GET /api/v1/machine-groups/{machineGroupId}`, `POST /api/v1/machine-groups`, `PUT /api/v1/machine-groups/{machineGroupId}`, `DELETE /api/v1/machine-groups/{machineGroupId}`.
  - [x] Use Java records for request/response DTOs; never serialize JPA entities.
  - [x] Request DTO must require `plantId` and `name`; add bounded `name` constraints and any optional field only if needed by AC.
  - [x] Normalize names consistently for duplicate detection; preserve user-facing name display after trim.
  - [x] Return `201` for create, `200` for update/get/list, `204` for delete.
  - [x] Add explicit Springdoc `@ApiResponses` so Orval generates correct success statuses and error metadata.

- [x] Task 3: Enforce roles and plant scope server-side (AC: #5-#9)
  - [x] Reuse `PlantScopeService` for non-SUPER_ADMIN plant access checks.
  - [x] `SUPER_ADMIN` can list/view/mutate all machine groups.
  - [x] `MANAGE` can create/list/view/update/delete only for assigned plant scope.
  - [x] `VIEWER` can list/view permitted machine groups and cannot mutate.
  - [x] Empty assigned scope must not leak all data.
  - [x] Direct out-of-scope `plantId` or `machineGroupId` must return safe `403`, not empty success for mutation.

- [x] Task 4: Add safe error handling (AC: #3, #4, #6, #8, #9)
  - [x] Add machine-group-specific duplicate/not-found/data-integrity exceptions or shared master-data exceptions if small and clear.
  - [x] Duplicate same plant/name returns stable machine-readable code such as `DUPLICATE_MACHINE_GROUP_NAME`.
  - [x] Missing group returns `MACHINE_GROUP_NOT_FOUND`.
  - [x] Invalid UUID path/query values return existing `INVALID_PATH_VALUE` or equivalent safe error.
  - [x] Do not expose SQL constraint names, Java exception names, stack traces, or raw database messages.

- [x] Task 5: Generate/update OpenAPI and Orval client (AC: #11, #12)
  - [x] Regenerate frontend API client from backend Springdoc after endpoints are available.
  - [x] Keep generated files isolated under `syncro/apps/web/src/lib/api/generated/`.
  - [x] Do not hand edit generated client output except as an emergency patch recorded in Dev Agent Record.
  - [x] Ensure generated client records create as `201` and delete as `204`.

- [x] Task 6: Build Machine Groups management UI (AC: #5, #7, #11)
  - [x] Replace `syncro/apps/web/src/app/(main)/dashboard/master-data/machine-groups/page.tsx` placeholder with route composition only.
  - [x] Put client logic under `syncro/apps/web/src/features/master-data/machine-groups/`.
  - [x] Use generated Orval/TanStack Query hooks for CRUD.
  - [x] Use plant scope and available plant list to filter by plant.
  - [x] Prefer shadcn/Radix non-native select/dropdown for plant selection.
  - [x] Include loading, empty, error, read-only VIEWER, forbidden, validation error, create, edit, delete confirmation, and mutation pending states.
  - [x] Query keys must include active plant scope and selected plant filter.
  - [x] Mutations must invalidate machine-group list/detail queries and any setup-completeness data if introduced later.

- [x] Task 7: Add backend tests (AC: #1-#10, #12)
  - [x] Add MockMvc/API tests for unauthenticated, SUPER_ADMIN, MANAGE, VIEWER, duplicate same-plant name, duplicate cross-plant allowed, invalid payload, malformed JSON, out-of-scope list/mutation, not found, invalid UUID, and delete behavior.
  - [x] Add Testcontainers integration tests for migration, FK, unique constraint, scope filtering, and delete behavior.
  - [x] Use real PostgreSQL for uniqueness/FK/query behavior; do not mock persistence for these paths.
  - [x] Use Story/Test IDs and priority markers in display names, e.g. `2.2-API-001 P0 ...`.

- [x] Task 8: Verify frontend and full baseline (AC: #11, #12)
  - [x] Run backend targeted tests for machine group API/service.
  - [x] Run `mvn -f syncro/apps/backend/pom.xml test`.
  - [x] Run `npm --prefix syncro/apps/web run generate:api` if backend OpenAPI server is available.
  - [x] Run `npm --prefix syncro/apps/web run check`.
  - [x] Run `npm --prefix syncro/apps/web run lint`.
  - [x] Run `npm --prefix syncro/apps/web run build`.
  - [x] Start backend and frontend; verify Machine Groups create/list/filter/edit/delete and VIEWER read-only in browser.
  - [x] Run `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` if script remains current.
  - [x] Map every AC to evidence in Dev Agent Record before moving story to review.

## Dev Notes

### Scope Boundary

Story 2.2 owns plant-scoped machine group/process-line CRUD only. Do not implement machines, spareparts, installations, responsibilities, setup completeness, telemetry, alerts, WAHA, audit-log story 2.9, or ABAC/job-scope rules beyond current application role plus plant scope.

Machine group means process line, e.g. `Forming`, scoped under plant `GM1`. Same name across plants is valid; same name within one plant is invalid.

### Current Code State To Preserve

- `syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql` already owns `plants` and `auth_user_plant_assignments`; add new migration instead of editing it.
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/PlantEntity.java` and `PlantRepository.java` map existing `plants` table from auth foundation. Story 2.2 may reference plant IDs through repository/service but must avoid duplicating plant table/entity.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/application/PlantService.java` established masterdata application-service pattern with `@Transactional`, role checks, `PlantScopeService`, duplicate guard before save, and database integrity catch.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantController.java` established `/api/v1/plants` controller style and Springdoc annotation pattern.
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/PlantExceptionHandler.java` already normalizes Plant CRUD validation, malformed JSON, invalid UUID path, duplicate, not found, forbidden, and data integrity errors. Extend carefully or split machine-group handler without conflicting error shapes.
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/api/PlantControllerTest.java` and `PlantServiceIntegrationTest.java` are current test style references.
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machine-groups/page.tsx` is currently a placeholder guarded only for `SUPER_ADMIN` and `MANAGE`; Story 2.2 must allow `VIEWER` read-only access if AC7 is implemented.
- `syncro/apps/web/src/features/master-data/plants/plant-management.tsx` is the closest UI pattern for generated hooks, TanStack Query invalidation, shadcn table/dialogs, validation errors, loading/empty/error/read-only states, and delete confirmation.
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
- For list by `plantId`, non-SUPER_ADMIN must pass `PlantScopeService.requirePlantAccess(user, plantId)` before returning data.
- For get/update/delete by `machineGroupId`, service must load group, then scope-check its `plantId`; avoid checking only user-provided plantId.

### Recommended Data Model

```sql
CREATE TABLE machine_groups (
  id UUID PRIMARY KEY,
  plant_id UUID NOT NULL,
  name VARCHAR(255) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT fk_machine_groups_plant FOREIGN KEY (plant_id) REFERENCES plants(id) ON DELETE RESTRICT,
  CONSTRAINT uq_machine_groups_plant_id_name UNIQUE (plant_id, name),
  CONSTRAINT ck_machine_groups_name_not_blank CHECK (btrim(name) <> '')
);

CREATE INDEX idx_machine_groups_plant_id ON machine_groups(plant_id);
```

If PostgreSQL syntax for `ON DELETE RESTRICT` with existing migration style needs adjustment, use valid PostgreSQL FK syntax but preserve explicit behavior.

### API Contract Recommendation

```text
GET    /api/v1/machine-groups?plantId={plantId}
GET    /api/v1/machine-groups/{machineGroupId}
POST   /api/v1/machine-groups
PUT    /api/v1/machine-groups/{machineGroupId}
DELETE /api/v1/machine-groups/{machineGroupId}
```

Request:

```json
{
  "plantId": "uuid",
  "name": "Forming"
}
```

View:

```json
{
  "id": "uuid",
  "plantId": "uuid",
  "plantCode": "GM1",
  "plantName": "Plant GM1",
  "name": "Forming",
  "createdAt": "2026-05-27T00:00:00Z",
  "updatedAt": "2026-05-27T00:00:00Z"
}
```

Include plant code/name in view if it avoids extra frontend lookups. Keep response compact; do not add machine counts unless required by this story.

### Frontend Implementation Guardrails

- `app/` route stays composition only.
- Feature implementation belongs under `src/features/master-data/machine-groups/`.
- Use generated hooks/client for machine groups and plants. Existing Plant generated hooks can provide filter options if no separate plant selector contract is added.
- Query key must include selected `plantId` and active plant scope.
- Empty assigned scope should not fire unrestricted list queries.
- VIEWER sees data and read-only badges, not mutation buttons.
- MANAGE with empty scope should not see fake global data. If create is allowed only into assigned plant, disable create with explanation until a plant is assigned; unlike Plant create in Story 2.1, machine group create requires an existing accessible plant.
- Use shadcn/Radix non-native select/dropdown/picker for plant selection.
- Backend validation errors map to inline fields and safe form-level message.
- Delete confirmation must state selected group and whether future machines may block deletion.

### Previous Story Intelligence

Story 2.1 established Plant CRUD, Springdoc/OpenAPI, Orval, TanStack Query, Plant UI, and review-learned patterns.

Review lessons to apply immediately:

- Do not block MANAGE create incorrectly; machine group create depends on assigned plant access, so UX must explain missing assignment rather than silently disabling without reason.
- Empty-scope frontend must not fire list queries that imply access.
- Scope-related mutations must invalidate both domain data and plant-scope/related setup data when relevant.
- Generated Orval statuses must match backend (`201` create, `204` delete); explicit OpenAPI responses matter.
- Integrity catch must distinguish duplicate same-plant/name from other data integrity violations.
- If endpoint can return `409`, document it in Springdoc annotations.
- Backend test names should include Story/Test IDs and priority markers.

### Testing Requirements

Minimum backend test matrix:

- unauthenticated list/get/create/update/delete: `401`.
- VIEWER create/update/delete: `403`.
- VIEWER list/get assigned plant groups: `200`.
- MANAGE create under assigned plant: `201`.
- MANAGE create under unassigned plant: `403`.
- MANAGE list with unassigned plant filter: `403` or safe scoped empty only if documented; prefer `403` for explicit plant filter.
- SUPER_ADMIN same group name in different plants: allowed.
- duplicate same plant/name: stable duplicate error.
- blank/null/too-long name and missing/malformed plantId: `400 VALIDATION_ERROR`.
- malformed JSON/type mismatch: safe `400`.
- invalid UUID path/query: safe `400`.
- missing plant: safe not-found or validation error by documented rule.
- missing machine group: safe `404`.
- delete behavior: succeeds without dependent machines; future FK behavior documented.

Minimum frontend/browser evidence:

- SUPER_ADMIN sees plant filter and can create/list/edit/delete group.
- MANAGE sees only assigned plant choices and can mutate assigned plant group.
- VIEWER sees read-only table, no mutation controls.
- Empty plant assignment state explains contact/admin or missing assignment.
- Validation error displays inline.
- Duplicate same plant/name error displays safe message.
- Loading, empty, error/forbidden, and delete confirmation states are reachable or covered by component-level/manual evidence.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story 2.2: Manage Plant-Scoped Machine Groups]
- [Source: _bmad-output/planning-artifacts/architecture.md#Data Architecture]
- [Source: _bmad-output/planning-artifacts/architecture.md#Authentication & Security]
- [Source: _bmad-output/planning-artifacts/architecture.md#API & Communication Patterns]
- [Source: _bmad-output/planning-artifacts/architecture.md#Implementation Patterns & Consistency Rules]
- [Source: _bmad-output/planning-artifacts/ux-design-specification.md#Setup Flow: Build Machine Foundation]
- [Source: _bmad-output/planning-artifacts/page-specifications.md#Master Data management patterns]
- [Source: _bmad-output/implementation-artifacts/2-1-manage-plants.md#Previous Story Intelligence]
- [Source: syncro/apps/backend/src/main/resources/db/migration/V2__create_plant_scope_foundation.sql]
- [Source: syncro/apps/backend/src/main/java/com/syncro/masterdata/application/PlantService.java]
- [Source: syncro/apps/web/src/features/master-data/plants/plant-management.tsx]

## Dev Agent Record

### Agent Model Used

cx/gpt-5.5 via Claude Code.

### Debug Log References

- `mvn -f syncro/apps/backend/pom.xml "-Dtest=MachineGroupControllerTest,MachineGroupServiceIntegrationTest" test` — passed, 32 tests.
- `mvn -f syncro/apps/backend/pom.xml test` — passed, 88 tests.
- `npm --prefix syncro/apps/web run check` — passed.
- `npm --prefix syncro/apps/web run lint` — passed.
- `npm --prefix syncro/apps/web run build` — passed.
- `pwsh -NoProfile -File syncro/scripts/validate-syncro-baseline.ps1` — passed.
- Browser verification at `http://localhost:3001/dashboard/master-data/machine-groups` with local backend on `8080` — login, empty state, create, list, edit, delete verified.

### Completion Notes List

- Story created by BMad create-story workflow on 2026-05-27.
- Ultimate context engine analysis completed - comprehensive developer guide created.
- Added `machine_groups` Flyway migration with plant FK, unique per-plant name constraint, blank-name check, and plant index.
- Added backend CRUD API, DTOs, service, repository/entity, Springdoc responses, role/scope checks, and safe error handling.
- Generated Orval/TanStack Query client models/hooks for machine groups.
- Replaced Machine Groups placeholder route with feature UI covering plant filter, empty/loading/error/read-only/forbidden-oriented states, validation errors, create/edit/delete flows, and mutation pending states.
- Added MockMvc and Testcontainers integration coverage for authentication, authorization, validation, duplicate names, scope filtering, constraints, and delete behavior.
- Addressed test-review findings with a DB-level case-insensitive unique index and missing unauthenticated get, invalid plant filter, and out-of-scope create API tests.

### Acceptance Criteria Evidence

- AC1 -> `MachineGroupServiceIntegrationTest` `2.2-SVC-001`, browser create/list evidence.
- AC2 -> `MachineGroupServiceIntegrationTest` `2.2-SVC-003`.
- AC3 -> `MachineGroupControllerTest` `2.2-API-007`, `MachineGroupServiceIntegrationTest` `2.2-SVC-002`, `2.2-SVC-010`.
- AC4 -> `MachineGroupControllerTest` `2.2-API-005`, `2.2-API-006`, `2.2-API-013`, `2.2-API-015`.
- AC5 -> `MachineGroupControllerTest` `2.2-API-002`, `MachineGroupServiceIntegrationTest` `2.2-SVC-004`.
- AC6 -> `MachineGroupControllerTest` `2.2-API-008`, `2.2-API-016`, `MachineGroupServiceIntegrationTest` `2.2-SVC-005`, `2.2-SVC-006`.
- AC7 -> UI hides mutation controls for non-mutating role via `canMutate`; backend VIEWER list/get and mutation denial covered by API/service tests.
- AC8 -> `MachineGroupControllerTest` `2.2-API-004`, `2.2-API-011`, `2.2-API-012`.
- AC9 -> `MachineGroupControllerTest` `2.2-API-001`, `2.2-API-010`, `2.2-API-014`.
- AC10 -> migration FK uses `ON DELETE RESTRICT`; `MachineGroupServiceIntegrationTest` `2.2-SVC-008` verifies delete without dependents.
- AC11 -> browser verified empty, create, list, edit, delete confirmation, mutation completion; UI code covers loading/error/read-only/forbidden/validation states.
- AC12 -> evidence mapped above; full backend tests, frontend check/lint/build, and baseline validation passed.

### File List

- `_bmad-output/implementation-artifacts/2-2-manage-plant-scoped-machine-groups.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupController.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupDtos.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/api/MachineGroupExceptionHandler.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/application/MachineGroupService.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupEntity.java`
- `syncro/apps/backend/src/main/java/com/syncro/masterdata/infrastructure/MachineGroupRepository.java`
- `syncro/apps/backend/src/main/resources/db/migration/V3__create_machine_groups.sql`
- `syncro/apps/backend/src/main/resources/db/migration/V4__add_machine_group_case_insensitive_unique_index.sql`
- `syncro/apps/backend/src/test/java/com/syncro/SyncroBackendApplicationTests.java`
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/api/MachineGroupControllerTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/masterdata/application/MachineGroupServiceIntegrationTest.java`
- `syncro/apps/web/src/app/(main)/dashboard/master-data/machine-groups/page.tsx`
- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx`
- `syncro/apps/web/src/lib/api/generated/model/index.ts`
- `syncro/apps/web/src/lib/api/generated/model/list1Params.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupListResponse.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupRequest.ts`
- `syncro/apps/web/src/lib/api/generated/model/machineGroupView.ts`
- `syncro/apps/web/src/lib/api/generated/syncro.ts`

### Change Log

- 2026-05-27 — Created Story 2.2 with machine group CRUD, plant-scope, validation, API contract, UI, and test requirements.
- 2026-05-27 — Implemented plant-scoped machine group migration, backend API, generated client, management UI, and validation/test evidence.
- 2026-05-27 — Resolved test-review findings for DB case-insensitive uniqueness and missing API coverage.

## Story Completion Status

Story ready for review.
