---
title: 'Story 16-1: Department & MachineArea'
type: 'feature'
created: '2026-09-01'
baseline_revision: '3a8066f79bbdaf71e76e3ec39e7280e8f5a0ce6f'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-16-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: ['oversized']
deferred: []
---

<intent-contract>

## Intent

**Problem:** The org model already has plant-scoped departments with a department-user pivot (departments + department_users tables, fully wired in V1 and mapped by DepartmentEntity/DepartmentUserEntity). The remaining A2/A3/A11 gap is the machine physical-layout side: `machine_areas` exists in the schema and `machines.area_id` exists as a nullable column, but neither has API/service/audit/OPA wiring — the table is persistence-only and the machine entity does not even map `area_id`. SUPER_ADMIN cannot manage machine areas, and machines cannot reference their physical location.

**Approach:** Add a plant-scoped `MachineAreaService` (CRUD + soft-inactivate delete, mirroring `DepartmentService` conventions exactly: OPA mutation role SUPER_ADMIN/MANAGER_MAINTENANCE, plant-scope checks, AuditLogWriter with `AuditEntityType.MACHINE_AREA`, duplicate name/code protection) behind `MachineAreaController` at `/api/v1/machine-areas`. Wire `machines.area_id` into `MachineEntity`, `MachineCommand`/`MachineView`, `MachineDtos`, and `MachineService` with same-plant area validation, and extend the OPA rego `department_paths` set so machine-area mutations flow through the same MANAGER_MAINTENANCE gate.

## Boundaries & Constraints

**Always:**
- Follow the Department module as the pattern template: service in `com.syncro.org.application`, controller/DTOs/exception-handler in `com.syncro.org.api`, entity/repository already in `com.syncro.org.infrastructure.db`.
- Machine areas are plant-scoped, soft-inactive only (no hard delete); reject deactivation while machines reference the area.
- Mutations require `SUPER_ADMIN` or `MANAGER_MAINTENANCE` (mirror `DepartmentService.requireMutationRole`); non-SUPER_ADMIN mutations require plant access via `PlantScopeService`. Reads are any authenticated user with plant access (matches department pattern).
- `machines.area_id` is optional (nullable). When set, the area must belong to the same plant as the machine (mirror `MACHINE_GROUP_PLANT_MISMATCH` style guard).
- Every mutation writes an audit record via `AuditLogWriter` with `AuditEntityType.MACHINE_AREA` (exists in the V1 CHECK and enum already).
- Unique `(plant_id, name)` → `uq_machine_areas_plant_name` and `(plant_id, code)` where code non-null → `uq_machine_areas_plant_code`; map DataIntegrityViolation to a duplicate error like DepartmentService does.
- Do not touch the V1 migration — `machine_areas` and `machines.area_id` already exist in the schema.

**Block If:**
- The V1 schema and `MachineAreaEntity` disagree on a column → HALT blocked with the contradiction.

**Never:**
- No frontend work in this story (machine-area UI ships in 16-5 with the role-mapping screen).
- No changes to `department_users`/departments behavior or existing endpoints.
- No JPA auto-DDL, no migration edits, no new audit entity types.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create area | valid plantId + name (+ optional code) | 201 with view; row persisted; audit CREATE written | validation → 400 VALIDATION_ERROR |
| Duplicate area name | same (plantId, name) twice | 400 VALIDATION_ERROR duplicate-name | mapped from uq_machine_areas_plant_name |
| Duplicate area code | same (plantId, code) twice (code non-null) | 400 VALIDATION_ERROR duplicate-code | mapped from uq_machine_areas_plant_code |
| Deactivate area with machines | area has referencing machines | 409 MACHINE_AREA_HAS_MACHINES, not deactivated | counted via machines repo |
| Assign machine area | machine.areaId set to area in same plant | persisted; view returns areaId | 400 AREA_PLANT_MISMATCH when cross-plant |
| Set areaId null | clear area on machine | areaId null persisted | no error |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/org/api/DepartmentController.java` -- pattern template for MachineAreaController (list/get/create/update/delete, `@Operation` ids, `@AuthenticationPrincipal`).
- `syncro/apps/backend/src/main/java/com/syncro/org/api/DepartmentDtos.java` -- pattern for MachineAreaDtos (Create/Update requests, view with plantCode/plantName, list response, ErrorResponse).
- `syncro/apps/backend/src/main/java/com/syncro/org/api/DepartmentExceptionHandler.java` -- pattern for MachineAreaExceptionHandler (same error envelope, `@RestControllerAdvice(assignableTypes=MachineAreaController.class)`).
- `syncro/apps/backend/src/main/java/com/syncro/org/application/DepartmentService.java` -- pattern for MachineAreaService (requireMutationRole, plant scope, audit snapshot helper, duplicate-constraint mapping via `getMostSpecificCause().getMessage().contains(...)`).
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/MachineAreaEntity.java` -- exists; getters only, no `update()`/`deactivate()` yet — add them (fields: plantId plain UUID, code, name, description, active, createdAt, updatedAt).
- `syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/db/MachineAreaRepository.java` -- exists, bare JpaRepository; add `findByPlantIdAndNameIgnoreCase`, `existsByPlantIdAndNameIgnoreCase`, `existsByPlantIdAndCodeIgnoreCase`, `findByIdWithPlant` (or resolve plantId → PlantEntity via PlantRepository like DepartmentService does).
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineEntity.java` -- add `areaId` UUID column (`@Column(name="area_id")`, nullable, plain UUID per AD-3 cross-aggregate); add to constructor + getter + `update()`.
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java` -- `MachineCommand` + `MachineView` + `MachineAuditValues` gain `areaId`; create/update validate same-plant area; `toView` passes areaId through.
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineDtos.java` + `MachineController.java` -- `MachineRequest` + `MachineView` gain `areaId`; command mapping updated.
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineExceptionHandler.java` -- add handlers for area-not-found + area-plant-mismatch.
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- add `countByAreaId(UUID)`.
- `syncro/authz/policy/authz.rego` -- add `/api/v1/machine-areas` + `/api/v1/machine-areas/*` to `department_paths` set (line ~225) so mutations flow through the existing MANAGER_MAINTENANCE gate.
- `syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql` -- add one `machine_areas` row (e.g. "Production Floor 1") for GM1 and set BF-08410's `area_id`; keep idempotent guards.
- `syncro/apps/backend/src/test/java/com/syncro/db/PilotSeedTest.java` -- add `machine_areas` + area reference to expected counts/assertions.
- `syncro/apps/backend/src/test/java/com/syncro/org/application/DepartmentServiceTest.java` -- mock-based pattern for a MachineAreaServiceTest.
- `syncro/apps/backend/src/test/java/com/syncro/org/infrastructure/db/OrgEntityConventionIntegrationTest.java` -- shows the repository round-trip test style already covering machine_areas.
- `syncro/apps/backend/src/test/java/com/syncro/machine/api/MachineControllerTest.java` -- MockMvc pattern to extend with areaId assertions.

## Tasks & Acceptance

**Execution:**
1. `MachineAreaEntity` -- add `update(...)` + `deactivate(...)` methods.
2. `MachineAreaRepository` -- add name/code uniqueness exists-checks + countByAreaId support if needed.
3. `MachineAreaService` (new, `com.syncro.org.application`) -- CRUD mirroring DepartmentService incl. audit + plant scope + duplicate mapping + `MACHINE_AREA_HAS_MACHINES` guard via `machines.countByAreaId`.
4. `MachineAreaDtos` + `MachineAreaController` + `MachineAreaExceptionHandler` (new, `com.syncro.org.api`) -- `/api/v1/machine-areas` REST surface.
5. `MachineEntity`/`MachineService`/`MachineDtos`/`MachineController`/`MachineExceptionHandler`/`MachineRepository` -- `areaId` end-to-end with same-plant validation.
6. `authz.rego` -- extend `department_paths`.
7. `pilot-seed.sql` + `PilotSeedTest` -- seed one machine area for GM1, link BF-08410.
8. Tests -- `MachineAreaServiceTest` (mock, covering I/O matrix rows 1-4), `MachineAreaControllerTest` (MockMvc), extend `MachineControllerTest` for areaId (row 5-6).

**Acceptance Criteria:**
- Given the V1 schema (already containing `machine_areas` + `machines.area_id`), when the new service/controller boot, then `ddl-auto=validate` passes unchanged (no migration edit).
- Given a SUPER_ADMIN/MANAGER_MAINTENANCE user, when they create/update/deactivate a machine area via `/api/v1/machine-areas`, then the row persists, an audit `MACHINE_AREA` row is written, and OPA allows the mutation (MANAGER_MAINTENANCE gate).
- Given a machine update setting `areaId` to an area of the same plant, when the update is submitted, then the machine persists with `area_id` set and the view returns it; a cross-plant area returns 400 `AREA_PLANT_MISMATCH`.
- Given an area referenced by machines, when a deactivate is attempted, then 409 `MACHINE_AREA_HAS_MACHINES` and the area stays active.
- Given the pilot seed, when applied twice, then exactly one machine-area row exists, BF-08410 references it, and the idempotency count test stays green.

## Spec Change Log

## Review Triage Log

## Design Notes

- `MachineAreaEntity` stores `plantId` as a plain UUID (AD-3 cross-aggregate); to render `plantCode`/`plantName` in views, resolve the plant via `PlantRepository.findById` (same as DepartmentService's `findByIdWithPlant` pattern) rather than adding a `@ManyToOne`.
- The rego change is deliberately minimal: machine areas are org-maintenance config just like departments, so they share `department_paths` (mutations: MANAGER_MAINTENANCE; reads flow through generic `read_allowed`). No new rego rule needed.
- Duplicate-name vs duplicate-code disambiguation: inspect the constraint name in the exception message — `uq_machine_areas_plant_name` → name error, `uq_machine_areas_plant_code` → code error.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=MachineAreaServiceTest,MachineAreaControllerTest,MachineControllerTest,PilotSeedTest"` -- expected: all green.
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: BUILD SUCCESS.
- `npx rego fmt --diff syncro/authz/policy/authz.rego` (or equivalent `opa fmt --diff`) -- expected: no formatting diff.
- Manual: confirm `git grep -l "machine_areas" src/main/java` shows the new controller/service/entity and that `MachineEntity` maps `area_id`.
