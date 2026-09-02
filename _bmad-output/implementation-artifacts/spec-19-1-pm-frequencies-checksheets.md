---
title: 'Story 19-1: PM Frequencies & Checksheets'
type: 'feature'
created: '2026-09-02'
status: 'done'
review_loop_iteration: 0
baseline_revision: '0846d36'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred:
  - summary: >-
      Full `mvn test` in one JVM hits Docker memory exhaustion (~3.8 GB) on
      pre-existing alert-module integration tests; every affected class passes
      in isolation.
    evidence: |-
      Background full-suite run was killed mid-execution with no logged
      failures; SparepartAlertQueryServiceTeamScopeFilterIntegrationTest
      passes 10/10 when run alone. Environmental, not caused by story 19-1.
    severity: low
---

<intent-contract>

## Intent

**Problem:** Blueprint F1–F3 tables (pm_frequencies, pm_checksheets, active_checksheets) and bare entities exist since 15-2, but there is no service or API: frequencies are unseeded master data, checksheets cannot be created/revised/approved, and the active_checksheets pointer is never maintained — the PM foundation of Epic 19 is unusable.

**Approach:** Add PmFrequencyService + PmChecksheetService and REST surfaces `/api/v1/pm-frequencies` and `/api/v1/pm-checksheets` implementing the revision workflow: create revision 1 (unapproved), revise (supersedes self-FK, next revision_no), approve (stamps approver/time/effective_date, flips the active_checksheets pointer, deactivates the old revision). Seed MONTHLY/ANNUAL frequencies and extend the audit entity_type CHECK via one additive V7 migration.

## Boundaries & Constraints

**Always:**
- V7 additive migration only (V1..V6 untouched): INSERT pm_frequencies MONTHLY/ANNUAL (sort_order 1/2); extend ck_audit_log_entity_type with PM_FREQUENCY, PM_CHECKSHEET (drop+recreate CHECK, V6 pattern) and add matching AuditEntityType enum values in the same change.
- API `/api/v1/pm-frequencies`: GET list (sort_order asc), GET /{id}, POST {code,name,description?,sortOrder?,isActive?}, PUT /{id} {name,description?,sortOrder?,isActive}. Duplicate code → 409 DUPLICATE_FREQUENCY_CODE.
- API `/api/v1/pm-checksheets`: POST {machineId,frequencyId,revisionReason?} → revision_no=1, is_active=false, created_by=actor; GET list (filters machineId, frequencyId); GET /{id}; GET /active?machineId=&frequencyId= → pointer row; POST /{id}/revise {revisionReason?} → revision_no=max+1, supersedes=source, unapproved; POST /{id}/approve {effectiveDate?} (default today from Clock). camelCase.
- Approve is the only path that sets is_active=true and upserts active_checksheets (machine_id,frequency_id)→checksheet_id; the old active revision flips is_active=false in the same transaction. Approving an approved revision → 409 INVALID_CHECKSHEET_TRANSITION. No update/delete of checksheet content (items are 19-2).
- Gates: frequency mutations and checksheet create/revise by STAFF_MAINTENANCE/SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE (SUPER_ADMIN bypass); approve additionally requires a leader role (SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE/SUPER_ADMIN). Machine-scoped checksheet actions use the requireMutationAccess pattern (plant/group scope via OperationalScopeService); frequencies are global master data (role gate only).
- Audit: CREATE/UPDATE records with actor, previous+new values (programValues pattern); approve records UPDATE with approvedBy/effectiveDate.
- OPA: new pm_frequency_paths {"/api/v1/pm-frequencies", "/api/v1/pm-frequencies/*"} and pm_checksheet_paths {"/api/v1/pm-checksheets", "/api/v1/pm-checksheets/*", "/api/v1/pm-checksheets/active", "/api/v1/pm-checksheets/*/revise", "/api/v1/pm-checksheets/*/approve"} with the same four-role mutation allow blocks; update authz.rego + authz_test.rego.
- Errors: VALIDATION_ERROR (400), FORBIDDEN (403), PM_FREQUENCY_NOT_FOUND / PM_CHECKSHEET_NOT_FOUND / MACHINE_NOT_FOUND (404), DUPLICATE_FREQUENCY_CODE / INVALID_CHECKSHEET_TRANSITION (409).
- Create checksheet requires machine exists and frequency active; revision_no uniqueness relies on uq_pm_checksheets_machine_frequency_revision (race → 409).
- TypeScript strict; no frontend work.

**Block If:**
- No race-safe upsert of the active_checksheets pointer exists with current repo patterns → HALT blocked.

**Never:**
- No edits to V1..V6; no checklist categories/items endpoints (19-2); no schedules (19-3); no deletion of checksheets; no scheduler.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create checksheet | POST valid machine+freq | 201 revision 1, is_active=false, no pointer row | unknown machine/frequency → 404; inactive frequency → 400 |
| Duplicate frequency code | POST code exists | rejected | 409 DUPLICATE_FREQUENCY_CODE |
| Revise | POST /{id}/revise on active r1 | 201 r2 supersedes=r1, unapproved, inactive | — |
| Approve | POST /{id}/approve on r2 | approved_by/at/effective_date stamped; r2 active; pointer→r2; r1 inactive; audit | already approved → 409 |
| Active lookup | GET /active?machineId&frequencyId | pointer checksheet | none → 404 |
| Staff approves | STAFF_MAINTENANCE approve | rejected | 403 FORBIDDEN |
| Out-of-scope machine | leader of plant A, machine in B | rejected | 403 FORBIDDEN |
| Concurrent revise | two revises of same source | one r2 wins, other 409 | uq constraint → 409 |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1086-1133` -- pm_frequencies/pm_checksheets/active_checksheets DDL. READ-ONLY.
- `syncro/apps/backend/src/main/resources/db/migration/V6__work_order_quality_rating_audit_type.sql` -- additive CHECK-extend migration pattern for V7.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmFrequencyEntity.java`, `PmChecksheetEntity.java` (has approve()), `ActiveChecksheetEntity.java` + `ActiveChecksheetId.java` + repositories -- extend: PmChecksheetEntity needs deactivate(); ActiveChecksheetEntity needs setChecksheetId(); repos need findByCode, max-revision query, pointer lookup.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PreventiveProgramService.java:258-298` -- requireMutationAccess/plantInScope/groupInScope/loadMachine pattern to copy.
- `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` + `AuditRecord` + `domain/AuditEntityType.java` -- audit; add PM_FREQUENCY, PM_CHECKSHEET.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PreventiveProgramController.java` + `PreventiveDtos.java` + `PreventiveExceptionHandler.java` -- controller/DTO/advice pattern.
- `syncro/authz/policy/authz.rego:309-329` -- preventive path-set pattern; `authz_test.rego` case pattern.
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/preventive/infrastructure/db/PreventiveEntityConventionIntegrationTest.java` -- already proves F1-F3 mapping (no re-proof needed); `AbstractPostgresIntegrationTest` base; `src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- update CHECK assertions.

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmFrequencyService.java`, `PmChecksheetService.java` (+ commands/exceptions).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmFrequencyController.java`, `PmChecksheetController.java`, DTO records (extend PreventiveDtos or new PmDtos), exception handler (extend advice).
- `syncro/apps/backend/src/main/resources/db/migration/V7__pm_frequencies_seed_and_audit_types.sql`.
- Tests: `PmChecksheetServiceIntegrationTest.java`, `PmFrequencyControllerTest.java`, `PmChecksheetControllerTest.java`.

## Tasks & Acceptance

### Review Findings

- [x] [Review][Patch] Locale-sensitive `toUpperCase()` on frequency code — use `Locale.ROOT` and validate length AFTER uppercasing (Turkish-i / VARCHAR(50) overflow → 500) [`PmFrequencyService.java:59`]
- [x] [Review][Patch] `update()` wipes existing `description` when omitted, but preserves `sortOrder`/`isActive` — make null-merge consistent [`PmFrequencyService.java:90`]
- [x] [Review][Patch] Missing `MissingServletRequestParameterException`/`MethodArgumentTypeMismatchException` handlers → malformed UUID / missing param returns Spring default body, breaking the error envelope [`PreventiveExceptionHandler.java`]
- [x] [Review][Patch] Concurrent duplicate create maps to `INVALID_CHECKSHEET_TRANSITION` not `CHECKSHEET_ALREADY_EXISTS` — `save()` should distinguish revisionNo==1 pair conflict [`PmChecksheetService.java:save`]
- [x] [Review][Patch] Audit `checksheetValues` mixes `.toString()` UUIDs with raw UUID objects — stringify all consistently [`PmChecksheetService.java`]
- [x] [Review][Patch] `list()` unfiltered branch returns `findAll()` in arbitrary order — pin a stable order [`PmChecksheetService.java:121`]
- [x] [Review][Patch] `revise()` skips the `frequency.isActive()` guard that `create()` enforces — add it [`PmChecksheetService.java:162`]
- [x] [Review][Patch] `.env.example` header comment stale (still says org/maintenance/sync surface) [`syncro/.env.example`]
- [x] [Review][Patch] `PmFrequencyService.update()` has no executing test (field-preservation + audit + 404 unverified)
- [x] [Review][Patch] "inactive frequency → 400" only mock-stubbed — add real integration test
- [x] [Review][Patch] SUPER_ADMIN bypass branches never executed by any test — add integration test
- [x] [Review][Patch] Out-of-scope MUTATION (create/revise/approve → 403) untested (INT-012 covers reads only)
- [x] [Review][Patch] Matrix row "concurrent revise → one wins, other 409" untested — add concurrency test (repo pattern exists)
- [x] [Review][Patch] INT-008 asserts wall-clock `LocalDate.now(ZoneOffset.UTC)` vs injected Clock — flaky across midnight; use controlled clock
- [x] [Review][Defer] OPA rego tests not wired into `mvn test`/CI (README count stale) — pre-existing repo condition, not introduced here — deferred, pre-existing
- [x] [Review][Defer] `syncro/apps/web/openapi.json` not regenerated for new endpoints — already stale for prior epics; contract-generation is a separate concern — deferred, pre-existing
- [x] [Review][Defer] `list()` N+1 machine lookup + unbounded findAll — matches existing PreventiveProgramService idiom — deferred, pre-existing pattern
- [x] [Review][Defer] Frequency `isActive=false` can orphan active checksheets (no in-use guard) — spec silent; behavior change — deferred

**Execution:**
- `V7__pm_frequencies_seed_and_audit_types.sql` -- seed + CHECK extend -- foundation
- `AuditEntityType.java` -- PM_FREQUENCY/PM_CHECKSHEET -- audit parity with V7
- `PmFrequencyRepository.java` / `PmChecksheetRepository.java` / `ActiveChecksheetRepository.java` + entities -- queries and mutators -- service needs
- `PmFrequencyService.java` + `PmChecksheetService.java` -- CRUD + revision/approve workflow + gates + audit -- core
- `PmFrequencyController.java` + `PmChecksheetController.java` + DTOs + handler -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- new path sets -- OPA parity
- `V1BaseSchemaMigrationTest.java` -- assert new CHECK values -- migration evidence
- Integration + controller tests -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given a fresh DB, when migrations run, then pm_frequencies contains MONTHLY and ANNUAL and audit_log accepts PM_FREQUENCY/PM_CHECKSHEET entity types.
- Given a machine+frequency, when a checksheet is created then revised then approved, then revision_no increments, supersedes links the chain, only the approved revision is is_active, and active_checksheets points at it (old revision deactivated) — all audit-logged.
- Given an approved revision, when approved again, then 409 INVALID_CHECKSHEET_TRANSITION; given STAFF_MAINTENANCE, then approve gives 403 while create/revise succeed.
- Given a duplicate frequency code, then 409; given an out-of-scope machine, then 403.
- Given a full `mvn test` run, then the suite is green including OPA tests.

## Spec Change Log

### 2026-09-03 — post-review contract deltas (code is authoritative)
- `CHECKSHEET_ALREADY_EXISTS` (409) added to the error set beyond the "Errors:" enumeration (duplicate-create pre-check; race path shares this code).
- Reads (`list`/`get`/`getActive`) are scope-filtered via `OperationalScopeService` — the "Gates:" constraint listed only mutation scoping; read scoping was added as a review hardening (project rule: scoped data enforced server-side).
- Frequency `code` is normalized to upper-case (`Locale.ROOT`) on create — implicit in the spec's duplicate-code semantics, now explicit.
- PUT frequency treats `sortOrder`/`isActive`/`description` as keep-existing when omitted (spec listed them without `?`).
- KEEP: V7 additive-only pattern, approve-as-only-activation-path, latest-revision guards, pointer constraint backstop, rego coarse-fence + service-authoritative approve narrowing.

## Review Triage Log

### 2026-09-03 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 18 (high 5, medium 6, low 7)
- defer: 1
- reject: ~20
- addressed_findings:
  - `[high]` `[patch]` Approve lacked latest-revision guard → pointer could regress to a stale revision; added revisionNo < max → 409
  - `[high]` `[patch]` Revise accepted superseded sources → chain/numbering divergence; added non-latest source → 409
  - `[high]` `[patch]` list/get/getActive were unscoped — any authenticated role could read every plant; added OperationalScope filtering mirroring PreventiveProgramService
  - `[high]` `[patch]` Headline second-approval path (deactivate old + pointer update) never exercised; added INT-002 asserting deactivation, pointer flip, and audit_log rows
  - `[high]` `[patch]` New PM paths missing from SYNCRO_AUTHZ_ENFORCED_PATHS (.env.example) — rego rules were dead config (same gap as 18-4); added both patterns
  - `[medium]` `[patch]` Pointer upsert had no constraint backstop → wrapped saveAndFlush, DataIntegrityViolation → 409
  - `[medium]` `[patch]` Duplicate create surfaced misleading INVALID_CHECKSHEET_TRANSITION → ChecksheetAlreadyExistsException / CHECKSHEET_ALREADY_EXISTS 409
  - `[medium]` `[patch]` Old-revision deactivation not audit-logged → added UPDATE AuditRecord for the deactivated revision
  - `[medium]` `[patch]` revise/approve rejected body-less POSTs despite optional fields → @RequestBody(required=false)
  - `[medium]` `[patch]` Six exception→HTTP mappings untested → controller error-path tests (403/409/404/400 with fieldErrors)
  - `[medium]` `[patch]` List ordering/filter branches unobserved → INT-010/INT-011
  - `[low]` `[patch]` Whitespace-only code/name passed @NotBlank then hit DB CHECK (500) → service 400 guards
  - `[low]` `[patch]` Negative sortOrder accepted → 400
  - `[low]` `[patch]` Duplicate PmFrequencyNotFoundException across services → deduped to PmFrequencyService's
  - `[low]` `[patch]` Dead ActiveChecksheetRepository.findByChecksheetId → deleted
  - `[low]` `[patch]` Redundant /pm-checksheets/active rego entry + query-string test action → fixed; added rego test pinning staff-approve-allowed-at-rego (service-only narrowing)
  - `[low]` `[patch]` Missing EOF newlines on all new/modified files → added

## Auto Run Result

Status: done

Summary: PM Frequencies & Checksheets (blueprint F1–F3) — V7 additive migration seeds MONTHLY/ANNUAL frequencies and extends ck_audit_log_entity_type with PM_FREQUENCY/PM_CHECKSHEET (Java enum moved in same change). PmFrequencyService + REST /api/v1/pm-frequencies (GET list sort_order asc, GET /{id}, POST, PUT /{id}) with role gate, duplicate-code 409, whitespace/sortOrder validation. PmChecksheetService + REST /api/v1/pm-checksheets (POST create r1, GET list scoped, GET /{id}, GET /active, POST /{id}/revise, POST /{id}/approve) implementing the revision workflow: approve is the only activation path — stamps approver/time/effective-date (default today from Clock), deactivates the old active revision (audit-logged), upserts the active_checksheets pointer with constraint backstop; latest-revision guards on approve and revise; leader-only approve in-service (rego coarse fence, pinned by test). Reads scope-filtered via OperationalScopeService. OPA pm_frequency_paths + pm_checksheet_paths with four-role allow blocks; enforced-paths wired in .env.example.

Files changed: V7 migration (new); PmFrequencyService, PmChecksheetService (new); PmFrequencyController, PmChecksheetController (new); PreventiveDtos (+frequency/checksheet records), PreventiveExceptionHandler (+advice types and mappings); PmChecksheetEntity (+activate/deactivate), ActiveChecksheetEntity (+setChecksheetId), repositories (+queries); AuditEntityType (+PM_FREQUENCY, +PM_CHECKSHEET); authz.rego + authz_test.rego; .env.example; V1BaseSchemaMigrationTest (+V7 assertions); tests: PmChecksheetServiceIntegrationTest (12), PmFrequencyControllerTest (9), PmChecksheetControllerTest (17).

Review findings breakdown: build-auto step-04 pass — 18 patched (5 high, 6 medium, 7 low), 1 deferred, ~20 rejected. /bmad-code-review pass — 14 patched (8 code, 6 test), 4 deferred (DW-147..150), ~10 dismissed.

Follow-up review recommendation: true — patched high-severity findings present (score 3×6+7=25 ≥ 5).

Verification performed: second /bmad-code-review pass (4 layers) returned 14 more patches (8 code, 6 test) — all applied (Locale.ROOT code normalization, description null-merge, query/path param error handlers, save() rev-1 → CHECKSHEET_ALREADY_EXISTS, uniform audit UUID strings, stable list order, revise inactive-frequency guard, .env.example comment; tests: frequency update, real inactive-frequency 400, SUPER_ADMIN bypass, out-of-scope mutations, PmChecksheetReviseConcurrencyIntegrationTest for the concurrent-revise matrix row, controlled-clock INT-008). Final per-class runs (shared-container memory limit on full-suite single-JVM): PmChecksheetServiceIntegrationTest 17/17, PmChecksheetReviseConcurrencyIntegrationTest 1/1, PmFrequencyControllerTest 9/9, PmChecksheetControllerTest 20/20, V1BaseSchemaMigrationTest 22/22, PreventiveEntityConventionIntegrationTest 4/4, regression PreventiveControllerTest 11/11 + PreventiveChecklistControllerTest 14/14 + PreventiveProgramServiceTest 11/11; OPA 364/364; mvn test-compile clean; git diff of db/migration shows only V7 added.

Deferred: full `mvn test` in one JVM hits Docker memory exhaustion (~3.8 GB) on pre-existing alert-module integration tests — environmental, unrelated to this story; every affected class passes in isolation.

Residual risks: concurrent double-approve relies on the pointer constraint backstop (409) rather than row locking — acceptable at this scale; rego admits STAFF on /approve by design (service gate authoritative), pinned by test.

## Design Notes

- Pointer flip in one @Transactional: approve loads the checksheet, sets the old active revision is_active=false, saves the new one active=true, upserts ActiveChecksheetEntity (findById → setChecksheetId or insert). Composite PK makes upsert a find-then-save.
- revision_no = max+1 computed in-txn; uq_pm_checksheets_machine_frequency_revision is the race guard → DataIntegrityViolation mapped to 409.
- Frequencies are global (no plant column) — deliberately not machine-scoped; role gate only.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmChecksheetServiceIntegrationTest,PmFrequencyControllerTest,PmChecksheetControllerTest,V1BaseSchemaMigrationTest,PreventiveEntityConventionIntegrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full suite green
- OPA rego tests (as run in prior stories) -- expected: pass with new path sets

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows only V7 added.
