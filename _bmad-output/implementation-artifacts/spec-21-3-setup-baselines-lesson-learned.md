---
title: 'Story 21-3: Setup Baselines & Lesson Learned (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-06'
status: 'done'
baseline_revision: '8e7aeb8'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-21-context.md'
  - '_bmad-output/implementation-artifacts/spec-21-1-non-conformance-8d-reports.md'
  - '_bmad-output/implementation-artifacts/spec-21-2-calibration-equipment-change-control.md'
warnings: []
deferred:
  - summary: >-
      Tag search via tags::text containment false-positives on JSON structural characters and tag substrings (tag=setup matches setup-time).
    evidence: |-
      Spec-sanctioned mechanism (Always clause "tags::text contains"); LikePattern escapes \ % _ only. JSONB containment (@>) is the upgrade path if the consumption story needs exact tags.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/infrastructure/db/LessonLearnedRepository.java
    severity: low
  - summary: >-
      Baseline/lesson list endpoints are unbounded (no pagination) and JSONB payloads have no size cap.
    evidence: |-
      NC list precedent (21-1 deferred entry, same class); tolerable at pilot scale.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/api/LessonLearnedController.java
    severity: low
  - summary: >-
      Baseline audit entityLabel is the raw row UUID (lessons use projectId) — audit UI shows opaque labels for baseline events.
    evidence: |-
      Cosmetic/consistency only; spec does not pin entityLabel.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/application/MachineSetupBaselineService.java
    severity: low
  - summary: >-
      PATCH cannot clear an event link (null keeps stored value); detach requires deleting the source event.
    evidence: |-
      Spec silent on unlink semantics; FK ON DELETE SET NULL is the sanctioned clear path. A sentinel or unlink endpoint is a later-story decision.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/infrastructure/db/LessonLearnedEntity.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 15-2 landed `machine_setup_baselines` and `lesson_learned` (blueprint H) with mapped entities, but there is no API or application layer — setup standards cannot be versioned/recorded and lessons cannot be captured against NC/8D/workorder events, so IATF continuous-improvement evidence (NFR-P2-2) is missing. `lesson_learned` also has no columns to link the source event or attach evidence.

**Approach:** Build the compliance baseline + lesson layer mirroring 21-1/21-2: versioned machine setup baselines (parameters JSONB carrying tolerances/references, server-assigned version, activate-supersedes-siblings) and lesson-learned records (client-supplied unique `project_id` business key, optional NC/8D/workorder event links + JSONB evidence, scope-filtered substring/tag search). V18 adds event-link + evidence columns, `version` optimistic-lock columns, and two audit entity types. Backend only.

## Boundaries & Constraints

**Always:**
- Forward-only change; never edit V1..V17; V18 is additive (columns + audit CHECK drop/recreate preserving EVERY existing value including 21-2's CALIBRATION_*/EQUIPMENT_CHANGE_NOTICE, V13/V14/V17 pattern)
- Baseline: `machine_id` NOT NULL; `version` is server-assigned per machine (max+1, never client-supplied); creating a baseline activates it and deactivates the machine's other active baselines in the same transaction (PmChecksheetService.approve precedent); `parameters` JSONB holds the setup standard including tolerances and references (documented shape — no separate columns); optional `ecn_id` links to an equipment change notice (21-2 table)
- Lesson: `project_id` is the client-supplied unique business key (`uq_lesson_learned_project` retained — one lesson per project id, DUPLICATE_IDENTIFIER on clash); optional event links `nc_id`/`eight_d_id`/`work_order_id` (plain UUID/VARCHAR columns, FK ON DELETE SET NULL, validated to exist → 404 with stable codes); `evidence` JSONB array of `{objectKey, filename}` Garage references (8D `pdfArtifactUrl` passthrough precedent — no new attachment table, no Garage coupling this story); `tags` JSONB string array
- Reads filter by machine scope: baselines by plant-OR-group (21-1 `findScoped` predicate, `machine_id` NOT NULL); lessons by plant-OR-group OR `machine_id IS NULL` (visible to all authenticated, NC-unlinked precedent); SUPER_ADMIN unrestricted
- Search: lessons filterable by `q` substring over title/problem_summary (case-insensitive `LikePattern`) and by tag (`tags::text` contains); baselines filterable by machine + active-only flag
- Every mutation: service role gate (`ComplianceForbiddenException` → 403) AND rego rule for the same role set (parity) AND `AuditLogWriter.record` with previous/new values; audit types MACHINE_SETUP_BASELINE / LESSON_LEARNED
- Mutation roles: the 21-1 six-role set; AUDITOR/TECHNICIAN read-only; `@Version` on both → 409 `VERSION_CONFLICT`
- DTO records with Bean Validation; `Instant` UTC; JSON fields as `Map<String,Object>`/`List<String>` via `@JdbcTypeCode(SqlTypes.JSON)`; error envelope via `ComplianceExceptionHandler` (extend `assignableTypes` with the two new controllers)

**Block If:**
- A change to V1..V17 or an existing API contract seems required → HALT (all 21-3 work is additive; `uq_lesson_learned_project` is retained, not dropped)

**Never:**
- No frontend UI (consumption surface is a later story)
- No lesson status/lifecycle (AC has none; NC-style CHECK would be new scope)
- No JSONB schema validation beyond non-null shape (8D precedent forwards maps unvalidated); no JPA relations across contexts; no new dependencies, no Lombok/MapStruct

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create baseline | POST valid, STAFF_MAINTENANCE | 201 + version=1 active + CREATE audit | 400 blank machine; 404 MACHINE_NOT_FOUND |
| Create baseline unknown ecn_id | POST with dangling/out-of-scope ecnId | 404 ECN_NOT_FOUND (scope-checked, review M1) | — |
| Second baseline same machine | POST again | version=2 active, version=1 deactivated (both audited) | — |
| Activate specific version | POST /{id}/activate | that version active, siblings inactive | 404 BASELINE_NOT_FOUND |
| Activate already-active version | POST /{id}/activate | 200 unchanged, no audit, no lock bump (review L3) | — |
| Create lesson | POST valid, project_id unique | 201 + row + CREATE audit | 409 DUPLICATE_IDENTIFIER |
| DELETE lesson | DELETE /{id}, six-role | 204 + DELETE audit with previous values | 404 LESSON_NOT_FOUND |
| Lesson with event links | nc_id/eight_d_id/work_order_id valid | links persisted, evidence JSONB stored | 404 <ENTITY>_NOT_FOUND for unknown |
| Search lessons | ?q=forming&tag=setup | scope-visible matches only | — |
| Out-of-scope read | leader, other plant's machine | absent from list; detail 404 | — |
| Concurrent baseline activate | stale version | 409 VERSION_CONFLICT | — |
| Concurrent same-machine create | same max+1 | 409 VERSION_CONFLICT via uq_machine_setup_baselines_machine_version (review H1) | — |
| Technician mutates | TECHNICIAN | 403 (service + rego) | — |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `V1__orm_foundation_schema.sql:1650-1689` -- existing baseline/lesson DDL (read-only reference)
- `compliance/infrastructure/db/MachineSetupBaselineEntity.java:34-45,112-117` -- parameters JSONB + supersed() -- add @Version
- `compliance/infrastructure/db/LessonLearnedEntity.java:47-59` -- sparepartsUsed/tags JSONB -- add event links + evidence + @Version
- `compliance/infrastructure/db/MachineSetupBaselineRepository.java` / `LessonLearnedRepository.java` -- extend -- findByMachineIdAndActiveTrue, max-version, scoped + search queries
- `compliance/application/NonConformanceService.java:83,193-255,293-318` -- role gate/scope/transition/audit/race patterns to mirror
- `maintenance/preventive/application/PmChecksheetService.java:202-252` -- activate-supersedes-siblings precedent
- `compliance/api/ComplianceExceptionHandler.java:37` -- extend assignableTypes (21-2 already added calibration/ecn controllers)
- `compliance/infrastructure/db/NonConformanceRepository.java:26-50` -- findScoped/countVisible plant-OR-group JPQL template
- `common/LikePattern.java:21-31` + `AuthLoginAuditRepository.java:22` -- case-insensitive substring search precedent
- `audit/domain/AuditEntityType.java` -- add MACHINE_SETUP_BASELINE, LESSON_LEARNED (V18 CHECK)
- `V17__calibration_ecn_support.sql` (21-2) -- CHECK drop/recreate template to extend in V18
- `authz/policy/authz.rego` -- compliance path sets + role rules pattern (21-2 added calibration/ecn)
- `.env.example` + `PmAuthzEnforcementParityTest.java:27-59` -- enforced paths + SET_BODY regex + rename guards
- Tests: `ComplianceIntegrationTest.java:85-113`, `NonConformanceServiceTest.java:56-90`, `NonConformanceControllerTest.java:52-73`

## Tasks & Acceptance

**Execution:**
- `db/migration/V18__setup_baseline_lesson_evidence.sql` -- create -- add `nc_id UUID`/`eight_d_id UUID`/`work_order_id VARCHAR(50)` FKs (SET NULL) + `evidence JSONB` to lesson_learned; `version` on lesson_learned, `lock_version` on machine_setup_baselines (business `version` already occupies the name); partial unique indexes `uq_machine_setup_baselines_machine_version` (WHERE ecn_id IS NULL — NULLS DISTINCT fix, review H1) + `uq_one_active_baseline_per_machine` (WHERE is_active, review H2); drop/recreate ck_audit_log_entity_type with MACHINE_SETUP_BASELINE + LESSON_LEARNED (preserve all prior incl 21-2's three); update V1BaseSchemaMigrationTest
- `compliance/infrastructure/db/MachineSetupBaselineEntity.java` + `LessonLearnedEntity.java` -- extend -- @Version fields, lesson event-link + evidence fields + mutators
- `compliance/infrastructure/db/MachineSetupBaselineRepository.java` + `LessonLearnedRepository.java` -- extend -- findByMachineIdAndActiveTrue, maxVersion, findScoped/countVisible (baseline machine-join; lesson machine-join-or-null), search (q + tag)
- `compliance/application/MachineSetupBaselineService.java` -- create -- create (server version + supersede siblings) + activate + get/list scoped + audit + role gates
- `compliance/application/LessonLearnedService.java` -- create -- CRUD + event-link validation + scoped search + audit + role gates
- `compliance/api/MachineSetupBaselineController.java` + `LessonLearnedController.java` + `ComplianceDtos.java` + `ComplianceExceptionHandler.java` -- create/extend -- `/api/v1/machine-setup-baselines` (+`/{id}/activate`), `/api/v1/lessons-learned` (+`?q=&tag=`); stable codes BASELINE_NOT_FOUND, LESSON_NOT_FOUND, MACHINE_NOT_FOUND, WORK_ORDER_NOT_FOUND, NON_CONFORMANCE_NOT_FOUND, EIGHT_D_REPORT_NOT_FOUND
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest` -- extend -- compliance_baseline_paths + compliance_lesson_paths sets, six-role mutation rules, env entries, regex + parsed-set guards
- Tests: `MachineSetupBaselineServiceTest` (Mockito: version assignment, supersede, activate, scope), `LessonLearnedServiceTest` (event-link validation, search, duplicate), `MachineSetupBaselineControllerTest` + `LessonLearnedControllerTest` (@WebMvcTest envelope), `ComplianceBaselineLessonIntegrationTest` (Testcontainers: V18 applies, supersede flips is_active, event-link FKs, tag search, scope filtering, VERSION_CONFLICT)

**Acceptance Criteria:**
- Given a machine, when setup baselines are recorded, then each gets a server-assigned incrementing version, the newest is active and prior ones deactivated, parameters (incl tolerances/references) persist as JSONB (AC1)
- Given a lesson, when created with NC/8D/workorder links and evidence, then links validate to existing records (404 otherwise), evidence JSONB persists, and it is searchable within scope (AC2)
- Given any baseline/lesson mutation, when submitted, then it is OPA-gated (rego+service parity), audit-logged with previous/new values, and stale-version writes return VERSION_CONFLICT (AC3)

## Spec Change Log

- D1 (2026-09-07, review 21-3): V18 names the baseline optimistic-lock column `lock_version`, not `version` — `machine_setup_baselines.version INT NOT NULL` already exists in V1 as the server-assigned business version (part of `uq_machine_setup_baselines_machine_ecn_version`), so a second `version` column is impossible. `lesson_learned` uses plain `version` per the original task line. The Design Notes already treat the unique constraint and @Version as two distinct write guards.
- D2 (2026-09-07, review 21-3 H1/H2): V18 adds two partial unique indexes. Postgres UNIQUE is NULLS DISTINCT, so the existing (machine_id, ecn_id, version) constraint never fires for concurrent no-ECN creates with the same max+1 — `uq_machine_setup_baselines_machine_version (machine_id, version) WHERE ecn_id IS NULL` closes that race (classified → 409 VERSION_CONFLICT). `uq_one_active_baseline_per_machine (machine_id) WHERE is_active` DB-enforces the single-active invariant (the service's supersede loop deactivates siblings before inserting, so the index only catches races; classified → 409 VERSION_CONFLICT in create AND activate).
- D3 (2026-09-07, review 21-3 M1): baseline `ecn_id` validation is scope-checked (the caller must be able to SEE the ECN via the 21-2 countVisible predicate → 404 ECN_NOT_FOUND otherwise) — a global existence check would let a user pre-empt another plant's ECN through `uq_machine_setup_baselines_ecn` (one baseline per ECN). NC/8D/WO lesson links stay existence-only per spec intent (the existence-oracle concern was rejected for those).
- D4 (2026-09-07, review 21-3 L3): `activate()` on an already-active baseline short-circuits — 200 with the unchanged view, no audit row, no lock_version bump (idempotent repeat must not look like a state change).
- Deferred (review 21-3, not fixed): tag `::text` containment can false-positive on substring matches across the JSON array (spec-sanctioned shape); list endpoints are unbounded (NC precedent); lesson `entity_label` is the project id but baseline labels are the row UUID (cosmetic); PATCH cannot clear an event link (spec silent; FK ON DELETE SET NULL is the clear path).

## Review Triage Log

### 2026-09-07 — Review pass (4 layers: blind-hunter, edge-case-hunter, verification-gap, intent-alignment)
- intent_gap: 0 (intent-alignment verdict: PASS; all five declared deviations acceptable/bad_spec)
- bad_spec: 1 (D1 — "`version` to both tables" infeasible on machine_setup_baselines; amended to `lock_version`)
- patch: 18: (high 2, medium 5, low 11)
- defer: 4
- reject: 3
- addressed_findings:
  - `[high]` `[patch]` Postgres UNIQUE is NULLS DISTINCT — `uq_machine_setup_baselines_machine_ecn_version` never fires for concurrent no-ECN creates with same max+1 → duplicate version + two active rows; the unit race test was non-discriminating. V18 partial index `(machine_id, version) WHERE ecn_id IS NULL` + classification + discriminating integration test.
  - `[high]` `[patch]` "Exactly one active per machine" had no DB enforcement (cold-start window unprotected). V18 partial index `(machine_id) WHERE is_active`, classified in create AND activate catches.
  - `[medium]` `[patch]` Baseline `countEcn` global existence check let a user pre-empt another plant's ECN via `uq_machine_setup_baselines_ecn` — scope-checked via 21-2 countVisible predicate (D3).
  - `[medium]` `[patch]` TOCTOU FK violations (event links deleted between validation and flush) surfaced as unclassified 500 — shared `classifyWriteRace` maps the five FKs to stable 404 codes.
  - `[medium]` `[patch]` `activate()` on already-active baseline emitted no-op UPDATE audit + lock bump — short-circuited (D4).
  - `[medium]` `[patch]` Lesson `@Version` had zero behavioral test (controller test only mocked the handler) — integration stale-write test added.
  - `[medium]` `[patch]` V1 CHECK-preservation test spot-checked 17/~66 values — now asserts parsed CHECK set EQUALS `AuditEntityType.values()` (full parity, catches future drift).
  - `[low]` `[patch]` Test hardening: PATCH event-link validation, lesson MACHINE_NOT_FOUND, lesson audit plantId, activate 404, ECN-link happy path, groupIds sentinel arg, FORBIDDEN stubs moved to mutation endpoints + `$.version`/`$.lockVersion` serialization asserts, blank rootCause/solution fieldErrors, INVENTORY/STOREKEEPER rego denials.
  - `[low]` `[patch]` `@PositiveOrZero` on durationDays/reCycleCount; dead `fk_machine_setup_baselines_validated_by` catch branch removed.
- rejected:
  - NC/8D/WO existence-oracle scoping (spec intent is existence-only; links are plain UUID/VARCHAR columns; cross-scope pre-emption only real for ECN which was fixed).
  - `pg_advisory_xact_lock` alternative to the partial indexes (DB constraint is simpler and self-enforcing).
  - Dropping the `duplicateVersionRace` unit test (retargeted to the new index name instead — classification logic still deserves a unit pin).

## Design Notes

- `project_id` retained as the lesson's unique business key (client-supplied, like `nc_number`); event linkage lives in the NEW nc_id/eight_d_id/work_order_id columns, so "multiple lessons per project" is expressed as multiple project_ids, and `uq_lesson_learned_project` never needs dropping (Block-If avoided).
- Baseline tolerances/references fold into `parameters` JSONB (documented shape `{ "tolerances": {...}, "references": [...] }`) — no new columns; AC "parameters, tolerances, references" is one JSONB standard.
- `ponytail: version auto-increment per machine via max(version)+1 in the create tx; races are DB-enforced by the V18 partial unique indexes (review H1/H2 — the V1 (machine_id, ecn_id, version) constraint is NULLS DISTINCT and never fires for no-ECN rows); a dedicated sequence only if the 409-retry rate ever matters`.
- Evidence is a JSONB array of Garage object-key references (passthrough, presigned at read by the consumption story) — no attachment table, matching 8D `pdfArtifactUrl` posture.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Compliance*,*Baseline*,*Lesson*"` -- expected: green incl. Testcontainers V18
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest"` -- expected: parity + schema green
- `syncro/authz/run-opa-test.ps1` -- expected: all policy tests PASS

## Auto Run Result

**2026-09-07 — final verification after patch pass (surefire reports 14:00+, OPA re-run in foreground):**

| Suite | Result |
|-------|--------|
| MachineSetupBaselineServiceTest | 15/15 |
| LessonLearnedServiceTest | 19/19 |
| MachineSetupBaselineControllerTest | 9/9 |
| LessonLearnedControllerTest | 12/12 |
| ComplianceBaselineLessonIntegrationTest | 10/10 (Testcontainers, V18 applies) |
| ComplianceCalibrationEcnIntegrationTest (21-2 regression) | 12/12 |
| ComplianceIntegrationTest (21-1 regression) | 8/8 |
| ComplianceEntityConventionIntegrationTest (15-2 regression) | 4/4 |
| PmAuthzEnforcementParityTest | 1/1 |
| V1BaseSchemaMigrationTest | 36/36 (incl. full CHECK↔enum parity + V18 index assertions) |
| `run-opa-test.ps1` | **PASS: 534/534**, exit 0 (512 baseline + 22 baseline/lesson cases) |

Total: 126/126 green. The two legacy Testcontainers suites passed in the full run this time (the shared-container flake documented in 21-1/21-2 did not recur).

**Review outcome:** 18 patched (2 high / 5 medium / 11 low), 4 deferred, 3 rejected → `followup_review_recommended: true` (≥1 high patched).
