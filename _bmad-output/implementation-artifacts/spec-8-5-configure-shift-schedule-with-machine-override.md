---
title: 'Configure Shift Schedule with Machine Override'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_commit: 914624b
final_revision: 7b57b9d
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** FR-083/FR-084 (Epic 8, story 8.5) require machine groups to define an operating shift schedule (up to three daily shifts, start/end local wall-clock times, cross-midnight permitted) and machines to optionally override it (machine wins; UI states the inherited source), but no shift domain exists anywhere in the codebase and 8.6 projections depend on a resolved shift configuration.

**Approach:** V40 migration adds two window tables: `machine_group_shift_windows` and `machine_shift_windows` (parallel machine-level override). A new `shiftconfig` module owns storage, gates, resolution, and audit: `PUT|GET /api/v1/machine-groups/{id}/shift-config` and `PUT|GET|DELETE /api/v1/machines/{id}/shift-config`, where machine GET returns the effective config with the resolved source (`MACHINE`/`MACHINE_GROUP`/`NONE`). Mutations require MANAGE/SUPER_ADMIN app role plus `JobScopeService.requireLevelOrAbove(user, "LEADER")` (FR-088) and are audit-logged (FR-089). Frontend adds shared `ShiftConfigEditor` and `InheritedConfigBadge` components wired into the machine-group edit dialog and the Machine Hub.

## Boundaries & Constraints

**Always:**
- Gate order on mutations (group PUT, machine PUT/DELETE): `requireMutationRole` (SUPER_ADMIN or MANAGE, else 403 `FORBIDDEN`) → `jobScopes.requireLevelOrAbove(user, "LEADER")` (else 403 `JOB_SCOPE_REQUIRED` with the standard message) → plant access. Plant access follows the machine/machine-group module house convention: `PlantScopeService.requirePlantAccess(user, plantId)` for non-SUPER_ADMIN (403 `FORBIDDEN`). Denials: no write, no audit.
- Reads (group GET, machine GET) require authentication + plant access but NOT job scope (a viewer may see the resolved calendar).
- Set semantics are replace-all: a `PUT` body is the full ordered shift list; the previous windows for that resource are deleted then re-inserted in one transaction (0–3 windows; an empty list clears the config). Machine `DELETE` clears the override (fallback to group) and is an idempotent 204 when absent.
- Validation (service-owned, house fieldErrors style): at most 3 shifts; each shift has non-null `startTime`/`endTime` with `startTime != endTime` (zero-length window is invalid); `endTime < startTime` is a legal cross-midnight window, not an error. Overlap between shifts is NOT validated (out of scope until 8.6 math). Violations → 400 `VALIDATION_ERROR` with fieldErrors.
- Shift numbers are assigned by list order (index + 1) server-side; the client sends an ordered array `{ startTime, endTime }` and never supplies shift numbers. Response windows carry `shiftNumber`.
- Time representation: plant-local wall clock, Java `LocalTime`, wire format `"HH:mm"` (24h). No timezone conversion anywhere in this story — the plant-local timezone boundary (default `Asia/Jakarta`) belongs to 8.6's `OperatingCalendarCalculator`; this story stores and returns wall-clock times verbatim.
- Resolution precedence is machine override wins, else machine group config applies, else `NONE`. Machine shift-config GET returns `{ source, inheritedFromGroup, shifts }`; group shift-config GET returns `{ shifts }` only.
- Audit on every mutation: group set → `AuditAction.CREATE` on first config / `UPDATE` on replace / `DELETE` when cleared via empty list; machine override set → `CREATE`/`UPDATE`, override clear → `DELETE`; `AuditEntityType.MACHINE_GROUP` or `MACHINE` (both already exist, no new enum), entityId = resource id, label = group name / machine code, plantId = the resource's plant, previous/new snapshots = the window list (ordered maps of shiftNumber/startTime/endTime).
- Frontend follows house idiom: plain `useState` forms, Orval generated hooks, `errorResponse()` (SyncroApiError), `canMutate` role flag, static "Requires job scope LEADER or above." hint, plain controlled state, generated files only touched via regen scripts. `ShiftConfigEditor` uses `<Input type="time">` rows; `InheritedConfigBadge` renders a `Badge` "Inherited from group" when the machine source is `MACHINE_GROUP`.
- Shift config is a new `shiftconfig` module (application + infrastructure + api) so resolution can span the machine and masterdata modules without a circular dependency. No `S3Client`, no telemetry, no projection math in this story.

**Block If:** Nothing requires human input. Pinned: two-window-table relational model (not JSON) matching architecture "shift window rows"; group config cleared via empty `PUT` (no group DELETE endpoint, matching architecture's `PUT /machine-groups/{id}/shift-config` only); machine hub gains an override editor + badge in a new Shift section; `startTime != endTime` is the only window rule (no overlap validation).

**Never:**
- Never embed shift config as JSON in `machine_groups`/`machines` columns (architecture mandates window rows).
- Never put shift entities in the machine or masterdata entity graph such that the machine/machine-group modules must depend on the shiftconfig module; keep the new module downstream.
- Never add a shift-specific `AuditEntityType`; reuse `MACHINE_GROUP`/`MACHINE`.
- Never require job scope for reads; never skip the LEADER gate on mutations; SUPER_ADMIN bypasses job scope exactly as in the sparepart module (documented).
- Never compute operating-time/projection math in this story (that is 8.6); never touch InfluxDB, Redis, or the telemetry path.
- Never hand-edit `src/lib/api/generated/**`; never introduce react-hook-form/Zod or a new time library.
- Never log shift times beyond the window snapshot in audit; no secrets.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SET_GROUP | Group, no config; PUT with 2 windows (one cross-midnight `23:00→06:00`) | 200; windows stored numbered 1..2; audit CREATE | No error |
| REPLACE_GROUP | Group with 3 windows; PUT with 2 | Old rows replaced; audit UPDATE with previous/new snapshots | No error |
| CLEAR_GROUP | Group with config; PUT with empty array | Config cleared; audit DELETE | No error |
| MACHINE_OVERRIDE_WINS | Machine + group config; machine PUT 1 window | Machine GET → `source: MACHINE`, machine windows; audit CREATE | No error |
| MACHINE_FALLBACK | Machine no override + group config | Machine GET → `source: MACHINE_GROUP`, `inheritedFromGroup: true`, group windows | No error |
| NO_CONFIG | No group config, no override | Machine GET → `source: NONE`, shifts `[]` | No error |
| CLEAR_OVERRIDE | Machine with override; DELETE | 204; GET now falls back to group; audit DELETE (no-op DELETE when absent, no audit) | No error |
| FOUR_SHIFTS | PUT with 4 windows | No write | 400 `VALIDATION_ERROR` fieldErrors.shifts |
| ZERO_LENGTH | Window `08:00→08:00` | No write | 400 `VALIDATION_ERROR` fieldErrors.shifts |
| BELOW_LEADER | MANAGE with no ≥LEADER assignment mutates | No write/audit | 403 `JOB_SCOPE_REQUIRED` |
| VIEWER_ROLE | Any mutation | No write | 403 `FORBIDDEN` |
| SUPER_ADMIN_NO_ROWS | SUPER_ADMIN mutates any plant's config | Allowed (documented bypass) | No error |
| WRONG_PLANT | MANAGE+LEADER on a group/machine in an unassigned plant | No write | 403 `FORBIDDEN` (plant-access denial, house module convention) |
| UNKNOWN_ID | PUT/GET/DELETE nonexistent group or machine | Standard | 404 `MACHINE_GROUP_NOT_FOUND` / `MACHINE_NOT_FOUND` |

</intent-contract>

## Code Map

**Backend (`syncro/apps/backend/src/main/java/com/syncro` + resources):**
- `resources/db/migration/V40__create_shift_windows.sql` -- NEW -- `machine_group_shift_windows` (id, machine_group_id FK CASCADE, shift_number SMALLINT CHECK 1..3, start_time TIME, end_time TIME, created_at, updated_at; UNIQUE(machine_group_id, shift_number)) + `machine_shift_windows` (id, machine_id FK CASCADE, same shape); indexes on FK columns.
- `shiftconfig/infrastructure/MachineGroupShiftWindowEntity.java` + `MachineShiftWindowEntity.java` -- NEW -- `@Entity` window rows (LocalTime start/end, shiftNumber, timestamps); `MachineGroupShiftWindowRepository` + `MachineShiftWindowRepository` with `deleteByMachineGroupId`/`deleteByMachineId` and `findAllBy...OrderByShiftNumber`.
- `shiftconfig/application/ShiftConfigService.java` -- NEW -- deps `MachineGroupRepository`, `MachineRepository`, `AuthUserPlantAssignmentRepository`(or `PlantScopeService`), `AuditLogWriter`, `Clock`, `JobScopeService`; methods `getGroupConfig`, `setGroupConfig`, `getMachineConfig` (resolved), `setMachineConfig`, `clearMachineConfig`; private `requireMutationRole`, `findScopedGroup`/`findScopedMachine` (plant access), `validate` (≤3, non-null, start!=end, cross-midnight allowed), window snapshot maps; nested exceptions mirroring the sparepart price-entry/image pattern (Validation/MutationForbidden/NotFound) plus reuse `PlantAccessDeniedException` for scope.
- `shiftconfig/api/ShiftConfigController.java` + `ShiftConfigDtos.java` + `ShiftConfigExceptionHandler.java` -- NEW -- mappings per intent; `@Operation`/`@ApiResponses` with `content = @Content` on every non-2xx (8-2/8-3/8-4 lesson); DTOs `ShiftWindowRequest(startTime, endTime)` with `@JsonFormat(pattern="HH:mm")`, `SetShiftConfigRequest(List<ShiftWindowRequest> shifts)`, `ShiftWindowView(shiftNumber, startTime, endTime)`, `MachineGroupShiftConfigView(shifts)`, `MachineShiftConfigView(source, inheritedFromGroup, shifts)`; handler 400 `VALIDATION_ERROR`, 403 `FORBIDDEN`/`JOB_SCOPE_REQUIRED`, 404 `MACHINE_GROUP_NOT_FOUND`/`MACHINE_NOT_FOUND`.

**Backend tests (`syncro/apps/backend/src/test/java/com/syncro/shiftconfig`):**
- `ShiftConfigServiceIntegrationTest.java` -- NEW -- Testcontainers PostgreSQL; `persistedUser`/`assign`/`assignJobScope`/`latestAuditEntryFor` patterns (reuse from `SparepartImageServiceIntegrationTest`); cover every matrix row incl. precedence, fallback, clear, validation, denials, SUPER_ADMIN bypass, audit snapshots.
- `ShiftConfigControllerTest.java` -- NEW -- `@WebMvcTest` + `@Import(SecurityConfig, …, JwtAuthenticationFilter, TimeConfig, TestJsonConfig)`; status + `$.code` per matrix incl. the 400 fieldErrors shape and time parsing.

**Frontend (`syncro/apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE (boot backend, `npm run generate:snapshot` + `generate:api`) -- hooks `useGetMachineGroupShiftConfig`, `useUpdateMachineGroupShiftConfig`, `useGetMachineShiftConfig`, `useUpdateMachineShiftConfig`, `useDeleteMachineShiftConfig` + models (`machineGroupShiftConfigView`, `machineShiftConfigView`, `shiftConfigRequest`, `shiftWindow…`).
- `components/syncro/shift-config-editor.tsx` -- NEW -- props `{ value: ShiftWindowInput[]; onChange; error?; readOnly?; disabledReason? }`; ≤3 `<Input type="time">` rows with add/remove, `aria-invalid`/`role="alert"`, LEADER hint; plain controlled state.
- `components/syncro/inherited-config-badge.tsx` -- NEW -- `Badge` "Inherited from group" shown when `source === "MACHINE_GROUP"`.
- `features/master-data/machine-groups/machine-group-management.tsx` -- MODIFY -- edit dialog gains a Shift Configuration section (edit mode only) with `ShiftConfigEditor` bound to GET hook (enabled in edit mode) + PUT hook; invalidate query key on success; `errorResponse()` verbatim denial; `readOnly` when `!canMutate`; static LEADER+ hint.
- `features/machine-hub/machine-hub-page-content.tsx` -- MODIFY -- add a Shift section (Overview tab or new block): resolved config via `useGetMachineShiftConfig` (source + `InheritedConfigBadge`), override editor via `useUpdateMachineShiftConfig`/`useDeleteMachineShiftConfig` (LEADER+), clear button, verbatim denial.
- Tests: `components/syncro/shift-config-editor.test.tsx` NEW; extend `machine-group-management.test.tsx` + `machine-hub-page-content.test.tsx` using the module-level mutable-mock + `vi.mock` clone pattern (`machine-group-management.test.tsx` precedent; hub may need a new test file mirroring `machine-group-management.test.tsx`).

## Tasks & Acceptance

**Execution:**
- [x] `V40__create_shift_windows.sql` + two window entities + repositories -- relational shift storage.
- [x] `ShiftConfigService` set/get/clear/resolve with pinned gates, validation, resolution precedence, audit -- the use case.
- [x] DTOs + controller + exception handler -- API surface with `HH:mm` times and correct OpenAPI error schemas.
- [x] Backend tests (integration + WebMvc) per matrix incl. precedence, fallback, clear, denials, audit.
- [x] Orval regeneration (boot backend; run scripts) + force-add orphaned models -- typed hooks exist.
- [x] `ShiftConfigEditor` + `InheritedConfigBadge` components + machine-group dialog integration + Machine Hub shift section + frontend tests.
- [x] Verify: Maven suite green; `npm run test:unit` + `npx tsc --noEmit` + Biome green (no new diagnostics beyond pre-existing); live evidence: curl group PUT/GET, machine PUT/GET (source MACHINE vs MACHINE_GROUP vs NONE), DELETE fallback, psql window rows, audit rows.

**Acceptance Criteria:**

- Given a machine group exists, when a LEADER+-scoped MANAGE user sets up to three shifts (including a cross-midnight window) via PUT, then the windows persist in `machine_group_shift_windows` numbered by order and the response echoes them; an empty array clears the config. [AC 8.5-1]
- Given a machine belongs to a configured group, when the machine has no override, then machine shift-config GET returns the group's windows with `source: MACHINE_GROUP` and `inheritedFromGroup: true`; when the machine has an override, the machine's windows are returned with `source: MACHINE`; when neither exists, `source: NONE`. [AC 8.5-2]
- Given a machine override exists, when it is cleared via DELETE, then the machine falls back to its group config and the response reflects the inherited source; clearing when absent is an idempotent 204. [AC 8.5-3]
- Given the Machine Hub shows a machine, when it inherits its shift schedule from its group, then an `InheritedConfigBadge` states the group inheritance and the ShiftConfigEditor allows a LEADER+ user to override; VIEWERs and below-LEADER users see a read-only editor with the LEADER hint. [AC 8.5-4]
- Given any shift-config mutation, when performed, then it is audit-logged with actor, action, entity type MACHINE_GROUP or MACHINE, label, plant, and previous/new window snapshots; reads are never audited. [AC 8.5-5]
- Given an invalid payload (four shifts, zero-length window, or missing times), when submitted, then no write occurs and the API returns 400 `VALIDATION_ERROR` with fieldErrors; a below-LEADER or VIEWER user gets 403 `JOB_SCOPE_REQUIRED`/`FORBIDDEN` with no write and no audit. [AC 8.5-6]

## Spec Change Log

- 2026-08-24: Spec created (draft → ready-for-dev → in-progress). Epic 8 context loaded (valid cache, newer than planning docs); story 8.5 identified as "Configure Shift Schedule with Machine Override" (FR-083/FR-084, ACs from epics.md 1285-1302); architecture data-model + API deltas read (1760, 1766, 1775); PRD FR-083/084/088/089 read (207-213). Codebase explored via subagent: MachineGroupEntity/MachineEntity graph, MachineGroupController/Service patterns, MachineController (getMachine operationId, MachineView), JobScopeService + JobScopeForbiddenException (only used by sparepart module so far), AuditEntityType (MACHINE_GROUP + MACHINE exist), latest migration V39, frontend machine-group-management (plain useState + Orval + errorResponse + canMutate) and machine-hub-page-content (useGetMachineByCode, read-only, Badge available). Decisions pinned: two relational window tables; new downstream `shiftconfig` module; empty-PUT clears group config (no group DELETE); `startTime != endTime` only rule (no overlap validation, deferred to 8.6); shift numbers assigned by order; `HH:mm` wire format; gates = app-role → LEADER → plant access (module house 403 convention); audit via MACHINE_GROUP/MACHINE; machine hub gains Shift section with InheritedConfigBadge.

## Review Triage Log

### 2026-08-24 — Review pass (Blind Hunter + Edge Case Hunter, baseline 914624b)
- intent_gap: 0
- bad_spec: 0
- patch: 12: (high 0, medium 4, low 8) -- all fixed in this pass:
  - `[medium]` `[patch]` `{"shifts":null}` / missing key silently cleared the schedule with an audited DELETE; controller now returns 400 VALIDATION_ERROR "Shifts payload is required." (only an empty array clears). API-017 added.
  - `[medium]` `[patch]` A null element inside the shifts array NPE'd to a raw 500 in `toCommands`; null elements now map to empty commands so validation reports them as 400 fieldErrors.
  - `[medium]` `[patch]` UI submitted incomplete rows into Jackson parse failure (opaque MALFORMED_JSON); both dialog and hub now block submit on blank start/end and surface `fieldErrors.shifts` verbatim.
  - `[medium]` `[patch]` Background refetch clobbered in-progress shift edits (dialog + hub) and wiped just-displayed errors; dirty-guard refs skip resync while editing, error no longer cleared by the sync effect.
  - `[low]` `[patch]` Multiple row-level validation messages overwrote each other under one fieldErrors key; messages are now joined ("Shift 1 …; Shift 3 …").
  - `[low]` `[patch]` Concurrent replace-all PUTs surfaced unique-constraint violations as raw 500; `DataIntegrityViolationException` handler returns 409 SHIFT_CONFIG_CONFLICT. API-019 added.
  - `[low]` `[patch]` Enter key inside shift time inputs submitted the parent machine-group form; editor inputs preventDefault Enter.
  - `[low]` `[patch]` Save/Clear buttons allowed interleaved mutations; both disable while either mutation is pending.
  - `[low]` `[patch]` V40 carried two redundant FK indexes duplicating the leading column of the unique constraints; removed pre-commit (live dev DB reset of V40 applied).
  - `[low]` `[patch]` Controller-test gaps closed: group empty-array clear (API-015), machine 400 VALIDATION_ERROR (API-016), VIEWER DELETE 403 (API-018).
  - `[low]` `[patch]` Group schedule saves did not refresh machines' cached resolved configs; predicate-based invalidation of `*/shift-config` queries added on save success.
  - `[low]` `[patch]` Formatter-only biome fix applied to shift-section.tsx after edits.
- defer: 2: (medium 1, low 1)
  - `[medium]` Frontend has no job-scope signal, so below-LEADER MANAGE users see enabled editors until the server's 403 (cross-story limitation since 8-2; needs auth endpoint exposing effective job scope) → deferred-work.md.
  - `[low]` Audit test helpers are O(entire-table) with createdAt-tie nondeterminism → deferred-work.md.
- reject: 10
  - Loose `string` badge type matches generated model (`source?: string`) — premise invalid; DB CHECK for zero-length windows (service is authoritative per spec); ungrouped-machine NPE (machine_group_id NOT NULL); plant-coarse job scope (documented JobScopeService semantics, spec-pinned); generated-client unverifiability (process note; hooks verified via grep/tsc/tests); triplicated constants across SQL/Java/TS (inherent stack split); shared errorResponse refactor (house pattern); split-brain dialog save semantics (spec-prescribed separate endpoints); aria-invalid granularity (cosmetic); unused `disabledReason` prop (house idiom).
- addressed_findings: all 12 patches listed above were fixed and re-verified (backend ShiftConfig* 37/37 green incl. +5 new API tests; frontend 252/252 green; tsc exit 0; biome warnings-only on touched files).

## Design Notes

- **Why two relational window tables, not JSON:** architecture line 1760 explicitly says "shift window rows" and "parallel machine-level override table"; relational rows keep `shift_number` a real constraint (`UNIQUE(machine_group_id, shift_number)`, `CHECK 1..3`) and give 8.6's `OperatingCalendarCalculator` a typed, indexable source. No JSONB.
- **Why a new `shiftconfig` module:** resolution reads `MachineGroupEntity` (masterdata module) and `MachineEntity` (machine module). A downstream module avoids either owning the other; the machine/machine-group modules stay untouched and cycle-free.
- **Why `PUT` with empty array clears a group config (no group DELETE):** the architecture lists only `PUT /machine-groups/{id}/shift-config`; an empty list is the natural "no schedule" state and keeps one write path. Machine override clearing has a dedicated `DELETE` because the override is opt-in on top of inheritance.
- **Why `startTime != endTime` is the only window rule:** a zero-length window is ambiguous (24h vs empty); rejecting it keeps 8.6 math unambiguous. Cross-midnight is `endTime < startTime`; ordering/overlap rules are deliberately deferred to the operating-time math in 8.6.
- **Golden example (machine resolution):** group windows `[1: 07:00–15:00, 2: 23:00–06:00]`, machine override `[1: 09:00–17:00]` → machine GET `{ source: "MACHINE", inheritedFromGroup: false, shifts: [{shiftNumber:1, startTime:"09:00", endTime:"17:00"}] }`. After `DELETE /machines/{id}/shift-config` → `{ source: "MACHINE_GROUP", inheritedFromGroup: true, shifts: [1:07:00–15:00, 2:23:00–06:00] }`.
- **Continuity from 8-2/8-3/8-4:** reuses `JobScopeService.requireLevelOrAbove(user, "LEADER")`, the audit record contract, `errorResponse()`/dialog idioms, and the `@ApiResponses(@Content)` OpenAPI lesson. Machine/machine-group modules already expose `MachineView`/`MachineGroupView` and their controllers; the new endpoints are additive.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=ShiftConfig*,MachineGroup*,Machine*,JobScope*,AuditLogWiringIntegrationTest"` -- expected: BUILD SUCCESS (V40 applies from empty and prior state; gates/audit/resolution proven on Testcontainers PostgreSQL).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: shift hooks generated; unit tests green.
- `npx tsc --noEmit` and `npx biome check <touched files>` -- expected: exit 0 / no new diagnostics.

**Manual checks:**
- Boot stack; login admin; `PUT /machine-groups/{id}/shift-config` with a cross-midnight window → 200; `PUT /machines/{id}/shift-config` → GET shows `source: MACHINE`; DELETE → GET shows `source: MACHINE_GROUP`; psql confirms window rows and audit_log entries with MACHINE_GROUP/MACHINE entity types and window snapshots.

## Auto Run Result

Status: done (final_revision 7b57b9d; baseline 914624b).

**Summary:** Machine groups can now hold up to three daily wall-clock shift windows (cross-midnight permitted) in `machine_group_shift_windows`, machines can override them in `machine_shift_windows` (machine wins), and reads expose the resolved source (`MACHINE`/`MACHINE_GROUP`/`NONE`). Mutations enforce app role → LEADER job scope → plant access server-side and write immutable audit records with window snapshots. UI ships `ShiftConfigEditor` + `InheritedConfigBadge` wired into the machine-group edit dialog and a Machine Hub Shift section with override/clear and verbatim denial surfacing.

**Files changed:** backend — V40 migration, new `syncro/shiftconfig/` module (2 entities, 2 repositories, ShiftConfigService, ShiftConfigController/Dtos/ExceptionHandler) + integration/WebMvc tests (37 tests incl. 19 API-level); frontend — `ShiftConfigEditor`, `InheritedConfigBadge`, hub `ShiftSection`, group-dialog integration, hub page wiring, tests (+5 files, +2 modified), Orval clients regenerated with 5 force-added model files.

**Review findings breakdown:** 12 patches applied (4 medium: null-payload destructive clear now 400; null array element NPE→400; client completeness guard + fieldErrors.shifts surfacing; dirty-guarded resync), 2 deferred (frontend job-scope visibility; audit-test helper scalability), 10 rejected.

**Verification performed:** targeted Maven suite 187/187 green pre-review (Flyway "now at version v40" from empty DB); post-review ShiftConfig* 37/37 green; frontend vitest 252/252, tsc exit 0, biome warnings-only on touched files; live docker-stack round-trip via curl: group PUT cross-midnight windows persisted numbered, machine GET resolved MACHINE_GROUP→override PUT source MACHINE→DELETE 204→fallback re-confirmed, psql window rows + MACHINE_GROUP/MACHINE audit rows with snapshots confirmed.

**Residual risks:** below-LEADER MANAGE users see enabled editors until the server's 403 (deferred, cross-story); concurrent replace is surfaced as retryable 409 rather than serialized; sparepart-management suite showed load-related timeout flakiness once (passes consistently in isolation and full-suite reruns).
