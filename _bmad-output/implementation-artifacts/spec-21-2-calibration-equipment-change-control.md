---
title: 'Story 21-2: Calibration & Equipment Change Control (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-06'
status: 'done'
baseline_revision: '72d0772'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-21-context.md'
  - '_bmad-output/implementation-artifacts/spec-21-1-non-conformance-8d-reports.md'
warnings: []
deferred:
  - summary: >-
      ECN lifecycle has no reject/return edge — UNDER_REVIEW is a dead end unless a manager approves; dispositioning a bad change notice is impossible.
    evidence: |-
      Spec chose forward-only transitions verbatim (Block-If/Always language); blind-hunter flags it as an intent-level product decision, same class as 21-1's INEFFECTIVE-reopen deferral.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/compliance/application/EquipmentChangeNoticeService.java
    severity: medium
  - summary: >-
      Calibration instruments can never be retired — no DELETE endpoint and no INACTIVE status; a disposed instrument reads EXPIRED forever and pollutes the overdue view.
    evidence: |-
      CalibrationStatus CHECK is VALID/EXPIRING_SOON/EXPIRED only (V1); adding a value needs a migration + product decision; same class as 21-1's no-DELETE deferral.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/compliance/application/CalibrationService.java
    severity: medium
  - summary: >-
      A failed calibration (result="FAIL") advances dates and re-snapshots status exactly like PASS; IATF 7.1.5.2.1 arguably wants FAIL to withhold confidence and/or spawn an NC.
    evidence: |-
      recalibrate treats result as free text with no consequence branching; the 21-1 NC surface exists but is never linked. Enumerating PASS/FAIL semantics is a product decision beyond this story's AC.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/compliance/application/CalibrationService.java
    severity: medium
  - summary: >-
      ECN execute/close stamp no actor or timestamp of their own (no executed_by/executed_at/closed_by columns); who/when is reconstructable only from the audit log.
    evidence: |-
      V1 has no such columns and V17 was scoped to plant_id/version/audit types; adding them is a separate migration decision. Audit rows carry the actor, so evidence exists — just not on the ECN record.
    location: >-
      syncro/apps/backend/src/main/resources/db/migration/V17__calibration_ecn_support.sql
    severity: low
  - summary: >-
      openapi.json not regenerated for the calibration/ECN endpoints; frontend/Orval cannot see the surface until a consumption story runs the contract pipeline.
    evidence: |-
      syncro/apps/web/openapi.json last touched at story 9-5 and lacks even 21-1's NC paths; spec explicitly scopes out frontend UI, so regeneration belongs to the consumption story.
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 15-2 landed `calibration_instruments`, `calibration_records`, `equipment_change_notices` (blueprint H) with mapped entities, but there is no API or application layer — measurement control and change control cannot be recorded, overdue calibration is invisible, and ECNs have no approval workflow, so IATF audit needs (NFR-P2-2) are unmet.

**Approach:** Build the compliance calibration + ECN layer mirroring 21-1: instrument CRUD with recalibration events writing append-only records, calibration status derived on read from `next_calibration_date` vs a typed-config window, ECN approval lifecycle (DRAFT→UNDER_REVIEW→APPROVED→EXECUTED→CLOSED) linked to machine/workorder evidence, V17 adds `plant_id` (nullable) + `version` to instruments, `version` to ECNs, and three audit entity types, service role gates + rego parity + audit-logged mutations. Backend only.

## Boundaries & Constraints

**Always:**
- Forward-only change; never edit V1..V16; V17 is additive (columns + audit CHECK drop/recreate preserving every existing value, V13/V14 pattern)
- Calibration status is DERIVED on read: `next_calibration_date` before today → EXPIRED; within `syncro.compliance.calibration.expiring-window-days` (typed `*Properties`, default 14) → EXPIRING_SOON; else stored status. Stored `status` column stays a write-time snapshot (create/recalibrate), never a scheduler's job
- Recalibration: POST record appends a `calibration_records` row (append-only, no update path) and advances the instrument's `last/next_calibration_date` + snapshot status; record's `next_calibration_date` must be after its `calibration_date`
- ECN transitions forward-only via `requireTransition` (21-1 pattern): submit DRAFT→UNDER_REVIEW; approve UNDER_REVIEW→APPROVED (stamps reviewed_by/approved_by/effective_date/sign_off_at); execute APPROVED→EXECUTED (optional `executed_wo_id` link, after_photo_url); close EXECUTED→CLOSED; anything else → 409 `INVALID_STATE_TRANSITION`
- Approve/execute/close: SUPER_ADMIN + MANAGER_MAINTENANCE only; create/submit: the 21-1 six-role mutation set; instruments/records mutations same six-role set
- Scope: ECN reads filter by machine plant-OR-group (21-1 `findScoped` predicate minus null-machine clause — `machine_id` is NOT NULL); instrument reads: SUPER_ADMIN unrestricted, others see `plant_id IS NULL OR plant_id IN :plantIds` (null = global instrument, visible to all authenticated, NC-unlinked precedent)
- Every mutation: service role gate (`ComplianceForbiddenException` → 403) AND rego rule for the same role set (parity) AND `AuditLogWriter.record` with previous/new values; audit types CALIBRATION_INSTRUMENT / CALIBRATION_RECORD / EQUIPMENT_CHANGE_NOTICE
- `instrument_code`/`ecn_number` client-supplied unique → 409 `DUPLICATE_IDENTIFIER`; unknown machine/workorder/record references → 404 with stable codes; `@Version` on instruments + ECNs → 409 `VERSION_CONFLICT`
- DTO records with Bean Validation; `Instant` UTC; `certificate_url`/`before_photo_url`/`after_photo_url` are TEXT passthrough (8D `pdfArtifactUrl` precedent — no Garage coupling this story); error envelope via `ComplianceExceptionHandler` (extend `assignableTypes`)

**Block If:**
- A change to V1..V16 or an existing API contract seems required → HALT (all 21-2 work is additive)

**Never:**
- No frontend UI (consumption surface is a later story)
- No scheduled sweep persisting calibration status (derived on read)
- No ECN separation-of-duty rule (approver≠submitter) — not in intent; no JPA relations across contexts (plain UUID columns); no new dependencies, no Lombok/MapStruct

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create instrument | POST valid, STAFF_MAINTENANCE | 201 + row + CREATE audit | 400 blank code/name; 409 duplicate code |
| Recalibrate | POST record, instrument exists | 201 record + instrument dates advanced + UPDATE audit | 404 INSTRUMENT_NOT_FOUND; 400 next<=date |
| Overdue read | next_calibration_date past | list `?status=EXPIRED` returns it (derived) | — |
| Expiring read | next within window | derived EXPIRING_SOON | — |
| ECN create+submit | POST then submit | DRAFT→UNDER_REVIEW, audit rows | 409 invalid transition |
| ECN approve | MANAGER, UNDER_REVIEW | APPROVED + reviewer/approver/date stamped | 403 for other roles |
| ECN execute | APPROVED, executed_wo_id valid | EXECUTED + WO link | 404 WORK_ORDER_NOT_FOUND |
| ECN close | EXECUTED | CLOSED | 409 from non-EXECUTED |
| Out-of-scope ECN read | leader, other plant's machine | absent from list; detail 404 | — |
| Concurrent ECN approve | stale version | 409 VERSION_CONFLICT | — |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `V1__orm_foundation_schema.sql:1578-1648` -- existing calibration/ECN DDL (read-only reference)
- `compliance/infrastructure/db/CalibrationInstrumentEntity.java:54,139` -- status enum + recalibrate() -- add plantId/@Version
- `compliance/infrastructure/db/CalibrationRecordEntity.java` -- append-only -- reuse as-is
- `compliance/infrastructure/db/EquipmentChangeNoticeEntity.java:179,190` -- review()/execute() -- add submit()/close() + @Version
- `compliance/application/NonConformanceService.java:83,193,204-255,293,303` -- role gate/scope/transition/audit/race patterns to mirror
- `compliance/api/NonConformanceController.java` + `ComplianceExceptionHandler.java:36` -- controller/advice pattern; advice assignableTypes must extend
- `compliance/infrastructure/db/NonConformanceRepository.java:26-50` -- findScoped/countVisible plant-OR-group JPQL template
- `org/application/OperationalScopeService.java:46` -- derive(user) single scope source
- `audit/domain/AuditEntityType.java:73` -- add 3 types (V17 CHECK)
- `V13__nc_severity_and_compliance_audit_types.sql:24-57` -- version column + CHECK drop/recreate template
- `authz/policy/authz.rego:440-450,1329-1365` -- compliance path sets + role rules pattern
- `.env.example:84` + `PmAuthzEnforcementParityTest.java:27-59` -- enforced paths + SET_BODY regex + rename guards
- Tests: `ComplianceIntegrationTest.java:85-113` (fixtures), `NonConformanceServiceTest.java:56-90`, `NonConformanceControllerTest.java:52-73`

## Tasks & Acceptance

**Execution:**
- `db/migration/V17__calibration_ecn_support.sql` -- create -- add `plant_id UUID NULL` FK + `version BIGINT DEFAULT 0` to calibration_instruments; `version` to equipment_change_notices; drop/recreate ck_audit_log_entity_type with CALIBRATION_INSTRUMENT/CALIBRATION_RECORD/EQUIPMENT_CHANGE_NOTICE (preserve all existing values); update V1BaseSchemaMigrationTest
- `compliance/domain/` + `config/` -- extend -- `CalibrationProperties` typed config (expiring-window-days, default 14, validated); reuse CalibrationStatus/EcnStatus enums
- `compliance/infrastructure/db/CalibrationInstrumentRepository.java` + `CalibrationRecordRepository.java` + `EquipmentChangeNoticeRepository.java` -- extend -- findScoped/countVisible twins (ECN machine join; instrument plant-OR-null), findByCode/Number
- `compliance/application/CalibrationService.java` -- create -- instrument CRUD + recalibrate (record append + date advance) + derived-status reads with status filter + scope + audit + role gates
- `compliance/application/EquipmentChangeNoticeService.java` -- create -- CRUD + submit/approve/execute/close transitions + WO link validation + scope + audit + role gates
- `compliance/api/CalibrationController.java` + `EquipmentChangeNoticeController.java` + `ComplianceDtos.java` + `ComplianceExceptionHandler.java` -- create/extend -- `/api/v1/calibration-instruments` (+`/{id}/records`, `/{id}/recalibrate`), `/api/v1/equipment-change-notices` (+`/{id}/submit|approve|execute|close`); stable codes INSTRUMENT_NOT_FOUND, CALIBRATION_RECORD_NOT_FOUND, ECN_NOT_FOUND, MACHINE_NOT_FOUND, WORK_ORDER_NOT_FOUND
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest` -- extend -- compliance_calibration_paths + compliance_ecn_paths sets, role rules (six-role mutate; MANAGER+SUPER_ADMIN approve/execute/close), env entries, regex + parsed-set guards
- Tests: `CalibrationServiceTest` (Mockito: derive matrix incl. window boundary, recalibrate, duplicate, scope), `EquipmentChangeNoticeServiceTest` (transition matrix, role gates, WO link), `CalibrationControllerTest` + `EquipmentChangeNoticeControllerTest` (@WebMvcTest envelope), `ComplianceCalibrationEcnIntegrationTest` (Testcontainers: V17 applies, derived status vs real dates, ECN lifecycle + audit rows, scope filtering, VERSION_CONFLICT)

**Acceptance Criteria:**
- Given calibration instruments exist, when managed via API, then instruments register with code/frequency/dates, recalibration appends a record and advances due dates, and overdue/upcoming are visible within scope via derived status (AC1)
- Given an ECN lifecycle, when submitted/approved/executed/closed, then each transition stamps actor/date, links machine + optional executed workorder evidence, is role-gated (rego+service parity), and audit-logged with previous/new values (AC2)
- Given invalid transitions, duplicates, out-of-scope reads, or stale versions, when submitted, then stable codes INVALID_STATE_TRANSITION / DUPLICATE_IDENTIFIER / 404 / VERSION_CONFLICT return (AC3)

## Spec Change Log

## Review Triage Log

### 2026-09-06 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 13: (high 3, medium 5, low 5)
- defer: 5
- reject: 6
- addressed_findings:
  - `[high]` `[patch]` Non-admin six-role user can create a plantId=null instrument = global, visible to every authenticated user (scope/privilege leak) — require plantId for non-SUPER_ADMIN.
  - `[high]` `[patch]` ECN content editable in any lifecycle state — approved/executed/closed artifacts can silently diverge from what was signed off — freeze content updates to DRAFT (409 otherwise).
  - `[high]` `[patch]` plantId one-way claim (global→plant by any six-role user) and never clearable — restrict plant assignment changes to SUPER_ADMIN.
  - `[medium]` `[patch]` create accepts lastCalibrationDate after nextCalibrationDate (recalibrate validates, create doesn't) + recalibrate accepts out-of-order history — add ordering guards.
  - `[medium]` `[patch]` PATCH blank-string renames (update DTOs lack the 21-1 requireNotBlankFields guard) + unbounded TEXT fields — add guards + @Size caps.
  - `[medium]` `[patch]` executed_wo_id validated for existence only — cross-plant WO evidence accepted — validate WO machine plant within ECN scope.
  - `[medium]` `[patch]` Test gaps: ECN ?status= non-null branch never executed; ECN/instrument PATCH null-keeps + audit unasserted; list ordering unasserted; duplicate-race classification untested both services; listRecords scope gate untested; 401 code string; invalid ?status= 400; MAINTENANCE_LEADER rego case.
  - `[medium]` `[patch]` Actor FK violations (recorded_by, submitted_by/reviewed_by/approved_by on deleted users) surface as unclassified 500 — classify to 403.
  - `[low]` `[patch]` CalibrationProperties expiring-window-days has no upper cap — huge value makes every read 500 (plusDays overflow) — cap ≤3650 + test.
  - `[low]` `[patch]` V17 adds plant_id without index (primary findScoped filter) + next_calibration_date ordering unindexed — add idx_calibration_instruments_plant + idx_calibration_instruments_next_date + no-backfill note (dev-phase reset, AD-22).
  - `[low]` `[patch]` @Version write-side only — expose version in InstrumentView/EcnView so clients can interpret VERSION_CONFLICT.
  - `[low]` `[patch]` Design Notes: single-step approve stamps reviewed_by=approved_by (honest, documented); calibration_frequency_days advisory (client supplies next date); normalize blank afterPhotoUrl to null.
  - `[low]` `[patch]` Integration test captures TODAY at class load (UTC-midnight flake) — use fixed clock.

## Design Notes

- Derived calibration status on read (no sweep): `ponytail: sweep job + persisted history only if an audit story needs status-over-time`. Stored snapshot keeps V1 DEFAULT 'VALID' semantics for writes; reads recompute so a passing midnight cannot hide an overdue instrument.
- Instrument `plant_id` nullable = global instrument visible to all authenticated (NC-unlinked precedent); scope is plant-only (no machine link to derive groups).
- ECN `change_type` stays free text (V1 has no CHECK); @Size(max=50) only.
- No SoD on ECN approval (intent silent); record it if a later IATF audit demands it.
- Single-step approve stamps `reviewed_by = approved_by` deliberately (review 21-2 L12) — there is no separate review step this story; a later review/approve split would add one.
- `calibration_frequency_days` is advisory (review 21-2 L12): the client supplies the recalibration's `next_calibration_date`; deriving it from the frequency is a later decision.
- Blank `afterPhotoUrl` on execute is normalized to null (review 21-2 L12) — never stored as an empty string.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Compliance*,*Calibration*,*EquipmentChange*,*Ecn*"` -- expected: green incl. Testcontainers V17
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest"` -- expected: parity + schema green
- `syncro/authz/run-opa-test.ps1` -- expected: all policy tests PASS

## Auto Run Result

**2026-09-07 — final verification after patch pass (surefire reports 08:04–08:33, OPA re-run in foreground):**

| Suite | Result |
|-------|--------|
| CalibrationServiceTest | 24/24 |
| EquipmentChangeNoticeServiceTest | 19/19 |
| CalibrationControllerTest | 12/12 |
| EquipmentChangeNoticeControllerTest | 11/11 |
| ComplianceCalibrationEcnIntegrationTest | 12/12 (Testcontainers, V17 applies) |
| ComplianceIntegrationTest (21-1 regression) | 8/8 |
| ComplianceEntityConventionIntegrationTest | 4/4 |
| CalibrationPropertiesTest | 2/2 |
| PmAuthzEnforcementParityTest | 1/1 |
| V1BaseSchemaMigrationTest | 34/34 |
| `run-opa-test.ps1` | **PASS: 512/512**, exit 0 (488 baseline + 24 calibration/ECN cases) |

Total: 127/127 green. The implementer's "OPA output looks wrong" note was a false alarm — the script runs `opa test -v` (per-test PASS lines; summary at tail), and the flagged command-2 suites passed on its re-run.

**Review outcome:** 13 patched (3 high / 5 medium / 5 low), 5 deferred, 6 rejected → `followup_review_recommended: true` (≥1 high patched).
