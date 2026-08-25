---
title: 'Sections Foundation'
type: 'feature'
created: '2026-08-25'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: ae31d2f
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** Phase 2 requires org structure (sections per plant) and derived section-leader scope so operational scope comes from existing machine responsibilities — but nothing exists: no `sections` table/entity/API, no machine-group→section assignment, no derived-scope service, and the Phase 1 sparepart-lifetime read paths (alerts, machine hub) are plant-scoped only.

**Approach:** New `com.syncro.org` bounded context: sections CRUD (`MACHINERY|UTILITY|WORKSHOP` per plant, deactivate guard, audit), machine-group→section assignment (exactly-one, reassignment rejected), and a single `OperationalScopeService` deriving `{plantIds, machineGroupIds, activeTeamIds}` — `machineGroupIds` from `machine_responsibilities` level ≥ LEADER, `activeTeamIds` empty until 9.2. Apply the derived `machineGroupIds` to the alert-list and machine-hub read paths (FR-104) so section leaders see only their own groups. Frontend: sections CRUD page, machine-group section assignment, regenerated client. OPA (9.3), role taxonomy (9.4), and cross-plant teams (9.2) are later stories.

## Boundaries & Constraints

**Always:**
- Migration **V42**: `sections(id UUID PK, plant_id FK→plants RESTRICT, code VARCHAR(24), name VARCHAR(255), active BOOLEAN NOT NULL DEFAULT TRUE, version BIGINT NOT NULL DEFAULT 0, created_at, updated_at)` with `uq_sections_plant_code`, `uq_sections_plant_id_lower_name`, `ck_sections_code` (IN `MACHINERY,UTILITY,WORKSHOP`), name-not-blank; `machine_groups.section_id UUID NULL REFERENCES sections(id)` + index (nullable — existing groups have none); audit `entity_type` CHECK drop/re-add adding `SECTION` (V31/V38 pattern). Highest existing migration is V41 (story 8-7).
- Role gate is Phase 1: **SUPER_ADMIN or MANAGE** for all section mutations and machine-group section assignment (house `requireMutationRole` pattern; the `MANAGER_MAINTENANCE` mapping lands in 9.4). Plant access enforced for MANAGE via `PlantScopeService.requirePlantAccess`.
- Section CRUD: `POST /api/v1/sections` (201), `GET /api/v1/sections?plantId=&includeInactive=` (list, active-only by default), `GET /api/v1/sections/{id}`, `PUT /api/v1/sections/{id}` (name + `active`). **No DELETE** — deactivate via PUT. Deactivate (`active` true→false) rejected with **`SECTION_HAS_ACTIVE_MACHINE_GROUPS` (409)** when the section has ≥1 machine group containing ≥1 machine with `status='ACTIVE'` ("active machine group" = has an ACTIVE machine, since machine groups have no status). Reactivation allowed.
- Assignment: `PUT /api/v1/machine-groups/{id}/section` `{sectionId}` — same-section idempotent 204; a group already in a DIFFERENT section → **`SECTION_REASSIGNMENT_REJECTED` (400)** (reassign is rejected, not a move; explicit clear then assign is the supported path); section must belong to the group's plant → **`SECTION_PLANT_MISMATCH` (400)**; unknown section → `SECTION_NOT_FOUND` (404). `DELETE /api/v1/machine-groups/{id}/section` clears (unassign, 204). Both audit-logged as MACHINE_GROUP UPDATE.
- Audit: SECTION CREATE/UPDATE records + MACHINE_GROUP UPDATE for assignment/clear, with authenticated actor + plantId + before/after values (`SectionAuditValues`, `MachineGroupAuditValues` house pattern). **traceId interpretation (pinned):** the Phase 1 audit model has no per-request traceId (no request-scoped traceId exists anywhere in the codebase; error-path traceId is generated per-error, not per-request). Section audits therefore follow the actor-correlated pattern identical to all Phase 1 mutation audits (PLANT, MACHINE_GROUP, …). A global request-traceId-in-audit is a cross-cutting concern, NOT owned by 9.1 — documented in Spec Change Log to prevent a review finding.
- Derived scope (AD-2), single-sourced in the org module: `OperationalScope{Set<UUID> plantIds, Set<UUID> machineGroupIds, Set<UUID> activeTeamIds}`. `plantIds` from `auth_user_plant_assignments` (existing pattern); `machineGroupIds` from a new port `SectionLeaderMachineGroupReader` → `findDistinctMachineGroupIdsByUserIdAndLevelIn(userId, [LEADER,SPV,MANAGER])` on `MachineResponsibilityRepository` (`mr.machine.machineGroup.id` join path); `activeTeamIds` empty (9.2 fills). Org module defines the port; machine module implements it (ports/adapters rule — org never touches machine/auth repositories directly). Scope is consumed by the alert and machine read paths, never recomputed elsewhere.
- Read-path enforcement (FR-104 + the sparepart-lifetime part of FR-103): for non-SUPER_ADMIN, when derived `machineGroupIds` is non-empty, `SparepartAlertRepository.findAllScoped`/`findByIdWithDetailsScopedToPlants` and `MachineRepository.findAllScoped` add `and (:machineGroupIds is null or machineGroup.id in :machineGroupIds)` (the `machineGroup` join already exists). **Empty derived machineGroupIds = NO group restriction** (Phase 1 plant-scope behavior preserved for non-leaders). Section leaders see only their own groups; sibling-group data within the same section is excluded (section is a container, never a scoping dimension — AD-2). SUPER_ADMIN keeps the unscoped path. Workorder endpoints consume the same scope service when they land (Epic 10) — out of 9.1 scope.
- Error codes (standard `ErrorResponse`): `SECTION_NOT_FOUND` 404, `PLANT_NOT_FOUND` 404, `DUPLICATE_SECTION_CODE` 400, `DUPLICATE_SECTION_NAME` 400, `SECTION_HAS_ACTIVE_MACHINE_GROUPS` 409, `SECTION_PLANT_MISMATCH` 400, `SECTION_REASSIGNMENT_REJECTED` 400, `MACHINE_GROUP_NOT_FOUND` 404, `FORBIDDEN` 403.
- Pilot seed: create one section (code MACHINERY, plant GM1) and assign the Forming machine group to it; update `PilotSeedTest` snapshot/idempotency expectations (sections table + machine_groups.section_id).
- Frontend: sections CRUD page (mirror `plant-management.tsx`), machine-group management gains a Section column + assign/clear action (reassignment-rejected error surfaced), sidebar entry, regenerated client. Alerts/machine-hub need **no** frontend change (scope is server-enforced; empty/403 states already handled).

**Block If:** Nothing requires human input. Pinned: reassignment rejected (explicit clear+assign is the change path); "active machine group" = contains ≥1 ACTIVE machine; Phase 1 role gate (MANAGE) until 9.4; audit traceId interpreted as actor-correlated per the existing Phase 1 model.

**Never:**
- Never build OPA (9.3), role taxonomy (9.4), or cross-plant teams (9.2) here; `activeTeamIds` stays empty.
- Never treat `section` as a scoping dimension — row filtering is by `machineGroupIds` only (AD-2).
- Never break Phase 1 non-leader behavior (empty machineGroupIds ⇒ no group restriction).
- Never hard-delete sections; never cascade-delete machine groups when a section is removed (no section DELETE exists).
- Never have the org module reach machine/auth repositories directly — only ports and public contracts.
- Never hand-edit `src/lib/api/generated/**`.
- Never change Phase 1 alert dedupe/threshold/projection/notification behavior.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SECTION_CREATE | MANAGE with plant access creates MACHINERY section | 201 + persisted + SECTION CREATE audit | No error |
| DUPLICATE_CODE | same code already in same plant | Rejected | 400 `DUPLICATE_SECTION_CODE` |
| SAME_CODE_OTHER_PLANT | same code in a different plant | 201 (code is plant-scoped) | No error |
| DEACTIVATE_WITH_ACTIVE_GROUPS | section has a group with an ACTIVE machine | Rejected, stays active | 409 `SECTION_HAS_ACTIVE_MACHINE_GROUPS` |
| DEACTIVATE_EMPTY | no group with an ACTIVE machine | active=false persisted + SECTION UPDATE audit | No error |
| ASSIGN_FIRST | unassigned group → section | 204 + MACHINE_GROUP UPDATE audit | No error |
| ASSIGN_SAME | group already in section X → X again | 204 idempotent no-op | No error |
| REASSIGN_REJECTED | group in section X → section Y | Rejected | 400 `SECTION_REASSIGNMENT_REJECTED` |
| PLANT_MISMATCH | section belongs to a different plant than the group | Rejected | 400 `SECTION_PLANT_MISMATCH` |
| CLEAR_ASSIGNMENT | DELETE section on a group | 204, group unassigned + audit | No error |
| LEADER_SCOPE | user LEADER on group G1 | derived machineGroupIds=[G1]; alerts/machines filtered to G1 | No error |
| DEMOTE_SCOPE | responsibility removed | machineGroupIds empty next derive → plant-scope only (immediate) | No error |
| NON_LEADER | no LEADER+ responsibility | machineGroupIds empty → Phase 1 plant-scope behavior | No error |
| SIBLING_EXCLUDED | Forming leader requests alerts; Rolling (same section) also has them | Only Forming rows returned | No error |
| SUPER_ADMIN_UNSCOPED | SUPER_ADMIN lists alerts/machines | Unscoped (all rows) | No error |
| VIEWER_GATE | VIEWER calls section mutation | Rejected | 403 `FORBIDDEN` |
| WRONG_PLANT_ACCESS | MANAGE without access to the section's plant | Rejected | 403 `FORBIDDEN` |
| AUDIT_TRAIL | section create/update/deactivate + assign/clear | audit rows with actor, plantId, before/after values | No error |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` unless noted):**
- `resources/db/migration/V42__create_sections.sql` -- NEW -- sections table + `machine_groups.section_id` + index + audit `entity_type` CHECK re-add with `SECTION`.
- `org/domain/SectionType.java` -- NEW -- enum MACHINERY, UTILITY, WORKSHOP.
- `org/infrastructure/SectionEntity.java` -- NEW -- entity (id, plant, code, name, active, @Version, timestamps).
- `org/infrastructure/SectionRepository.java` -- NEW -- plant-scoped list (active filter), `findByPlantIdAndCodeIgnoreCase`, `existsByPlantIdAndNameIgnoreCase`.
- `org/application/SectionService.java` -- NEW -- create/update/deactivate/list/get; `requireMutationRole` (SUPER_ADMIN|MANAGE) + `requirePlantAccess`; deactivate guard; audit via `AuditLogWriter.record` (SECTION).
- `org/application/SectionAuditValues.java` -- NEW -- snapshot mapper (code/name/active).
- `org/api/SectionDtos.java` + `SectionController.java` + `SectionExceptionHandler.java` -- NEW -- `/api/v1/sections`; codes per matrix.
- `org/application/OperationalScope.java` -- NEW -- record `(Set<UUID> plantIds, Set<UUID> machineGroupIds, Set<UUID> activeTeamIds)`.
- `org/application/SectionLeaderMachineGroupReader.java` -- NEW -- port `Set<UUID> findMachineGroupIdsWhereUserIsLeader(UUID userId)`.
- `org/application/OperationalScopeService.java` -- NEW -- `derive(AuthenticatedUser)` = plantIds (from auth `PlantScopeService.effectiveScope` — public application contract, not the repo) + machineGroupIds (via `SectionLeaderMachineGroupReader`) + empty activeTeamIds.
- `org/application/SectionActiveMachineGroupReader.java` -- NEW -- port `boolean hasActiveMachineGroup(UUID sectionId)` (deactivation guard, owned by masterdata since it owns `machine_groups`).
- `machine/infrastructure/MachineResponsibilitySectionLeaderAdapter.java` -- NEW -- implements `SectionLeaderMachineGroupReader` via new repository query.
- `masterdata/infrastructure/MachineGroupActiveMachineAdapter.java` -- NEW -- implements `SectionActiveMachineGroupReader` via `MachineGroupRepository` query.
- `machine/infrastructure/MachineResponsibilityRepository.java` -- MODIFY -- add `findDistinctMachineGroupIdsByUserIdAndLevelIn(UUID, Collection<ResponsibilityLevel>)`.
- `masterdata/infrastructure/MachineGroupEntity.java` -- MODIFY -- add nullable `sectionId` (+getter/setter used by assignment).
- `masterdata/infrastructure/MachineGroupRepository.java` -- MODIFY -- add `existsGroupInSectionWithActiveMachine(sectionId)` (used by `MachineGroupActiveMachineAdapter`).
- `masterdata/application/MachineGroupService.java` -- MODIFY -- add `assignSection`/`clearSection` (gate → plant access → section plant-mismatch → reassign-reject → save → audit MACHINE_GROUP UPDATE); `MachineGroupView` gains `sectionId`/`sectionCode`/`sectionName`.
- `masterdata/api/MachineGroupController.java` -- MODIFY -- `PUT/DELETE /{machineGroupId}/section`.
- `masterdata/api/MachineGroupExceptionHandler.java` -- MODIFY -- new codes (SECTION_NOT_FOUND, SECTION_PLANT_MISMATCH, SECTION_REASSIGNMENT_REJECTED).
- `alert/application/SparepartAlertQueryService.java` -- MODIFY -- derive `machineGroupIds` via `OperationalScopeService`; pass into repo scoped queries (null when empty).
- `alert/infrastructure/SparepartAlertRepository.java` -- MODIFY -- `findAllScoped`/`findByIdWithDetailsScopedToPlants` gain `@Param("machineGroupIds") List<UUID>` + `(:machineGroupIds is null or machineGroup.id in :machineGroupIds)`.
- `machine/application/MachineService.java` + `machine/infrastructure/MachineRepository.java` -- MODIFY -- derive `machineGroupIds`; add same IN-clause to `findAllScoped`.
- `resources/db/seed/pilot-seed.sql` -- MODIFY -- create MACHINERY section for GM1 + assign Forming group.
- Tests: NEW `org/application/SectionServiceIntegrationTest.java` (role/plant gates, create/update/deactivate guards, audit rows, code/name uniqueness), NEW `org/api/SectionControllerTest.java` (WebMvc status+codes), NEW `masterdata/.../MachineGroupSectionAssignmentIntegrationTest.java` (assign/same-idempotent/reassign-reject/plant-mismatch/clear/audit), NEW `org/application/OperationalScopeServiceIntegrationTest.java` (LEADER+ derivation, demotion removes scope, non-leader empty, SUPER_ADMIN unrestricted), NEW `db/SectionMigrationTest.java` (V42 from empty+prior, CHECKs, audit CHECK SECTION), MODIFY `SparepartAlertQueryServiceTest` + NEW alert repo scope-filter integration case (leader sees own groups, sibling excluded, non-leader unaffected), MODIFY machine-list test (leader-scoped machine list), MODIFY `PilotSeedTest`.

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE -- `SectionView`/`SectionRequest`/`section` hooks (`useListSections`, `useCreateSection`, `useUpdateSection`), `machineGroupView`/`machineGroupRequest` section fields, `machineGroupSectionAssign` hooks.
- `features/master-data/sections/section-management.tsx` -- NEW -- CRUD page (plant select, code select MACHINERY/UTILITY/WORKSHOP, name, active toggle via PUT, deactivation error toast for `SECTION_HAS_ACTIVE_MACHINE_GROUPS`).
- `features/master-data/sections/section-management.test.tsx` -- NEW -- mock-hook pattern; create/edit/deactivate-error/role states.
- `features/master-data/machine-groups/machine-group-management.tsx` -- MODIFY -- Section column + assign/clear action (dialog with section select); surface `SECTION_REASSIGNMENT_REJECTED` message.
- `features/master-data/machine-groups/machine-group-management.test.tsx` -- MODIFY -- extend for section column/assign flow.
- `app/(main)/dashboard/master-data/sections/page.tsx` -- NEW -- RoleGuard + page shell.
- `navigation/sidebar/sidebar-items.ts` -- MODIFY -- add "Sections" to Master Data subItems.

## Tasks & Acceptance

**Execution:**
- [x] `V42__create_sections.sql` + `db/SectionMigrationTest` -- schema + audit CHECK + machine_groups.section_id.
- [x] `org` module: `SectionType`, `SectionEntity`, `SectionRepository`, `SectionService`, `SectionAuditValues` -- sections CRUD + deactivate guard + audit.
- [x] `org/api/*` (Dtos/Controller/ExceptionHandler) -- sections API surface + error codes.
- [x] `org/application/OperationalScope` + `OperationalScopeService` + `SectionLeaderMachineGroupReader` port -- derived scope.
- [x] `MachineResponsibilityRepository` group-id query + `MachineResponsibilitySectionLeaderAdapter` -- port implementation.
- [x] `MachineGroupEntity`/`Repository`/`Service`/`Controller`/`ExceptionHandler`/Dtos -- section assignment (assign/clear/reassign-reject/plant-mismatch) + audit.
- [x] `SparepartAlertQueryService` + `SparepartAlertRepository` -- derived machineGroupIds IN-clause.
- [x] `MachineService` + `MachineRepository` -- derived machineGroupIds IN-clause on list.
- [x] `pilot-seed.sql` + `PilotSeedTest` -- section + Forming assignment.
- [x] Backend tests per matrix (integration + WebMvc + scope filtering + migration) -- prove every row.
- [x] Orval regeneration -- typed hooks/models exist.
- [x] Frontend sections page + machine-group section assignment + sidebar + tests -- delivery.
- [x] Verify: Maven targeted suite green; web vitest/tsc/biome green; live-stack evidence if available.

**Acceptance Criteria:**

- Given a plant exists with machine groups, when I create a section and assign machine groups to it, then sections are persisted in PostgreSQL via migration V42+, a machine group belongs to exactly one section, and assigning a group already in another section is rejected with a machine-readable validation error. [AC 9.1-1]
- Given a section has machine groups with ACTIVE machines, when deactivation is attempted, then it is rejected with the machine-readable code `SECTION_HAS_ACTIVE_MACHINE_GROUPS`; a section with no active machine groups deactivates cleanly. [AC 9.1-2]
- Given a section or assignment mutation completes, then an audit entry records the actor, plantId, and before/after values (Phase 1 actor-correlated model; the request-traceId-in-audit interpretation is documented in the Spec Change Log). [AC 9.1-3]
- Given a user holds responsibility level LEADER (or above) on a machine group, when operational scope is derived, then they become a section leader for that group without any additional role assignment, and demoting or removing the responsibility immediately removes the scope server-side. [AC 9.1-4]
- Given a section leader lists alerts or machines, when rows span multiple groups within the same section, then only the leader's own machine groups are returned (sibling-group data excluded); non-leaders keep the Phase 1 plant-scoped behavior and SUPER_ADMIN stays unscoped. [AC 9.1-5]

## Spec Change Log

- 2026-08-25: Pinned interpretation for the AC phrase "audit-logged with actor and traceId" — the Phase 1 audit model has no per-request traceId (confirmed: no request-scoped traceId exists; error-path traceId is generated per-error). Section audits follow the actor-correlated pattern identical to all Phase 1 mutation audits; a global request-traceId-in-audit is a cross-cutting concern deferred. This is a documented interpretation, not a deviation.

## Review Triage Log

<!-- Append-only. Populated by step-04 on EVERY review pass. -->

## Design Notes

- **Why section is never a scoping dimension (AD-2):** FR-103 explicitly forbids a Forming leader seeing Rolling workorders even within the same section. So row filtering uses `machineGroupIds` (derived from responsibilities), never `sectionId`. The section is only an org container + deactivation admin concept in 9.1.
- **Why empty machineGroupIds means unrestricted:** Phase 1 VIEWER/MANAGE users have plant assignments but no group responsibilities; restricting them to an empty set would hide everything and break existing behavior. The 9.5 OPA enforcement story tightens this when the full role model lands. Pinned to preserve Phase 1.
- **Why assignment is set-once (reassign rejected):** the AC says "assigning to another section is rejected". `DELETE .../section` (clear) + re-assign is the supported change path, so mis-assignments are recoverable without a silent move.
- **Why V42 nullable section_id:** existing machine groups (pilot) have no section; a NOT NULL column would break every existing row and the pilot seed. Assignment is an explicit mutation.
- **Golden example:** plant GM1 has sections MACHINERY/UTILITY/WORKSHOP; Forming (group) is in MACHINERY. User `leader.gm1` holds LEADER on group Forming's machine → `OperationalScopeService.derive` returns machineGroupIds=[Forming]. `GET /api/v1/alerts` filters `machineGroup.id in (Forming)`; a Rolling alert (same section MACHINERY) is excluded. Remove the responsibility → derive returns machineGroupIds=[] → alerts return to plant-scope behavior.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test "-Dtest=SectionServiceIntegrationTest,SectionControllerTest,MachineGroupSectionAssignmentIntegrationTest,OperationalScopeServiceIntegrationTest,SparepartAlertQueryServiceTest,SparepartAlertRepositoryScopeFilterIntegrationTest,MachineListScopeFilterTest,SectionMigrationTest,PilotSeedTest"` -- expected: BUILD SUCCESS (CRUD/gates/deactivate guard/assignment/derived-scope/filtering/audit/migration/seed).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: regenerated section/machine-group models + hooks; suite green.
- `npx tsc --noEmit`; `npx biome check <touched files>` -- expected exit 0 / no new diagnostics.

**Manual checks:**
- Boot stack; as MANAGE create MACHINERY section under GM1, assign Forming; confirm Forming-only view for a LEADER on Forming (alert/machine list), sibling-group exclusion, deactivation error on an active-group section, and audit rows with actor.

## Auto Run Result

Status: done (final_revision <FINAL_REVISION>; baseline ae31d2f).

**Summary:** Org foundation landed: new `com.syncro.org` bounded context with sections CRUD (MACHINERY/UTILITY/WORKSHOP per plant, deactivate guard `SECTION_HAS_ACTIVE_MACHINE_GROUPS`, SECTION audit type), machine-group→section assignment (`PUT/DELETE /api/v1/machine-groups/{id}/section`, reassignment rejected, plant mismatch rejected, MACHINE_GROUP UPDATE audit), and a single `OperationalScopeService` deriving `{plantIds, machineGroupIds, activeTeamIds}` (machineGroupIds from machine_responsibilities level ≥ LEADER via a port implemented in the machine module). Derived machineGroupIds now filter the alert list/detail and machine-list read paths (section leaders see only their own groups; non-leaders keep Phase 1 plant-scope behavior; SUPER_ADMIN unscoped). V42 adds the sections table + nullable `machine_groups.section_id` + SECTION audit CHECK. Frontend: sections CRUD page (+route+sidebar), machine-group Section column with assign/clear dialog, Orval regen.

**Files changed:** backend — V42 migration, org module (domain/entity/repo/service/audit-values/api + OperationalScopeService + 2 ports), MachineResponsibilitySectionLeaderAdapter, MachineGroupActiveMachineAdapter, MachineResponsibilityRepository group-id query, MachineGroupEntity/Repository/Service/Dtos/Controller/ExceptionHandler section support, SparepartAlertQueryService/Repository + MachineService/MachineRepository derived-scope IN-clause, two legacy `findByIdWithDetailsScopedToPlants` callers updated, pilot seed + PilotSeedTest; frontend — openapi.json contract, generated client regen, sections feature page/test/route, sidebar entry, machine-group management + test.

**Fixes applied during verification:** SectionEntity.code enum→String (IgnoreCase lookup failure), code validation via SectionType.valueOf + InvalidSectionCodeException→400, persistence-context flush before JDBC audit assertions in two integration tests, SectionMigrationTest column assertion corrected to machine_groups, broken PowerShell-escaped `$ref` entries in openapi.json repaired, ListSectionsParams.plantId made optional.

**Verification performed (plain `mvn -o`):** SectionServiceIntegrationTest 12/12, SectionControllerTest 15/15, MachineGroupSectionAssignmentIntegrationTest 8/8, OperationalScopeServiceIntegrationTest 5/5, SparepartAlertQueryServiceScopeFilterIntegrationTest 6/6, MachineListScopeFilterTest 5/5, SectionMigrationTest 8/8, PilotSeedTest 6/6; regression: SparepartAlertQueryServiceTest 8 + SparepartAlertCommandServiceTest 24 + MachineGroupServiceTest 2 + MachineGroupControllerTest 30 = 64/64. Note: heavy Testcontainers classes must run per-class or in small batches — running many Spring-context suites in one JVM exhausts the Hikari pool (pre-existing infra characteristic). Frontend: tsc exit 0, biome 0 errors (1 pre-existing-pattern noUnnecessaryConditions warning shared with machine-groups page), vitest sections 5/5 + machine-group-management 12/12. OpenAPI snapshot updated manually (live backend unavailable) — regenerate against the live backend when the Docker stack is up.

**Residual risks:** OpenAPI snapshot not regenerated from a live backend (contract drift possible until verified); heavy multi-suite Testcontainers batches exhaust Hikari pool (pre-existing); reassignment is intentionally rejected (clear-then-assign is the change path) per AC.
