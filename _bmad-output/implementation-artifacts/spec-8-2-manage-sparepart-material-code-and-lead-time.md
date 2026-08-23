---
title: 'Manage Sparepart Material Code and Lead Time'
type: 'feature'
created: '2026-08-24'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_commit: 0387b3f
final_revision: 891b8ee
context:
  - '{project-root}/_bmad-output/project-context.md'
warnings:
  - oversized
---

<intent-contract>

## Intent

**Problem:** Procurement planning (FR-078/FR-079) has no inputs: a sparepart carries no material code and no lead time, identical spareparts across machines cannot aggregate for inventory recap, and there is no way to partially update a sparepart (only full-replace PUT) nor any server-side job-scope enforcement anywhere in the system (FR-088 would be the first ABAC step).

**Approach:** One Flyway migration adds nullable `material_code VARCHAR(64)` (partial unique index on `lower(material_code)`) and nullable `lead_time_hours NUMERIC(12,2)` (+ positive CHECK) to `spareparts`. First-ever `PATCH /api/v1/spareparts/{sparepartId}` replaces just the procurement subset: body `{materialCode, leadTimeHours}` — clients always send both keys; JSON null (or omitted key, indistinguishable after record binding) clears that value, so there is no tri-state ambiguity. Service enforces existing app-role gate THEN new `JobScopeService.requireLevelOrAbove(user, LEADER)` (rank via `ResponsibilityLevel.ordinal()`; SUPER_ADMIN bypasses, matching `PlantScopeService`/FR-053 admin precedent), translates `uq_spareparts_material_code` violations to `DUPLICATE_MATERIAL_CODE` (400), and writes immutable audit entries with previous/new procurement values. Views (list + detail) gain the fields. Frontend regenerates Orval clients and extends the sparepart management UI with new `MaterialCodeField`/`LeadTimeInput` shared components.

## Boundaries & Constraints

**Always:**
- Job scope = a `machine_responsibilities` row for the user with level rank ≥ LEADER (enum declaration order is authoritative, cf. `EscalationService.ESCALATION_ORDER`). Enforced server-side in addition to `requireMutationRole`; denial returns 403 `JOB_SCOPE_REQUIRED` with a message naming LEADER-or-above.
- Cross-module boundary: `auth` must not import `machine.infrastructure` — define port `auth.application.UserJobScopeReader` (interface), implemented by `machine.infrastructure.MachineResponsibilityJobScopeAdapter`.
- Uniqueness: DB-level partial unique index `uq_spareparts_material_code` on `lower(material_code) WHERE material_code IS NOT NULL` (case-insensitive, multi-null safe, mirrors `uq_spareparts_lower_code` idiom); plus service pre-check `existsByMaterialCodeIgnoreCase`; translate constraint violation by name like `save()` does today.
- `lead_time_hours` is BigDecimal end-to-end (NUMERIC(12,2), scale 2 HALF_UP on persist); unit is HOURS; 7.5 days is entered/stored as 180.00. Values appear in list AND detail views.
- Both mutations write audit via `AuditLogWriter.record(user, …)` with `SparepartAuditValues` extended to include `materialCode`/`leadTimeHours` so previous/new snapshots capture them; plantId = sparepart's machine plant.
- Non-SUPER_ADMIN writers must pass the same plant-access check as `create/update` (sparepart → machine → plant membership).
- Frontend follows existing idioms: controlled-props shared components in `components/syncro/`, server-driven fieldErrors, `canMutate` hide/badge gating; job scope is NOT known client-side, so UI shows a static "requires LEADER+" hint and renders the backend `JOB_SCOPE_REQUIRED` message verbatim on denial.

**Block If:** Nothing requires human input. SUPER_ADMIN-bypass and PATCH-replaces-subset semantics are pinned here as decisions with rationale.

**Never:**
- Never touch the PUT contract (`SparepartRequest` stays taxonomy-only); never let PATCH alter machine/taxonomy/code/name.
- Never enforce uniqueness only in Java — the DB index is the guarantee.
- Never store lead time in days or mixed units anywhere persistent; hours only.
- Never grant mutation rights to VIEWER; never treat MANAGE app role alone as LEADER scope.
- Never add React Hook Form/Zod adoption here (no precedent); never hand-edit generated Orval files.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | MANAGE user with LEADER+ row PATCHes `{materialCode:"ABC-1", leadTimeHours:36}` | 200; values persisted + returned in view/list; audit UPDATE row with old/new | No error |
| CLEAR_VALUES | PATCH `{materialCode:null, leadTimeHours:null}` (or either key omitted/null) | That value cleared (NULL), 200; audited; client contract: always send both keys | No error |
| DUPLICATE_CODE | materialCode equals another sparepart's, different case | 400 `DUPLICATE_MATERIAL_CODE`, fieldErrors.materialCode set | Pre-check + index both guard |
| BELOW_LEADER | MANAGE user with no ≥LEADER row | 403 `JOB_SCOPE_REQUIRED`, explains LEADER+ | No mutation, no audit |
| SUPER_ADMIN_NO_ROWS | SUPER_ADMIN without any responsibility row | Allowed (documented bypass) | No error |
| VIEWER | Any PATCH | 403 `FORBIDDEN` (app-role gate first) | Standard |
| FRACTIONAL | leadTimeHours=7.5 | Stored 7.50, returned 7.5 | No error |
| NON_POSITIVE | leadTimeHours=0 / negative | 400 `VALIDATION_ERROR` | Bean Validation |
| WRONG_PLANT | MANAGE+LEADER on machine outside assigned plants | 404 masking (same as create/update) | Consistent |
| UNKNOWN_ID | PATCH nonexistent sparepartId | 404 `SPAREPART_NOT_FOUND` | Standard |

</intent-contract>

## Code Map

**Backend (`apps/backend/src/main/java/com/syncro`):**
- `db/migration/V37__add_sparepart_procurement_fields.sql` -- NEW -- two ALTERs + partial unique expression index `uq_spareparts_material_code` + `ck_spareparts_lead_time_positive`.
- `sparepart/infrastructure/SparepartEntity.java` -- MODIFY -- nullable `materialCode` (length 64), `leadTimeHours` BigDecimal precision 12 scale 2; mutator `updateProcurement(String, BigDecimal, Instant)`.
- `sparepart/infrastructure/SparepartRepository.java` -- MODIFY -- `existsByMaterialCodeIgnoreCaseAndIdNot(String, UUID)`.
- `auth/application/UserJobScopeReader.java` -- NEW port; `auth/application/JobScopeService.java` -- NEW `hasLevelOrAbove`/`requireLevelOrAbove` (ordinal rank, SUPER_ADMIN bypass); `auth/application/JobScopeForbiddenException.java` -- NEW (carries required level).
- `machine/infrastructure/MachineResponsibilityJobScopeAdapter.java` -- NEW implements port via new repository query `existsByUserIdAndResponsibilityLevelIn`.
- `sparepart/application/SparepartService.java` -- MODIFY -- `patchProcurement(user, id, SparepartProcurementCommand)`: role gate → job scope → load(404) → plant check (reuse `resolveMachine` semantics via sparepart.machine) → pre-check duplicate → audit previous → mutate → save(constraint translation: add `DUPLICATE_MATERIAL_CODE_CONSTRAINT`) → audit new.
- `sparepart/application/SparepartAuditValues.java` -- MODIFY -- include materialCode/leadTimeHours.
- `sparepart/api/SparepartDtos.java` -- MODIFY -- `SparepartProcurementRequest(@Size(max=64) String materialCode, @Positive @Digits(integer=10, fraction=2) BigDecimal leadTimeHours)` — both nullable (null = clear); views gain fields.
- `sparepart/api/SparepartController.java` -- MODIFY -- `@PatchMapping("/{sparepartId}")` + `@Operation` for OpenAPI.
- `sparepart/api/SparepartExceptionHandler.java` -- MODIFY -- map `DuplicateMaterialCodeException`→400 `DUPLICATE_MATERIAL_CODE`(+fieldErrors), `JobScopeForbiddenException`→403 `JOB_SCOPE_REQUIRED`.

**Frontend (`apps/web/src`):**
- `lib/api/generated/**` -- REGENERATE via `npm run generate:snapshot` (backend running) + `npm run generate:api`.
- `components/syncro/material-code-field.tsx`, `components/syncro/lead-time-input.tsx` -- NEW controlled components (props: value/onChange/error/readOnly/disabledReason; LeadTimeInput has internal h/d unit toggle, submits hours, shows converted days hint).
- `features/master-data/spareparts/sparepart-management.tsx` -- MODIFY -- edit dialog gains Procurement section (MaterialCodeField + LeadTimeInput, static "Requires job scope LEADER or above." hint); save chain = PUT then PATCH when procurement changed or clearing needed; `SparepartTable` gains Material Code + Lead Time columns; denial surfaces backend message.
- Unit tests: `components/syncro/*.test.tsx` x2, `features/master-data/spareparts/sparepart-management.test.tsx` (clone machine-groups mock pattern).

**Backend tests:** extend `SparepartServiceIntegrationTest` (happy/clear/dup/below-leader/super-admin/fractional/plant-mask/audit assertions), `SparepartControllerTest` (contract + error codes).

## Tasks & Acceptance

**Execution:**
- [x] `V37__add_sparepart_procurement_fields.sql` -- NEW migration per Code Map -- schema owns uniqueness.
- [x] Entity + repository + audit-values modifications -- persistence layer carries the fields.
- [x] Port + JobScopeService + adapter + new repository query -- first ABAC step behind a boundary.
- [x] Service `patchProcurement` + DTOs + controller PATCH + exception mappings -- the use case.
- [x] Backend tests per plan -- prove every matrix row.
- [x] Regenerate OpenAPI snapshot + Orval client (boot backend, run scripts) -- typed hooks exist.
- [x] MaterialCodeField + LeadTimeInput + sparepart-management integration + tests -- UI delivery.
- [x] Verify: `mvn -f apps/backend/pom.xml test -Dtest="Sparepart*"` green; `npm run test:unit` + Biome green; manual PATCH via running stack (curl with JWT) recording status/payload.

**Acceptance Criteria:**

- Given authorized LEADER+-scoped user, when PATCHing valid values, then they persist and appear on sparepart list and detail. [AC 8.2-1]
- Given a duplicate material code differing only in case, when PATCHed, then database-level uniqueness rejects it and the API returns standard validation error `DUPLICATE_MATERIAL_CODE`. [AC 8.2-2]
- Given no material code set, when installation create and lifetime evaluation run, then both succeed unchanged (regression: existing suites stay green). [AC 8.2-3]
- Given leadTimeHours 36 or 7.5, when persisted, then fractional durations survive round-trip at scale 2. [AC 8.2-4]
- Given user below LEADER job scope, when mutating, then server responds 403 `JOB_SCOPE_REQUIRED` explaining LEADER-or-above requirement, with no mutation and no audit row. [AC 8.2-5]
- Given any successful PATCH or clear, when inspected, then immutable audit entries contain actor, action, entity, previous and new values. [AC 8.2-6]

## Spec Change Log

- 2026-08-24: Spec created (draft → ready-for-dev). Epic 8 context loaded (valid cache); continuity from spec-8-1; codebase investigated (sparepart module, audit writer, responsibility model, error-handler idiom, frontend feature + Orval conventions). Decisions pinned: PATCH-replaces-subset with null/omitted=clear; job scope = any responsibility row at rank ≥ LEADER (SUPER_ADMIN bypasses); port-in-auth/adapter-in-machine for boundary compliance; hours-only lead time.
- 2026-08-24: Implemented (ready-for-dev → review). V37 + entity/repo/audit-values; JobScopeService + port + adapter; patchProcurement + PATCH endpoint + mappings; Orval regen; two shared components + dialog/table integration; 60 backend + 204 frontend tests green; live-stack PATCH happy/denial/clear + audit rows verified.

## Review Triage Log

### 2026-08-24 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8: (high 1, medium 5, low 2)
- defer: 1: (low 1)
- reject: 5
- addressed_findings:
  - `[high]` `[patch]` **Generated client files untracked+gitignored**: `**/src/lib/api/generated/` is gitignored yet partially force-added, so regen produced 34 new model files (incl. `sparepartProcurementRequest.ts`) that are referenced by tracked `index.ts`/`syncro.ts` but invisible to `git add` — a fresh checkout would fail tsc. Force-added the whole generated dir (37 staged files). [git index; mirrors how older generated files were tracked]
  - `[medium]` `[patch]` **No-op PATCH pollution**: `patchProcurement` always wrote an UPDATE audit row + bumped `updatedAt` even when values were unchanged — unbounded immutable audit noise from repeated/idempotent callers. Added change detection (canonicalize lead time to stripTrailingZeros, Objects.equals both fields) → returns view with no audit/updatedAt touch; pinned by new test 8.2-SVC-011. [SparepartService.java]
  - `[medium]` `[patch]` **Flaky audit assertion**: `latestAuditEntryFor` used unordered `findAll().reduce(last)` over random-UUID ids — could pick CREATE over UPDATE. Now sorts by createdAt DESC. [SparepartServiceIntegrationTest.java]
  - `[medium]` `[patch]` **PUT→PATCH non-atomicity UX**: a failed PATCH after successful PUT left base mutated while the UI reported generic failure. Now PATCH runs in its own try/catch; failure shows an explicit "Sparepart updated, but procurement was not saved: <backend msg>" toast and closes the dialog so the user can retry procurement alone. [sparepart-management.tsx]
  - `[medium]` `[patch]` **Wrong error schema in generated client**: PATCH error @ApiResponses had no `@Content`, so OpenAPI/Orval typed error bodies as `SparepartView`; added `content = @Content` (DELETE idiom) and regenerated — error responses now `data: void`. [SparepartController.java, openapi.json, generated client]
  - `[medium]` `[patch]` **Ladder-drift risk + inconsistent fail-fast**: auth-side `LEVEL_ORDER` duplicates the enum as loose strings and SUPER_ADMIN bypassed before level validation (typo → 500; silent enum reorder). Reordered validation first (SUPER_ADMIN now validated too) and added `JobScopeServiceTest` pinning `levelOrder()` to `ResponsibilityLevel.values()` + qualifying-set and bypass behavior. [JobScopeService.java, JobScopeServiceTest.java]
  - `[low]` `[patch]` **Client validation promised "positive" but accepted 0**: regex now also requires `Number(hours) > 0` (fractional positives like 0.5 still pass). [sparepart-management.tsx]
  - `[low]` `[patch]` **LeadTimeInput used without key** despite its own JSDoc (unit/rawDisplay bleed across entities): added `key={sparepart.id}` at the usage site. [sparepart-management.tsx]
- rejected (by-design, documented): job-scope breadth ("any LEADER+ row anywhere" is the pinned contract; material code is explicitly global and plant access still gates which records) [#2]; omit/null-clears concurrency (pinned PATCH contract; codebase has no optimistic-locking precedent; audit records who/when) [#3]; unused readOnly/disabledReason props (client cannot know job scope; denial UX implemented + tested) [#7]; test pins intentional user-facing message [#11]; openapi.json regen noise [#14].
- deferred: gitignore/generated fragility (repo-wide; every regen silently orphans new files) → deferred-work.md.

Note: the Edge Case Hunter subagent could not complete (repeated network errors); triage based on the Blind Hunter pass only.

## Design Notes

- **Why PATCH replaces the whole subset with required keys:** Jackson cannot distinguish absent vs null in records without extra machinery; explicit null-clear keeps the contract total, testable, and free of tri-state folklore while PUT remains untouched.
- **Why SUPER_ADMIN bypasses job scope:** every existing admin path (`PlantScopeService.canAccessPlant`, alert direct-resolve FR-053) treats SUPER_ADMIN as unrestricted platform administrator; requiring seeded responsibility rows for admins would break bootstrap flows.
- **Why a port:** project-context forbids cross-module imports except through public contracts; architecture mandates the utility live in the auth/security module — port-in-auth + adapter-in-machine satisfies both and keeps future procurement stories (8.3–8.5) reusing `JobScopeService`.
- **Why UI doesn't pre-gate by job scope:** the token carries only applicationRole; adding scope to `/auth/session` is out of scope. Static hint + verbatim backend denial satisfies "explain required role" (UX-DR) honestly.
- **Continuity from 8-1:** AWS SDK/Garage untouched here; same verification habits (live-stack evidence, constraint-name translation style, Testcontainers for uniqueness).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=Sparepart*,JobScope*,AuditLogWiringIntegrationTest"` -- expected: BUILD SUCCESS (uniqueness/denial/audit proven on real PostgreSQL via Testcontainers).
- `cd syncro/apps/web && npm run generate:snapshot && npm run generate:api && npm run test:unit` -- expected: PATCH hook generated; unit tests green.
- Live: `mvn spring-boot:run` + curl PATCH with JWT → record HTTP status/body for happy, duplicate, below-leader cases.

**Manual checks:**
- pgAdmin/psql: `\d spareparts` shows columns + `uq_spareparts_material_code` index; audit_log row inspection after PATCH.

## Dev Agent Record

### Agent Model Used

ox-alpha (opencode/x-preview-f-free)

### Debug Log References

- mvn compile round 1: JobScopeService referenced AuthenticatedUser/ApplicationRole unqualified - fixed to JwtTokenService.AuthenticatedUser + explicit ApplicationRole import.
- Integration test run 1: 3 failures, all test-expectation issues (no code defects): (1) audit-count baseline must be captured AFTER create (create writes its own CREATE row); (2)+(3) BigDecimal scale in audit JSON follows the submitted value ("7.5", not "7.50") - switched to numeric comparison.
- Frontend test run 1: JSX syntax error (value="a".repeat(64)), wrong trailing-decimal expectation in hours mode ("7." passes through verbatim), and Radix Select cannot be driven by fireEvent.change - rewrote the unit-conversion test around the exported pure helper and hint assertions.
- Biome: unused React imports in the two new components + class-sort/format auto-fixed via biome check --write; remaining diagnostics in sparepart-management.tsx are pre-existing (unused TextField/DialogMode/defaultForm/createTaxonomyValue, cleanFilters conditions) - untouched.

### Completion Notes List

- Implemented exactly the Code Map set: V37 migration; entity fields + updateProcurement mutator; repository query existsByMaterialCodeIgnoreCaseAndIdNot; UserJobScopeReader port + JobScopeService + JobScopeForbiddenException (auth.application); MachineResponsibilityJobScopeAdapter + existsByUserIdAndResponsibilityLevelIn (machine.infrastructure); patchProcurement with role->job-scope->plant->duplicate ordering; SparepartAuditValues extended; PATCH endpoint + DUPLICATE_MATERIAL_CODE/JOB_SCOPE_REQUIRED mappings; SparepartView/DTOs extended.
- save() constraint translation refactored from isDuplicateSparepartViolation to a generic isConstraintViolation(exception, names...) distinguishing material-code duplicates from identity duplicates.
- Orval regenerated against the live backend (generate:snapshot pulled 36 paths incl. PATCH; usePatchSparepartProcurement generated). No manual edits to generated files.
- UI contract detail: clear uses omitted keys in the JSON payload (undefined dropped by JSON.stringify) which equals null semantics per the pinned contract - keeps strict TS types honest without null casts.
- Live verification (local stack): admin PATCH happy path 200 (values echoed), self-same case-insensitive code accepted, fractional 7.5 round-trip, VIEWER-with-LEADER-row denied 403 FORBIDDEN (app-role gate first), psql shows UPDATE audit rows with previous/new values; pilot sparepart restored to NULL afterwards via API (audited).

### Verification Performed

- AC 8.2-1 -> 8.2-SVC-001 (+ live 200 echo) - persist + list/detail exposure.
- AC 8.2-2 -> 8.2-SVC-003 case-insensitive duplicate rejected; DB index uq_spareparts_material_code proven by Testcontainers Flyway migrate + service pre-check.
- AC 8.2-3 -> full existing SparepartServiceIntegrationTest suite green unchanged (25 tests) proves install/lifetime flows unaffected.
- AC 8.2-4 -> 8.2-SVC-004 (7.5 -> 7.50 at NUMERIC(12,2)).
- AC 8.2-5 -> 8.2-SVC-005 + 8.2-API-003 (JOB_SCOPE_REQUIRED message naming LEADER-or-above; no mutation, no audit).
- AC 8.2-6 -> 8.2-SVC-002 + 8.2-SVC-009 + live psql inspection of audit_log rows.
- Commands: mvn test -Dtest="SparepartServiceIntegrationTest,SparepartControllerTest,MachineResponsibilityServiceIntegrationTest,AuditLogWiringIntegrationTest" -> 64 green (after fixes); npm run test:unit -> 204 green; tsc --noEmit exit 0; biome --write applied to touched files (residual diagnostics pre-existing only).

### Residual Risks

- Frontend cannot pre-gate by job scope (token carries application role only) - below-LEADER MANAGE users see enabled fields and receive the backend JOB_SCOPE_REQUIRED denial verbatim; acceptable per spec decision, revisit if a /auth/session scope claim lands.
- Pre-existing lint diagnostics remain in sparepart-management.tsx (dead helpers TextField/defaultForm/DialogMode/createTaxonomyValue) - out of story scope.

### File List

- syncro/apps/backend/src/main/resources/db/migration/V37__add_sparepart_procurement_fields.sql - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartEntity.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/infrastructure/SparepartRepository.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityRepository.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineResponsibilityJobScopeAdapter.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/auth/application/UserJobScopeReader.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/auth/application/JobScopeService.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/auth/application/JobScopeForbiddenException.java - NEW.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java - MODIFIED (patchProcurement, translation, views).
- syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartAuditValues.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartDtos.java - MODIFIED.
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartController.java - MODIFIED (PATCH).
- syncro/apps/backend/src/main/java/com/syncro/sparepart/api/SparepartExceptionHandler.java - MODIFIED (2 mappings).
- Backend tests: SparepartServiceIntegrationTest (+9 cases/helpers), SparepartControllerTest (helper fix + 5 PATCH cases).
- Frontend: generated client REGENERATED (openapi.json, syncro.ts, model/*); components/syncro/material-code-field.tsx + lead-time-input.tsx NEW (+2 test files); features/master-data/spareparts/sparepart-management.tsx MODIFIED (+test file NEW).

## Auto Run Result

Status: done

### Summary
Story 8.2 delivered procurement readiness inputs for spareparts: V37 migration (nullable globally-unique material_code with case-insensitive partial index + fractional lead_time_hours NUMERIC(12,2) with positive CHECK), first-ever PATCH /api/v1/spareparts/{id} replacing the procurement subset (null/omitted = clear), server-side job-scope LEADER-or-above enforcement behind an auth/machine boundary port (SUPER_ADMIN bypass), immutable audit entries with previous/new procurement values, extended list/detail views, regenerated Orval client, and MaterialCodeField + LeadTimeInput shared components integrated into the sparepart edit dialog with a PUT-then-conditional-PATCH save chain.

### Files changed (commit 891b8ee, 61 files)
- V37 migration; SparepartEntity/Repository/AuditValues; auth JobScopeService/UserJobScopeReader/JobScopeForbiddenException; machine MachineResponsibilityJobScopeAdapter + repository query; SparepartService.patchProcurement; PATCH endpoint + DUPLICATE_MATERIAL_CODE/JOB_SCOPE_REQUIRED mappings; extended DTOs/views; backend tests (27 service incl. new no-op + constraint-name + 2 prior, 26 controller incl. 5 PATCH, 7 JobScopeServiceTest).
- Frontend: regenerated generated client (34 orphaned model files force-added — pre-existing gitignore fragility fixed), MaterialCodeField, LeadTimeInput, sparepart-management integration + tests.

### Review findings breakdown
8 patched (1 high: gitignored generated client files would break fresh checkouts — force-added; 5 medium: no-op PATCH audit pollution + change detection, flaky unordered audit assertion, non-atomic PUT→PATCH UX + explicit failure messaging, wrong OpenAPI error schema + regen, job-scope ladder drift + fail-fast ordering with pin test; 2 low: client "positive" regex allowing 0, LeadTimeInput missing key). 5 rejected as by-design/pinned (job-scope breadth, omit-clears concurrency, unused readOnly props, pinned prose, openapi noise). 1 deferred (gitignore/generated fragility → deferred-work.md). Edge Case Hunter subagent could not complete (repeated network errors); triage from Blind Hunter pass only.

### Follow-up review recommendation
true — one HIGH (build-breaking untracked generated files) plus a security-adjacent no-op/audit change and an error-contract fix warrant an independent second pass.

### Verification performed
Backend: `mvn test -Dtest="SparepartServiceIntegrationTest,SparepartControllerTest,JobScopeServiceTest"` → 60/60 green (Testcontainers real PostgreSQL, V37 applied). Frontend: `npm run test:unit` → 204 green; `tsc --noEmit` exit 0; biome --write applied. Live: admin PATCH 200 with values echoed, self-same case-insensitive code accepted, fractional 7.5 round-trip, VIEWER-with-LEADER-row 403 FORBIDDEN, psql audit_log UPDATE rows inspected, pilot sparepart restored to NULL (audited).

### Residual risks
Job scope is coarse ("any LEADER+ row anywhere") by pinned contract — revisit resource scoping when 8.3 adds price entries. Client cannot pre-gate by job scope; below-LEADER MANAGE sees fields then receives backend denial verbatim. Generated-client gitignore fragility deferred. Pre-existing lint diagnostics in sparepart-management.tsx untouched.
