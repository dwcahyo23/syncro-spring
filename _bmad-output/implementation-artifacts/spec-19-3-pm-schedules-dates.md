---
title: 'Story 19-3: PM Schedules & Schedule Dates'
type: 'feature'
created: '2026-09-03'
status: 'done'
review_loop_iteration: 0
baseline_revision: '6202e50'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `pm_schedules`/`pm_schedule_dates` (blueprint F5) exist in V1 with bare 15-2 entities but no service or API — a yearly PM plan cannot be created, its per-date instances materialized, or moved through the SPV → production approval workflow, so 19-1/19-2 content never becomes executable preventive dates.

**Approach:** Add PmScheduleService + REST `/api/v1/pm-schedules` implementing: create a yearly schedule (snapshots checksheet + revision + frequency code/name, materializes the year's schedule dates from the server clock), sequential approval (DRAFT → PENDING_SPV_APPROVAL → PENDING_PRODUCTION_APPROVAL → APPROVED → ACTIVE) with per-approval actor/timestamp audit, and date-status updates (SCHEDULED → EXECUTED/MISSED/RESCHEDULED via explicit transitions). V9 additive migration extends the audit entity_type CHECK with PM_SCHEDULE/PM_SCHEDULE_DATE.

## Boundaries & Constraints

**Always:**
- V9 additive migration only (V1..V8 untouched): extend ck_audit_log_entity_type with PM_SCHEDULE, PM_SCHEDULE_DATE (V6/V7/V8 pattern) + matching AuditEntityType enum values in the same change. No schema changes.
- API `/api/v1/pm-schedules`: POST {plantId,machineId,checksheetId,year} → creates schedule (status DRAFT) + materializes dates; GET list ?year=&status= (scope-filtered); GET /{id} (schedule + dates, scope-filtered); POST /{id}/submit → DRAFT→PENDING_SPV_APPROVAL; POST /{id}/approve-spv → PENDING_SPV_APPROVAL→PENDING_PRODUCTION_APPROVAL; POST /{id}/approve-prod → PENDING_PRODUCTION_APPROVAL→APPROVED; POST /{id}/activate → APPROVED→ACTIVE; POST /{id}/dates/{dateId}/transition {status} → SCHEDULED↔{EXECUTED,MISSED,RESCHEDULED} (RESCHEDULED requires newDate? → NO, status only in this story). camelCase.
- Create validation: plant+machine exist; machine belongs to plant; checksheet exists and is APPROVED (approvedBy != null) and is the ACTIVE pointer's target for (machine, frequency) — else 409 INVALID_CHECKSHEET_STATE; unique (plant, machine, checksheet, year) → 409 SCHEDULE_ALREADY_EXISTS; year 2000-2999.
- Date materialization (from checksheet's effective_date or the current date from Clock, whichever is later — the F5 anchor): frequency MONTHLY → one date per month of the year (day = min(anchor day, days-in-month), PreventiveProgramService.nextAnchor clamping pattern); ANNUAL → one date (anchor month/day in the target year). Duplicate planned_date within a schedule → 400. All dates start SCHEDULED.
- Approvals are strictly sequential: any out-of-order transition → 409 INVALID_SCHEDULE_TRANSITION. Each transition stamps actor + timestamp (submitted_by/submitted_at, approved_by_spv/approved_at_spv, approved_by_prod/approved_at_prod) and writes an audit record with previous/new status. Only APPROVED schedules can be activated; only ACTIVE schedules expose the date-transition endpoint.
- Date transition guards: EXECUTED/MISSED only from SCHEDULED; RESCHEDULED from SCHEDULED or EXECUTED; terminal per date? NO — statuses are repeatable (a rescheduled date can be executed). Invalid transition → 409 INVALID_SCHEDULE_DATE_TRANSITION. Date mutations audit-logged.
- Gates: schedule create/submit by STAFF_MAINTENANCE/SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE with machine plant/group scope (SUPER_ADMIN bypass); approve-spv/approve-prod/activate require a leader role (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE/SUPER_ADMIN); reads scope-filtered like 19-1/19-2.
- Audit: CREATE/UPDATE records with actor + previous/new values (all UUIDs stringified).
- OPA: new pm_schedule_paths {"/api/v1/pm-schedules", "/api/v1/pm-schedules/*", "/api/v1/pm-schedules/*/submit", "/api/v1/pm-schedules/*/approve-spv", "/api/v1/pm-schedules/*/approve-prod", "/api/v1/pm-schedules/*/activate", "/api/v1/pm-schedules/*/dates/*/transition"} with the four-role mutation allow blocks (approve sub-paths leader-only in service, rego coarse fence); update authz.rego + authz_test.rego; add to SYNCRO_AUTHZ_ENFORCED_PATHS in .env.example.
- Errors: VALIDATION_ERROR (400), FORBIDDEN (403), PM_SCHEDULE_NOT_FOUND / PM_CHECKSHEET_NOT_FOUND / MACHINE_NOT_FOUND / PLANT_NOT_FOUND / PM_SCHEDULE_DATE_NOT_FOUND (404), SCHEDULE_ALREADY_EXISTS / INVALID_SCHEDULE_TRANSITION / INVALID_SCHEDULE_DATE_TRANSITION / INVALID_CHECKSHEET_STATE (409).
- warnings JSONB: leave null this story (19-4/19-5 populate); column exists.
- TypeScript strict; no frontend work.

**Block If:**
- No unambiguous anchor date source exists for date materialization (effective_date vs clock) → HALT blocked.

**Never:**
- No edits to V1..V8; no execution surface (19-5); no auto-workorder generation (19-4); no calendar/dashboard endpoints (FR-172 is Epic 14); no scheduler; no deletion of schedules.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create MONTHLY schedule | approved checksheet (MONTHLY, effective 2026-01-15), year 2027 | 201 DRAFT; 12 dates (15th of each month) | unapproved checksheet → 409; duplicate (plant,machine,checksheet,year) → 409 |
| Create ANNUAL schedule | ANNUAL checksheet, year 2027 | 201 DRAFT; 1 date (anchor day/month) | — |
| Submit | DRAFT submit | PENDING_SPV_APPROVAL; submitted_by/at stamped | out-of-order → 409 |
| Approve SPV | PENDING_SPV_APPROVAL | PENDING_PRODUCTION_APPROVAL; stamps | staff → 403; out-of-order → 409 |
| Approve PROD | PENDING_PRODUCTION_APPROVAL | APPROVED; stamps | — |
| Activate | APPROVED | ACTIVE | from non-APPROVED → 409 |
| Date transition | ACTIVE, SCHEDULED date → EXECUTED | status EXECUTED; audit | not ACTIVE → 409; invalid from-state → 409 |
| Out-of-scope | leader of plant A on machine B | 403 mutations, invisible reads | — |
| Duplicate date | materialization collides (impossible for MONTHLY distinct months) | — | 400 if crafted |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1176-1220` -- pm_schedules + pm_schedule_dates DDL + CHECKs + uniques. READ-ONLY.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmScheduleEntity.java`, `PmScheduleDateEntity.java` + repositories -- bare 15-2 entities; add mutators (transition methods, stamp methods) + queries (byPlantMachineChecksheetYear, byScheduleIdOrderByPlannedDateAsc).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PreventiveProgramService.java:196-221` -- nextAnchor/clamp pattern for date materialization.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmChecksheetService.java` + `PmChecklistService.java` -- 19-1/19-2 gate/audit/scope patterns to copy exactly.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/ActiveChecksheetRepository.java` -- verify the checksheet is the active pointer target.
- `syncro/apps/backend/src/main/java/com/syncro/authz/policy/authz.rego` + `authz_test.rego` -- path-set pattern.
- `syncro/.env.example` -- SYNCRO_AUTHZ_ENFORCED_PATHS.
- Tests: `PmChecklistServiceIntegrationTest` / `PmChecksheetServiceIntegrationTest` (fixture pattern), `PmChecklistControllerTest` (MockMvc error-path pattern), `V1BaseSchemaMigrationTest` (migration count → 9).

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmScheduleService.java` (+ commands/views/exceptions).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmScheduleController.java`, DTO records in PreventiveDtos, handlers in PreventiveExceptionHandler.
- `syncro/apps/backend/src/main/resources/db/migration/V9__pm_schedule_audit_types.sql`.
- Tests: `PmScheduleServiceIntegrationTest.java`, `PmScheduleControllerTest.java`.

## Tasks & Acceptance

**Execution:**
- `V9__pm_schedule_audit_types.sql` -- CHECK extend -- audit parity
- `AuditEntityType.java` -- PM_SCHEDULE, PM_SCHEDULE_DATE -- same change as V9
- `PmScheduleEntity/PmScheduleDateEntity` + repositories -- mutators + queries -- service needs
- `PmScheduleService.java` -- create+materialize, transitions, gates, audit -- core
- `PmScheduleController.java` + DTOs + handler -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `.env.example` -- path set + wiring -- OPA parity
- `V1BaseSchemaMigrationTest.java` -- V9 assertions -- migration evidence
- Integration + controller tests -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given an approved active checksheet (MONTHLY), when a schedule is created for a year, then a DRAFT schedule with 12 dates (monthly, clamped) persists with frequency snapshot + audit CREATE.
- Given a DRAFT schedule, when submit → approve-spv → approve-prod → activate, then the statuses advance strictly in order with actor/timestamp stamps and per-step audit UPDATE rows.
- Given any out-of-order transition, then 409 INVALID_SCHEDULE_TRANSITION; given a leader-only approval by STAFF, then 403.
- Given an ACTIVE schedule, when a SCHEDULED date transitions to EXECUTED, then the date updates and is audit-logged; invalid from-state → 409.
- Given an unapproved checksheet or a duplicate schedule, then 409 with the stable codes.
- Given a full per-class test run + OPA suite, then all green.

## Spec Change Log

## Review Triage Log

### Review Findings (bmad-build-auto step-04, 2026-09-03)

- [x] [Review][Patch] `create()` doesn't validate checksheet belongs to command machine — schedule could snapshot a checksheet from another machine [`PmScheduleService.java:create`] — fixed
- [x] [Review][Patch] `create()` doesn't validate frequency.isActive() — schedule can be created against deactivated frequency [`PmScheduleService.java:create`] — fixed
- [x] [Review][Patch] Non-MONTHLY/non-ANNUAL frequency codes silently materialize as one ANNUAL-style date — reject unsupported codes [`PmScheduleService.java:materializeDates`] — fixed
- [x] [Review][Patch] Concurrent approve transitions race (both pass requireState, double audit, last-write-wins) — add guarded conditional update backstop + concurrency test [`PmScheduleService.java:approveSpv/approveProd/activate/submit`] — fixed: findByIdForUpdate PESSIMISTIC_WRITE + PmScheduleTransitionConcurrencyIntegrationTest (4)
- [x] [Review][Patch] activate() audit label has stray "s@" prefix — inconsistent with other transition labels [`PmScheduleService.java:245`] — fixed
- [x] [Review][Patch] saveDate duplicate-key error uses "year" instead of "plannedDate" [`PmScheduleService.java:saveDate`] — fixed
- [x] [Review][Patch] list endpoint OpenAPI declares single ScheduleView schema for List return [`PmScheduleController.java:listPmSchedules`] — fixed
- [x] [Review][Patch] scheduleValues/dateValues copy `warnings` JSONB by reference — audit snapshot can share live reference [`PmScheduleService.java`] — fixed
- [x] [Review][Patch] Positive SCHEDULED→MISSED transition never tested (only rejections) — add test [`PmScheduleServiceIntegrationTest`] — fixed
- [x] [Review][Patch] 19.3-INT-018 ordering assertion flaky — two back-to-back creates can share createdAt microsecond [`PmScheduleServiceIntegrationTest:INT-018`] — fixed
- [x] [Review][Patch] No rego tests for POST /{id}/approve-prod (the one subpath with zero coverage) [`authz_test.rego`] — fixed (+6)
- [x] [Review][Patch] Missing controller tests: year=3000 → 400, status=NOT_A_STATUS → 400 [`PmScheduleControllerTest`] — fixed
- [x] [Review][Defer] N+1 list + unindexed year/status filters — pre-existing 19-1 pattern (DW-149) — deferred
- [x] [Review][Defer] AuthzEnforcementIntegrationTest not extended for pm-schedule paths — pre-existing pattern, DW-147/156 — deferred
- [x] [Review][Defer] No creator-only submit — F5 silent, mixed four-role gate; spec-aligned — deferred
- [x] [Review][Defer] Generic date-transition error message without legal from-states — API consistency gap, shared with 19-1 — deferred

## Design Notes

- Anchor for date materialization: the checksheet's effective_date when present, else LocalDate.now(clock) — the earliest an approved revision can anchor a plan. MONTHLY iterates the 12 months of the target year clamping the anchor day (Feb 29/30/31 short-month handling, PreventiveProgramService.nextAnchor pattern); ANNUAL emits one date at the anchor month/day in the target year.
- Approvals stamp columns per F5 (submitted_by/at, approved_by_spv/at, approved_by_prod/at) — no generic actor columns; the audit record carries previous/new status + actor.
- The transition endpoint is gated on schedule ACTIVE; date statuses are repeatable (RESCHEDULED → EXECUTED allowed) — no terminal state per date.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmScheduleServiceIntegrationTest,PmScheduleControllerTest,V1BaseSchemaMigrationTest` (per class if the shared container dies) -- expected: all pass
- `cd syncro/apps/backend && mvn -q test -Dtest=PmChecksheetServiceIntegrationTest,PmChecklistServiceIntegrationTest` -- expected: 19-1/19-2 regression green
- OPA via `docker run --rm -v <authz>/policy:/policy openpolicyagent/opa:1.19.1-debug test /policy` -- expected: pass with new path sets

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows only V9 added.

## Auto Run Result

Status: done

Summary: PM Schedules & Schedule Dates (blueprint F5) — V9 additive migration extends ck_audit_log_entity_type with PM_SCHEDULE/PM_SCHEDULE_DATE (enum moved in same change). PmScheduleService + REST /api/v1/pm-schedules: POST create snapshots an approved ACTIVE checksheet (revision + frequency code/name), validates plant/machine/checksheet/frequency coherence (machine-in-plant, checksheet-on-machine, frequency active, active-pointer match), and materializes the year's pm_schedule_dates from the effective-date anchor (MONTHLY → 12 clamped dates, ANNUAL → 1; unsupported codes rejected). Strict sequential approval DRAFT → submit → approve-spv → approve-prod → activate with per-step actor/timestamp stamps and audit UPDATE rows; every transition serialized via findByIdForUpdate PESSIMISTIC_WRITE (exactly one winner). ACTIVE-gated per-date transitions (SCHEDULED→EXECUTED/MISSED, SCHEDULED|EXECUTED→RESCHEDULED, RESCHEDULED→EXECUTED; no back to SCHEDULED), repeatable statuses, audit-logged. Gates: four-role create/submit + leader-only approvals (SUPER_ADMIN bypass), reads scope-filtered. OPA pm_schedule_paths + four-role blocks + approve-prod coverage; enforced-paths wired in .env.example.

Files changed: V9 migration (new); PmScheduleService (new); PmScheduleController (new, @Tag); PreventiveDtos (+schedule/date records), PreventiveExceptionHandler (+10 mappings, controller in assignableTypes); PmScheduleEntity (+markSubmitted), PmScheduleRepository (+findByIdForUpdate, findByPlantIdAndMachineIdAndChecksheetIdAndYear), PmScheduleDateRepository (+findByScheduleIdOrderByPlannedDateAsc); AuditEntityType (+2); authz.rego + authz_test.rego (+19); .env.example; V1BaseSchemaMigrationTest (+V9 assertions); tests: PmScheduleServiceIntegrationTest (21), PmScheduleControllerTest (26), PmScheduleTransitionConcurrencyIntegrationTest (4).

Review findings breakdown: step-04 pass — 12 patched (3 medium, 9 low), 4 deferred, ~15 dismissed (incl. false-positive RESCHEDULED→MISSED claim — verified the guard rejects it).

Follow-up review recommendation: true — patched score 3×3+9=18 ≥ 5 (3 medium, 9 low, 0 high).

Verification performed: PmScheduleServiceIntegrationTest 21/21, PmScheduleControllerTest 26/26, PmScheduleTransitionConcurrencyIntegrationTest 4/4, V1BaseSchemaMigrationTest 24/24, regressions PmChecksheetServiceIntegrationTest 17/17 + PmChecklistServiceIntegrationTest 17/17; OPA 400/400; migration diff shows only V9.

Deferred: N+1 list + unindexed year/status filters (DW-149 pattern), AuthzEnforcementIntegrationTest not extended (DW-147/156), no creator-only submit (F5 silent), generic date-transition error UX (shared with 19-1).

Residual risks: list() loads all schedules + per-row machine/date lookups (fine at pilot scale); concurrent create relies on uq backstop → SCHEDULE_ALREADY_EXISTS.
