---
title: 'Cross-Plant Teams'
type: 'feature'
created: '2026-08-25'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: a309a53
baseline_commit: a309a53639e89eae584afe5ef4044eee525ce482
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-9-1-sections-foundation.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Story 9.1 established derived section-leader scope from `machine_responsibilities` (level ≥ LEADER) but `activeTeamIds` is hardcoded to `Set.of()` in `OperationalScopeService.derive`. Shared repair work across plants — a technician from one plant working a breakdown in another plant's workshop — has no mechanism. The architecture (AD-13) says cross-plant teams fill this gap with expiry-dated memberships whose machine IDs merge into the `machineGroupIds` scope set for SQL filtering, without any policy redeploy or scheduled job.

**Approach:** New V43 migration: `teams` (expiry-dated, name-unique), `team_members` (user membership), `team_machines` (target machines). `TeamService` CRUD + member/machine link management gated by SUPER_ADMIN|MANAGE (Phase 1 role model; 9.4 maps to MANAGER_MAINTENANCE). `OperationalScopeService.derive` resolves target machines → their machine-group ids via a new port into the machine module, populating `activeTeamIds` (per the 9.1 record javadoc: "cross-plant team machine-group ids"). Read paths (alert list/detail, machine list) gain an additive OR-branch: base plant-scoped view unchanged, plus any row whose machine group ∈ team groups regardless of plant. Expiry evaluated lazily per derive via injected `Clock` — no worker, no redeploy, no scheduled job. Frontend: teams CRUD page with member/machine management dialogs, sidebar entry, regenerated client.

## Boundaries & Constraints

**Always:**
- Migration **V43**: `teams(id UUID PK, name VARCHAR(255) NOT NULL, expires_at TIMESTAMPTZ NOT NULL, version BIGINT NOT NULL DEFAULT 0, created_at TIMESTAMPTZ NOT NULL, updated_at TIMESTAMPTZ NOT NULL)` with `uq_teams_lower_name`, `ck_teams_name_not_blank`; `team_members(team_id UUID FK→teams ON DELETE CASCADE, user_id UUID FK→auth_users ON DELETE RESTRICT, PRIMARY KEY (team_id, user_id))`; `team_machines(team_id UUID FK→teams ON DELETE CASCADE, machine_id UUID FK→machines ON DELETE RESTRICT, PRIMARY KEY (team_id, machine_id))`; audits entity_type CHECK drop/re-add adding `TEAM` (V31/V38/V42 pattern). Highest existing migration is V42 (story 9-1).
- Role gate is Phase 1: **SUPER_ADMIN or MANAGE** for ALL team mutations (create/update/delete + member/machine link management). No plant-access gating — teams are cross-plant by definition and FR-105 grants both roles global management. `requireMutationRole` house pattern. VIEWER → 403 `FORBIDDEN`.
- Team CRUD: `POST /api/v1/teams` (201) `{name, expiresAt}`, `GET /api/v1/teams` (list all, sorted by name), `GET /api/v1/teams/{id}` (detail with members[] + machines[]), `PUT /api/v1/teams/{id}` `{name, expiresAt}`, `DELETE /api/v1/teams/{id}` (204, cascade members/machines). `expiresAt` must be strictly after `Instant.now(clock)` at mutation time — `TEAM_EXPIRY_IN_PAST` (400). `expiresAt` is mutable (extend or shorten but must remain future). Name uniqueness is case-insensitive — `DUPLICATE_TEAM_NAME` (400).
- Member links: `POST /api/v1/teams/{id}/members` `{userId}` (204 idempotent); `DELETE /api/v1/teams/{id}/members/{userId}` (204 idempotent). Unknown user → `USER_NOT_FOUND` (404). Member add/remove audit-logged as TEAM UPDATE with before/after member-count arrays.
- Machine links: `POST /api/v1/teams/{id}/machines` `{machineId}` (204 idempotent); `DELETE /api/v1/teams/{id}/machines/{machineId}` (204 idempotent). Unknown machine → `MACHINE_NOT_FOUND` (404). Machines' active status is NOT checked (repairs happen on stopped machines).
- Audit: TEAM CREATE/UPDATE/DELETE with authenticated actor, entity_id, entity_label=name, plantId=null (audit_log.plant_id is nullable — V16 schema). Member/machine link changes are TEAM UPDATE audits with `previousValue`/`newValue` maps containing `memberCount`/`machineCount` and `action` hint (`memberAdded`/`memberRemoved`/`machineLinked`/`machineUnlinked`). **traceId interpretation:** same as 9-1 Spec Change Log — Phase 1 audit model has no per-request traceId; team audits follow the actor-correlated pattern. A global request-traceId-in-audit is a cross-cutting concern, NOT owned by 9.2.
- `OperationalScopeService.derive` gains active team scope via a new port `TeamMachineGroupReader` (org module defines port; machine module implements adapter). Derivation: resolve team-machines for user's active teams → distinct machine group ids → populate `activeTeamIds`. Machine-group ids from `machine_responsibilities` (level ≥ LEADER) stay in `machineGroupIds` (9-1 unchanged). **Both fields are nullable in SQL params** — consumers pass `null` when empty.
- Read-path predicate extension (FR-105, AD-2, AD-13): for non-SUPER_ADMIN, the existing `findAllScoped`/`findByIdWithDetailsScopedToPlants` queries wrap ONLY their scope conditions (plant-scope AND group-scope) in an additive OR-branch. The single-filter clauses (`:machineId`, `:plantId`, `:status`, `:search`, pagination) stay unchanged and ANDed outside. JPQL shape (alert list):
  ```sql
  where (
    (plant.id in :plantIds
     and (:leaderGroupIds is null or machineGroup.id in :leaderGroupIds))
    or (:teamGroupIds is not null and machineGroup.id in :teamGroupIds)
  )
    and (:machineId is null or machine.id = :machineId)
    and (:plantId is null or plant.id = :plantId)
    and (:status is null or alert.status = :status)
  ```
  Semantics: base view = (assigned plants ∩ leader-groups-if-any) EXACTLY as 9-1; plus any row whose group ∈ team groups regardless of plant. Non-leader without team → `leaderGroupIds=null, teamGroupIds=null` → base view only (Phase 1 plant-scope, no group restriction). Non-leader WITH team → base view unrestricted-by-group + team-group rows cross-plant. Leader without team → leader-scoped within plants (9-1 unchanged). Leader WITH team → leader-scoped base ∪ team-group rows. SUPER_ADMIN keeps the unscoped path unchanged. `leaderGroupIds`/`teamGroupIds` = null when `derive().machineGroupIds()`/`activeTeamIds()` is empty. The same wrap applies to `MachineRepository.findAllScoped` (which additionally has `:machineGroupId` single filter — keep it outside the OR-branch).
- **Machine detail access gate (consistency):** `MachineService.get`/`getByCode` currently gate non-SUPER_ADMIN on `plantScopes.requirePlantAccess` only (9-1 did NOT group-filter machine detail). With teams, an out-of-plant machine appears in a team member's list but a 403 on its detail would be inconsistent with "members gain scoped access to the specified machines across plants". Extend `findScoped`: a non-SUPER_ADMIN may access the machine when it is in an assigned plant OR when `machine.getMachineGroup().getId() ∈ derive().activeTeamIds()`. This is an ADDITIVE relaxation — leader-group access and SUPER_ADMIN behavior unchanged.
- Expiry is evaluated lazily at derive time using `Instant.now(clock)` — no scheduled job, no policy redeploy, no `active` stored column. `Team` entity has no `active` field; `TeamView` includes a computed `boolean active = expiresAt.isAfter(now)`.
- Error codes (standard `ErrorResponse`): `TEAM_NOT_FOUND` 404, `DUPLICATE_TEAM_NAME` 400, `TEAM_EXPIRY_IN_PAST` 400, `USER_NOT_FOUND` 404, `MACHINE_NOT_FOUND` 404, `FORBIDDEN` 403, `TEAM_DATA_INTEGRITY` 400.
- Pilot seed: **no change**. Teams are admin-created runtime config, not canonical master data. No existing team seed needed.
- Frontend: teams CRUD page (mirror `section-management.tsx` with table/list of name + expiresAt + computed ACTIVE/EXPIRED badge, create/edit dialog, delete with confirm), member management dialog (user select via `GET /api/v1/auth/users`), machine management dialog (machine select via `useListMachines` filtering by plant), sidebar entry under Master Data, regenerated client. `GET /api/v1/auth/users` already exists and requires authentication only (no role gate — `SecurityConfig` authenticates all `/api/v1/**` except health/login; method security is annotation-based and `listUsers` has none). Only SUPER_ADMIN/MANAGE manage teams, so the picker is never reached by lower roles.

**Block If:** Nothing requires human input. Pinned: Phase 1 role gate (MANAGE) until 9.4; teams target machines → resolved to groups at derive time (AD-13 "team machine IDs merged into machineGroupIds"); `activeTeamIds` = machine-group ids contributed by active teams (per 9.1 record javadoc); member-add-existing = 204 idempotent; member-remove-missing = 204 idempotent; machine-link-active-status not checked; audit traceId actor-correlated per existing Phase 1 model; no pilot seed change.

**Never:**
- Never build OPA evaluation (9.3), role taxonomy (9.4), or OPA enforcement (9.5) here.
- Never treat `activeTeamIds` as real team IDs in this story — the 9.3 `PolicyDecisionPoint` will assemble `subject.activeTeamIds` (actual team IDs) and `subject.machineGroupIds` (leader ∪ team groups) separately when it lands.
- Never break Phase 1 non-leader behavior (both leaderGroupIds and teamGroupIds null → no group restriction).
- Never change leader-group semantics (9.1's `machineGroupIds` stays leader-only; `activeTeamIds` additive).
- Never break SUPER_ADMIN unscoped behavior.
- Never hard-delete team members or machines individually (DELETE cascade on team deletion handles bulk; member/machine sub-resources are individual).
- Never hand-edit `src/lib/api/generated/**`.
- Never add a scheduled job for expiry; expiry is lazy per derive.
- Never add a `active` stored column — it is computed from `expires_at` + `Clock.now()`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| TEAM_CREATE | MANAGE creates team {name, expiresAt=+30d} | 201 + persisted + TEAM CREATE audit | No error |
| DUPLICATE_NAME | same name (case-insensitive) exists | Rejected | 400 `DUPLICATE_TEAM_NAME` |
| EXPIRY_PAST | expiresAt = yesterday | Rejected | 400 `TEAM_EXPIRY_IN_PAST` |
| TEAM_UPDATE | rename + extend expiry | 200 + persisted + TEAM UPDATE audit | No error |
| SHORTEN_EXPIRY | expiresAt shortened but still future | 200 | No error |
| TEAM_DELETE | delete team with members+machines | 204 cascade; audit snapshot before delete | No error |
| VIEWER_GATE | VIEWER calls any team mutation | Rejected | 403 `FORBIDDEN` |
| MEMBER_ADD | add existing user to team | 204, member persisted | No error |
| MEMBER_ADD_DUP | same user added again | 204 idempotent | No error |
| MEMBER_REMOVE | remove existing member | 204, member gone | No error |
| MEMBER_REMOVE_MISSING | remove non-member | 204 idempotent | No error |
| MEMBER_UNKNOWN_USER | userId doesn't exist | Rejected | 404 `USER_NOT_FOUND` |
| MACHINE_LINK | link machine to team | 204, machine persisted | No error |
| MACHINE_LINK_DUP | same machine linked again | 204 idempotent | No error |
| MACHINE_UNLINK | unlink existing machine | 204, machine removed | No error |
| MACHINE_UNKNOWN | machineId doesn't exist | Rejected | 404 `MACHINE_NOT_FOUND` |
| SCOPE_ACTIVE_TEAM | technician user, team targets machine M in plant P2 group G2, team active | derive.activeTeamIds contains G2; alert/machine list includes G2 rows from P2; base view unchanged | No error |
| MACHINE_DETAIL_TEAM_GRANT | same technician opens machine M (P2) detail | 200 (group ∈ teamGroupIds bypasses plant gate) | No error |
| MACHINE_DETAIL_NOT_GRANTED | non-member user opens a machine outside their plant | Rejected | 403 `FORBIDDEN` |
| SCOPE_EXPIRED_TEAM | same but clock past expiresAt | derive.activeTeamIds empty; team rows excluded from read paths | No error |
| SCOPE_MEMBER_REMOVED | member removed from team | next derive → activeTeamIds empty immediately | No error |
| SCOPE_LEADER_UNION | leader on group G1, team targets G2 | leader sees G1 ∪ G2 rows; G2 rows visible even across plant boundary | No error |
| SCOPE_NO_TEAM | user with no teams | derive.activeTeamIds empty; read paths 100% unchanged (Phase 1/9-1 behavior) | No error |
| SUPER_ADMIN | SUPER_ADMIN lists teams | unrestricted list; no mutation gate bypass needed | No error |
| LIST_TEAMS | GET /api/v1/teams | all teams; each view has computed `active` flag | No error |
| TEAM_DETAIL | GET /api/v1/teams/{id} | team + members[] + machines[] | 404 `TEAM_NOT_FOUND` |
| AUDIT_TRAIL | team create/update/delete + member add/remove + machine link/unlink | audit rows with actor, action, entity type TEAM, entity id, entity label, plantId=null, before/after snapshot | No error |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` unless noted):**
- `resources/db/migration/V43__create_cross_plant_teams.sql` -- NEW -- teams table + team_members + team_machines + audit `entity_type` CHECK re-add with `TEAM`.
- `audit/domain/AuditEntityType.java` -- MODIFY -- add `TEAM` enum value.
- `org/domain/TeamStatus.java` -- NOT CREATED -- active is computed, never stored.
- `org/infrastructure/TeamEntity.java` -- NEW -- entity (id, name, expiresAt, @Version, timestamps). No `active` field.
- `org/infrastructure/TeamMemberEntity.java` -- NEW -- composite PK (teamId, userId), FK→teams CASCADE, FK→auth_users RESTRICT. Needs `@Embeddable` composite PK or `@IdClass`.
- `org/infrastructure/TeamMachineEntity.java` -- NEW -- composite PK (teamId, machineId), FK→teams CASCADE, FK→machines RESTRICT.
- `org/infrastructure/TeamRepository.java` -- NEW -- `findAllByOrderByNameAsc()`, `findByNameIgnoreCase(name)`, `existsByNameIgnoreCase(name)`, `findActiveTeamMachineIdsByUserId(UUID userId, Instant now)` native query (joins team_members ⋈ teams ⋈ team_machines WHERE expires_at > :now AND member = :userId).
- `org/application/TeamService.java` -- NEW -- create/update/delete/list/get; `requireMutationRole` (SUPER_ADMIN|MANAGE); member/machine link add/remove; `expiresAt` future check; name uniqueness; audit via `AuditLogWriter.record` (TEAM CREATE/UPDATE/DELETE); member/machine ops audit as TEAM UPDATE with action-hint maps.
- `org/application/TeamAuditValues.java` -- NEW -- snapshot mapper (name/expiresAt/memberCount/machineCount).
- `org/application/TeamDtos.java` + `TeamController.java` + `TeamExceptionHandler.java` -- NEW -- `/api/v1/teams`; codes per matrix.
- `org/application/TeamMachineGroupReader.java` -- NEW -- port `Set<UUID> resolveMachineGroupIds(Collection<UUID> machineIds)`.
- `org/application/OperationalScopeService.java` -- MODIFY -- inject `TeamRepository` + `TeamMachineGroupReader`; populate `activeTeamIds` = machine-group ids from user's active-teams' target machines.
- `machine/infrastructure/MachineGroupTeamAdapter.java` -- NEW -- implements `TeamMachineGroupReader` via `MachineRepository.findDistinctMachineGroupIdsByMachineIdIn`.
- `machine/infrastructure/MachineRepository.java` -- MODIFY -- add `@Query findDistinctMachineGroupIdsByMachineIdIn(Collection<UUID> machineIds)` returning `Set<UUID>`.
- `alert/application/SparepartAlertQueryService.java` -- MODIFY -- change `scopedMachineGroupIds` helper to return two params: `leaderGroupIds` (from `derive().machineGroupIds()`) + `teamGroupIds` (from `derive().activeTeamIds()`). Pass both to repo methods.
- `alert/infrastructure/SparepartAlertRepository.java` -- MODIFY -- `findAllScoped` and `findByIdWithDetailsScopedToPlants`: change `@Param("machineGroupIds")` to `@Param("leaderGroupIds")` + `@Param("teamGroupIds")`; add OR-branch predicate.
- `machine/application/MachineService.java` -- MODIFY -- same splitting as alert query service (pass leader + team params); `findScoped`/`get`/`getByCode` allow machine detail when machine group ∈ `derive().activeTeamIds()` (additive plant-gate relaxation).
- `machine/infrastructure/MachineRepository.java` -- MODIFY -- `findAllScoped` same OR-branch predicate.
- Tests: NEW `org/application/TeamServiceIntegrationTest.java` (role/name/expiry/member/machine/audit), NEW `org/api/TeamControllerTest.java` (WebMvc status+codes), NEW `org/application/OperationalScopeTeamScopeIntegrationTest.java` (active-team scope, expired-team exclusion, member-removal immediate, leader+team union, no-team regression), NEW `alert/.../SparepartAlertQueryServiceTeamScopeFilterIntegrationTest.java` (technician cross-plant, leader+team, expired denied, SUPER_ADMIN unscoped), NEW `machine/.../MachineListTeamScopeFilterTest.java` (list cross-plant + detail grant/deny), NEW `db/TeamMigrationTest.java` (V43 from empty+prior, CHECKs, FKs, cascade), MODIFY `SparepartAlertQueryServiceTest` (adjust `scopedMachineGroupIds` mock to split), MODIFY `MachineServiceTest` (same + detail-grant cases).

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE -- `TeamView`/`TeamRequest`/`TeamMemberView`/`TeamMachineView`/`team` hooks (`useListTeams`, `useCreateTeam`, `useUpdateTeam`, `useDeleteTeam`, `useAddTeamMember`, `useRemoveTeamMember`, `useLinkTeamMachine`, `useUnlinkTeamMachine`), `team` endpoints in generated client.
- `features/master-data/teams/team-management.tsx` -- NEW -- CRUD page (table: name, member count, machine count, expiresAt, status badge ACTIVE/EXPIRED; create/edit dialog; delete confirm; member management dialog with user select from `GET /auth/users`; machine management dialog with machine select + plant filter).
- `features/master-data/teams/team-management.test.tsx` -- NEW -- mock-hook pattern; create/edit/delete/expiry-error/member-add-remove/machine-link/role states.
- `app/(main)/dashboard/master-data/teams/page.tsx` -- NEW -- RoleGuard + page shell.
- `navigation/sidebar/sidebar-items.ts` -- MODIFY -- add "Teams" to Master Data subItems (between "Sections" and "Machine Groups").

## Tasks & Acceptance

**Execution:**
- [x] `V43__create_cross_plant_teams.sql` + `db/TeamMigrationTest` -- schema + audit CHECK + CASCADE + FKs.
- [x] `AuditEntityType` enum add `TEAM`.
- [x] `org` module: `TeamEntity`, `TeamMemberEntity`, `TeamMachineEntity`, `TeamRepository`, `TeamService`, `TeamAuditValues` -- teams CRUD + member/machine links + expiry guard + audit.
- [x] `org/api/*` (Dtos/Controller/ExceptionHandler) -- teams API surface + error codes.
- [x] `org/application/TeamMachineGroupReader` port -- machine-group id resolution.
- [x] `MachineGroupTeamAdapter` + `MachineRepository` group-id query -- port implementation.
- [x] `OperationalScopeService` -- fill activeTeamIds from active-team groups.
- [x] `SparepartAlertQueryService` + `SparepartAlertRepository` -- split leader/team params + OR-branch predicate.
- [x] `MachineService` + `MachineRepository` -- same split + OR-branch + detail-access team grant.
- [x] Backend tests per matrix (integration + WebMvc + scope filtering + migration) -- prove every row.
- [x] Orval regeneration -- typed hooks/models exist.
- [x] Frontend teams page + member/machine dialogs + sidebar + tests -- delivery.
- [x] Verify: Maven targeted suite green; web vitest/tsc/biome green.

**Acceptance Criteria:**

- Given a cross-plant team with an expiry date and members, when the team is active, then members gain scoped access to the specified machines across plants, and the scope is merged into the `machineGroupIds` set used for SQL filtering (AD-2, AD-13). [AC 9.2-1]
- Given a cross-plant team has expired, when a member requests access to the previously shared resource, then access is denied server-side, and no policy redeploy is required to revoke the extra scope. [AC 9.2-2]
- Given a SUPER_ADMIN or MANAGER_MAINTENANCE creates/updates/deletes a team, when the mutation is submitted, then team mutations are audit-logged with actor, action, target, and traceId (Phase 1 actor-correlated model). [AC 9.2-3]

### Review Findings (code review 2026-08-25)

- [x] [Review][Patch] Gate team read endpoints (list/get) behind SUPER_ADMIN|MANAGE — decision 2026-08-25 (Yusuf): option 1, consistent with mutations; closes cross-plant machine-metadata + member-identifier leak to plain VIEWERs (`TeamService.list/get` take no user; controller ignores principal); requires controller pass-through, test inversion of `readsAvailableToViewer`, page RoleGuard drop VIEWER, snapshot response enrichment
- [x] [Review][Patch] Alert act/history paths lack team OR-branch — team member sees a cross-plant alert in list/detail but acknowledge/resolve/notification-history return 404 (both pass `null, null` team scope) [syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java:155] [syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationHistoryQueryService.java:69]
- [x] [Review][Patch] Plant-less member with active team: machine detail granted but alert list returns empty / detail 404 — empty-plantIds guard precedes the team branch [syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertQueryService.java:61]
- [x] [Review][Patch] Members/Machines dialog shows stale lists after add/remove/link/unlink — `invalidateQueries(['/api/v1/teams'])` does not prefix-match orval detail key `['/api/v1/teams/${teamId}']`; invalidate via `getGetTeamQueryKey(teamId)` [syncro/apps/web/src/features/master-data/teams/team-management.tsx:89]
- [x] [Review][Patch] Machine dialog plant filter renders duplicate options (duplicate React keys) for any plant with ≥2 machines and derives plants from the truncated 200-machine page — build filter from `useListPlants` instead [syncro/apps/web/src/features/master-data/teams/team-management.tsx:520]
- [x] [Review][Patch] Concurrent duplicate add/link bypasses idempotency: check-then-insert race hits composite-PK violation with no DIVE catch on link saves → raw 500 instead of 204; same for FK DIVE if referenced row vanishes between `existsById` and flush [syncro/apps/backend/src/main/java/com/syncro/org/application/TeamService.java:149]
- [x] [Review][Patch] Frontend tests missing promised edit-dialog flow and role-state (read-only/View-only) coverage from Code Map claim [syncro/apps/web/src/features/master-data/teams/team-management.test.tsx]
- [x] [Review][Patch] Copy-paste duplicated assertion block in list test (SVC-019) [syncro/apps/backend/src/test/java/com/syncro/org/application/TeamServiceIntegrationTest.java:201]
- [x] [Review][Patch] VIEWER_GATE negative proven only for create; matrix row says "VIEWER calls any team mutation" — parametrize across all seven mutations [syncro/apps/backend/src/test/java/com/syncro/org/application/TeamServiceIntegrationTest.java:78]
- [x] [Review][Patch] Expired-team machine-detail denial has no direct integration test (only mocked unit + derive-level proof) — add expire-then-get-denied case [syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineListTeamScopeFilterTest.java]
- [x] [Review][Patch] Injected OpenAPI team paths declare only success responses while controllers document 400/401/403/404 via @ApiResponses — enrich snapshot to avoid drift on next live `generate:snapshot` [syncro/apps/web/openapi.json]
- [x] [Review][Patch] Stale `selectedMachineId` survives plant-filter change — Link can target an invisible machine; reset selection on filter change [syncro/apps/web/src/features/master-data/teams/team-management.tsx:517]
- [x] [Review][Patch] Edit dialog truncates stored expiry seconds/millis (`slice(0,16)`); saving an untouched name silently rewrites expiry up to 59s earlier — use second precision [syncro/apps/web/src/features/master-data/teams/team-management.tsx:71]
- [x] [Review][Patch] No-op removeMember/unlinkMachine still writes an immutable TEAM UPDATE audit row — skip audit when before==after counts prove no change [syncro/apps/backend/src/main/java/com/syncro/org/application/TeamService.java:164]
- [x] [Review][Patch] Member/machine pickers render bare empty popover with no guidance when everything is already added [syncro/apps/web/src/features/master-data/teams/team-management.tsx:449]
- [x] [Review][Defer] Machine-link picker hard-capped at `limit: 200` — machines beyond page one unlinkable via UI; proper fix is server-side search/pagination picker (pattern shared with existing master-data dialogs) [syncro/apps/web/src/features/master-data/teams/team-management.tsx:504] — deferred, limitation accepted for v1
- [x] [Review][Defer] Optimistic-lock (`@Version`) conflicts on team update/delete surface as raw 500 — handler mapping is a systemic gap across modules (sections/machines have none either); address globally later [syncro/apps/backend/src/main/java/com/syncro/org/api/TeamExceptionHandler.java] — deferred, systemic
- [x] [Review][Defer] Canonical combined Maven verification command is flaky due to pre-existing Testcontainers/Spring-context-caching quirk; classes pass individually [Verification section] — deferred, infra

## Spec Change Log

- 2026-08-25: Pinned interpretation for AC phrase "scope merged into the machineGroupIds set used for SQL filtering" — since team target machines may be in a different plant than the user's assignments, merging alone into the existing plant-filtered predicate would exclude cross-plant rows. An additive OR-branch `(:teamGroupIds is not null and machineGroup.id in :teamGroupIds)` bypasses the plant filter, preserving the "grant extra access" semantics without regressing base (plant-scoped) visibility. `activeTeamIds` in the `OperationalScope` record holds machine-group ids contributed by active teams (per 9.1 record javadoc). The 9.3 `PolicyDecisionPoint` will assemble `subject.activeTeamIds` (real team IDs) separately.
- 2026-08-25: Pinned interpretation for AC phrase "audit-logged with actor, action, target, and traceId" — same as 9.1 Spec Change Log: Phase 1 audit model has no per-request traceId; team audits follow the actor-correlated pattern identical to all Phase 1 mutation audits. A global request-traceId-in-audit is a cross-cutting concern deferred.

## Review Triage Log

<!-- Append-only. Populated by step-04 on EVERY review pass. -->

## Dev Agent Record

### Implementation Plan

Followed the Code Map exactly. Backend-first: V43 migration + audit CHECK re-add (V31/V38/V42 drop/re-add pattern), then the `org` module vertical slice (entities → repositories → service → API), then the scope plumbing (port `TeamMachineGroupReader` in org, adapter `MachineGroupTeamAdapter` in machine, `OperationalScopeService.derive` fills `activeTeamIds` via lazy expiry against injected `Clock`), then the read-path OR-branch split (`leaderGroupIds`/`teamGroupIds`) in alert list/detail and machine list plus the machine-detail additive team grant.

### Debug Log

- First combined Maven run of 4 integration classes hit connection-refused after the shell timeout killed a previous run mid-flight; each integration class passes when run individually (pre-existing Testcontainers/Spring-context-caching quirk: the shared static container restarts on a new mapped port per class while the cached Spring context keeps the first port — unrelated to this story's code).
- `TransientPropertyValueException` on team delete: JPA must not flush the team delete while link rows still reference it — fixed by explicitly deleting member/machine links before the team delete inside the same transaction (DB FK CASCADE alone is invisible to JPA).
- SUPER_ADMIN alert-list NPE: `scopedGroupParams(user)` was computed unconditionally; made it conditional on `!superAdmin`.
- Frontend: Radix Select interaction in jsdom required `hasPointerCapture`/`setPointerCapture`/`releasePointerCapture`/`scrollIntoView` polyfills in `vitest.setup.ts`; expiry-error test rejects with a real `SyncroApiError` so the component's `errorResponse` branch is exercised.
- Orval regeneration was driven from the committed `openapi.json` snapshot (teams paths/schemas injected mechanically mirroring the sections contract) because the live backend stack was not running; `src/lib/api/generated/**` stays untouched-by-hand and reproducible from the snapshot.
- One pre-existing flaky timeout (`sparepart-management.test.tsx` "sends PATCH after PUT...") under full-suite parallel load; passes 14/14 in isolation — not touched by this story.

### Completion Notes

- **V43** creates `teams` (name-unique case-insensitive via `uq_teams_lower_name`, `ck_teams_name_not_blank`, no stored `active`), `team_members` (PK (team_id,user_id), FK→teams CASCADE / FK→auth_users RESTRICT), `team_machines` (PK (team_id,machine_id), FK→teams CASCADE / FK→machines RESTRICT), and re-adds the `audit_log.entity_type` CHECK with `TEAM`. Proven by `TeamMigrationTest` (columns/nullability, unique index, blank-name CHECK, cascade/restrict semantics, TEAM accepted + unknown rejected).
- **Teams CRUD + links**: `TeamService` gates ALL mutations on `SUPER_ADMIN|MANAGE` (`requireMutationRole`, VIEWER → 403); reads un-gated (picker only reachable by managers in UI, mirrors `listUsers`). Name uniqueness case-insensitive → `DUPLICATE_TEAM_NAME`; expiry strictly future at mutation time vs injected `Clock` → `TEAM_EXPIRY_IN_PAST`; member/machine add/remove idempotent (204) with unknown user/machine → 404; all mutations audit-logged as TEAM CREATE/UPDATE/DELETE (actor-correlated Phase 1 model, `plantId=null`) and link changes as TEAM UPDATE with action-hint maps (`memberAdded`/`memberRemoved`/`machineLinked`/`machineUnlinked` + memberCount/machineCount before/after).
- **Scope derivation**: `OperationalScopeService.derive` now resolves the user's active-team target machines → distinct machine-group ids via the new `TeamMachineGroupReader` port (machine-module adapter over `MachineRepository.findDistinctMachineGroupIdsByMachineIdIn`). Expiry is evaluated lazily per derive (`expires_at > now(clock)`) — no job, no redeploy, no stored column. Leader `machineGroupIds` (9-1) unchanged; `activeTeamIds` additive.
- **Read-path OR-branch** exactly per spec shape in `SparepartAlertRepository.findAllScoped`/`findByIdWithDetailsScopedToPlants` and `MachineRepository.findAllScoped` (single filters ANDed outside): base plant view unchanged, plus any row whose group ∈ team groups regardless of plant. Callers passing the old 3-arg detail signature (`SparepartAlertCommandService`, `NotificationHistoryQueryService`) updated to pass `null, null` (no behavior change for them).
- **Machine detail grant**: non-SUPER_ADMIN may open a machine whose group ∈ `activeTeamIds` even out-of-plant (additive relaxation in `findScoped`/`getByCode`); non-member out-of-plant access still 403.
- **Frontend**: teams CRUD page mirroring section-management patterns (table with computed ACTIVE/EXPIRED badge, create/edit dialog, delete confirm, member dialog with `useListUsers` picker, machine dialog with `useListMachines` + plant filter), sidebar "Teams" entry between Sections and Machine Groups, RoleGuard page shell, regenerated orval client from the committed snapshot. vitest.setup gains jsdom pointer-capture/scrollIntoView polyfills needed by any Radix Select test.
- **AC evidence mapping**: AC 9.2-1 → `OperationalScopeTeamScopeIntegrationTest` (active team adds cross-plant group), `SparepartAlertQueryServiceTeamScopeFilterIntegrationTest#technicianSeesBasePlusTeamAlerts`, `MachineListTeamScopeFilterTest#teamMemberSeesCrossPlantMachine`; AC 9.2-2 → `#expiredTeamExcluded` (derive + alert list + machine list, server-side, no redeploy); AC 9.2-3 → `TeamServiceIntegrationTest` audit rows (CREATE/UPDATE/DELETE + link action hints, actor, null plantId) and `TeamMigrationTest#auditEntityTypeAcceptsTeam`.

## File List

Backend main:
- syncro/apps/backend/src/main/resources/db/migration/V43__create_cross_plant_teams.sql
- syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java
- syncro/apps/backend/src/main/java/com/syncro/org/application/OperationalScopeService.java
- syncro/apps/backend/src/main/java/com/syncro/org/application/TeamMachineGroupReader.java
- syncro/apps/backend/src/main/java/com/syncro/org/application/TeamService.java
- syncro/apps/backend/src/main/java/com/syncro/org/application/TeamAuditValues.java
- syncro/apps/backend/src/main/java/com/syncro/org/api/TeamDtos.java
- syncro/apps/backend/src/main/java/com/syncro/org/api/TeamController.java
- syncro/apps/backend/src/main/java/com/syncro/org/api/TeamExceptionHandler.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamEntity.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMemberId.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMemberEntity.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMemberRepository.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMachineId.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMachineEntity.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamMachineRepository.java
- syncro/apps/backend/src/main/java/com/syncro/org/infrastructure/TeamRepository.java
- syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineGroupTeamAdapter.java
- syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java
- syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java
- syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertQueryService.java
- syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java
- syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertRepository.java
- syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationHistoryQueryService.java

Backend tests:
- syncro/apps/backend/src/test/java/com/syncro/db/TeamMigrationTest.java
- syncro/apps/backend/src/test/java/com/syncro/org/application/TeamServiceIntegrationTest.java
- syncro/apps/backend/src/test/java/com/syncro/org/application/OperationalScopeTeamScopeIntegrationTest.java
- syncro/apps/backend/src/test/java/com/syncro/org/api/TeamControllerTest.java
- syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertQueryServiceTeamScopeFilterIntegrationTest.java
- syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertQueryServiceTest.java
- syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertCommandServiceTest.java
- syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineListTeamScopeFilterTest.java
- syncro/apps/backend/src/test/java/com/syncro/machine/application/MachineServiceTest.java

Frontend:
- syncro/apps/web/openapi.json
- syncro/apps/web/vitest.setup.ts
- syncro/apps/web/src/navigation/sidebar/sidebar-items.ts
- syncro/apps/web/src/app/(main)/dashboard/master-data/teams/page.tsx
- syncro/apps/web/src/features/master-data/teams/team-management.tsx
- syncro/apps/web/src/features/master-data/teams/team-management.test.tsx
- syncro/apps/web/src/lib/api/generated/** (regenerated via `npm run generate:api`; gitignored build output reproduced from the committed snapshot)

Story artifacts:
- _bmad-output/implementation-artifacts/spec-9-2-cross-plant-teams.md
- _bmad-output/implementation-artifacts/sprint-status.yaml

## Change Log

- 2026-08-25: Story 9-2 implemented — V43 cross-plant teams schema (+audit TEAM entity type), org-module teams CRUD/member/machine links with Phase 1 MANAGE gate and actor-correlated audits, `activeTeamIds` derivation via `TeamMachineGroupReader` port with lazy Clock-based expiry, additive leader/team OR-branch split on alert and machine scoped queries, machine-detail team grant, full backend test matrix, frontend teams page + dialogs + sidebar + tests, orval client regenerated from committed OpenAPI snapshot.
- 2026-08-25: Code review (Blind Hunter + Edge Case Hunter + Acceptance Auditor) — 18 findings triaged: 15 patched, 3 deferred (DW-125..127), 6 dismissed. Key fixes: team read endpoints gated SUPER_ADMIN|MANAGE (review decision), alert act/history paths widened with the additive team branch (no more view/act 404 asymmetry), plant-less members keep the team branch on alert paths, dialog detail-query invalidation via `getGetTeamQueryKey`, plant filter sourced from `useListPlants` (dedup + untruncated), idempotent link races absorb composite-PK violations, no-op link ops skip audit rows, second-precision expiry editor, picker empty states, VIEWER_GATE proven across all nine endpoints, expired-team machine-detail denial integration test, OpenAPI snapshot enriched with controller-documented error responses.

## Design Notes

- **Why teams target machines, not machine groups (AD-13):** FR-105 says "scoped access to specified machines/workorders across plants". AD-13 literally says "Cross-plant team machine IDs are merged into the machineGroupIds scope". Resolving machines → their machine-group ids at derive time gives the exact granularity the architecture prescribes, and the coarsening to group-level SQL filtering is an accepted AD-13 outcome.
- **Why additive OR-branch instead of simple union into machineGroupIds:** A non-leader technician on a team targeting machine M (group G2) in plant P2 has base visibility of plant P1 (their assignment) with no group restriction. Simple union `machineGroupIds = leaderGroups ∪ teamGroups` = {G2}. Since `machineGroupIds` being non-empty restricts to only those groups, this would narrow the technician's base view to only G2 rows — losing their unrestricted-in-plant view. This contradicts "teams grant extra access without removing". The OR-branch preserves base visibility exactly.
- **Why lazy expiry evaluation (no scheduled job):** FR-163: "Expired cross-plant team membership yields no extra scope — org changes take effect without policy redeploy (scope passed as input)." Lazy derive-per-request with `Clock` matches this exactly. No worker, no redeploy, no `active` stored column.
- **Why no owning plant on teams:** A cross-plant team by definition spans plants. FR-105 grants SUPER_ADMIN / MANAGER_MAINTENANCE global management without plant qualifier. Audit records use `plantId=null` (V16 `audit_log.plant_id` is nullable).
- **Why no pilot seed change:** Teams are runtime admin config, not canonical master data. Adding a team would create unnecessary PilotSeedTest churn without validating any acceptance criterion.
- **Golden example:** Plant GM1 has sections MACHINERY/UTILITY/WORKSHOP and group Forming. Plant SM2 (Sinar Mas 2) has group Packaging. A cross-plant team "Forming Help" with expiry 2026-09-30 targets machine SM2's Packaging machine. User `technician.sm1` (assigned to GM1, no LEADER responsibility) is added as a member. While the team is active, `OperationalScopeService.derive` returns `activeTeamIds={PackagingGroupId}`. Alert list returns Forming alerts (unrestricted plant scope) PLUS Packaging alerts (team group cross-plant). After 2026-09-30, `derive` returns `activeTeamIds=Set.of()` → Packaging alerts excluded. Remove the member → immediately excluded regardless of expiry.
- **Why `activeTeamIds` holds group ids, not actual team IDs:** The 9.1 `OperationalScope` record javadoc explicitly documents `@param activeTeamIds cross-plant team machine-group ids; empty until story 9.2`. When 9.3's `PolicyDecisionPoint` needs `subject.activeTeamIds` (real team IDs) for OPA input, it will call the org module's public contracts separately to resolve the actual team IDs for the user. This is a clean 9.3 concern.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test "-Dtest=TeamServiceIntegrationTest,TeamControllerTest,OperationalScopeTeamScopeIntegrationTest,SparepartAlertQueryServiceTeamScopeFilterIntegrationTest,MachineListTeamScopeFilterTest,TeamMigrationTest,SparepartAlertQueryServiceTest,MachineServiceTest"` -- expected: BUILD SUCCESS (CRUD/gates/expiry/member/machine/scope-union/cross-plant/expired-denied/regression/audit/migration).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: regenerated team models + hooks; suite green.
- `npx tsc --noEmit`; `npx biome check <touched files>` -- expected exit 0 / no new diagnostics.

**Manual checks:**
- Boot stack; as MANAGE create team "Cross Repair" with expiry 30 days; add a technician member; link a machine from another plant; confirm the technician's alert/machine list includes the out-of-plant machine's rows; advance clock or set past expiry → confirm exclusion; remove member → confirm immediate exclusion; verify audit rows with actor, action, entity type TEAM, null plantId.