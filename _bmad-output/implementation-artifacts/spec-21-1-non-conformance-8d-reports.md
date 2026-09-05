---
title: 'Story 21-1: Non-Conformance & 8D Reports (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_revision: '85e56ff'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-21-context.md'
  - '_bmad-output/implementation-artifacts/spec-20-2-kpi-targets-dashboard-consumption.md'
warnings: []
deferred:
  - summary: >-
      No DELETE endpoint for NC/8D and no content lock after VERIFIED/EFFECTIVE —
      spec "CRUD" wording vs matrix/AC (no delete row); IATF immutability is a product decision.
    evidence: |-
      Blind-hunter + intent-audit: mis-keyed NC (wrong machine link, typo'd number) can never be
      corrected or removed; VERIFIED NC description/severity stay editable. ACs and I/O matrix
      never require delete or post-verification locking.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/api/NonConformanceController.java
    severity: medium
  - summary: >-
      NC list endpoint is unbounded (no pagination); WorkOrder precedent uses Pageable + countScoped.
    evidence: |-
      Blind-hunter + edge-case-hunter: list() returns all scope-visible rows; tolerable at pilot
      scale, revisit when compliance tables grow across plants.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/application/NonConformanceService.java
    severity: low
  - summary: >-
      8D state machine has no reopen edge out of INEFFECTIVE (IATF practice loops back to D5/D6).
    evidence: |-
      Blind-hunter: verify requires CLOSED; requireTransition allows only DRAFT→IN_PROGRESS→CLOSED.
      Spec defines these transitions verbatim, so this is intent-level, not an implementation defect.
    location: syncro/apps/backend/src/main/java/com/syncro/compliance/application/EightDReportService.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** Story 15-2 landed the compliance schema/entities (blueprint H1/H2: `non_conformances`, `eight_d_reports`) but there is no API or application layer — NCs and 8D reports cannot be created, updated, or read by any user, the NC table lacks the `severity` column the story AC requires, and no audit entity types exist for compliance mutations.

**Approach:** Forward migration V13 adds `non_conformances.severity` (uppercase enum MINOR/MAJOR/CRITICAL) and extends `ck_audit_log_entity_type` with NON_CONFORMANCE + EIGHT_D_REPORT (V12 pattern). Build the `compliance` api/application layer: NC CRUD + status transitions and one-per-NC 8D report with D1–D8 sections and effectiveness verification, service-level role gates + machine-scope filtering (WorkOrderService pattern), audit-logged mutations, rego coarse-gate rules + `SYNCRO_AUTHZ_ENFORCED_PATHS` entries (parity test enforces both sides). Backend only — no UI AC in this story.

## Boundaries & Constraints

**Always:**
- Migrations are forward-only (V13); never edit V1..V12; DB reset + reseed is the development-phase stance (AD-22)
- Every mutation: role gate in the service (throw `ComplianceForbiddenException` → 403 `FORBIDDEN`) AND rego coarse gate for the same role set (parity), AND `AuditLogWriter.record` with previous/new values and decisionId correlation
- NC status transitions: OPEN→IN_PROGRESS→CLOSED→VERIFIED; CLOSED stamps `closed_at` (server Clock) and requires rootCause + correctiveAction; VERIFIED only from CLOSED; anything else → 409 `INVALID_STATE_TRANSITION`
- 8D status: DRAFT→IN_PROGRESS→CLOSED→EFFECTIVE|INEFFECTIVE; verify-effectiveness stamps `effectiveness_verified_at`; one report per NC (`uq_eight_d_reports_nc`) → 409 `EIGHT_D_CONFLICT` on second create
- List/detail reads filter by machine scope: NCs with a `machine_id` are visible only when the machine's group is in the user's derived scope (`OperationalScopeService`); SUPER_ADMIN sees all; NCs without machine link are visible to any authenticated user
- `nc_number`/`report_number` are client-supplied unique business ids → duplicate → 409 `DUPLICATE_IDENTIFIER`
- DTO records with Bean Validation; `Instant` UTC timestamps; JSON fields (d1Team, d4RootCause) as `Map<String,Object>`; error envelope per-module `@RestControllerAdvice` (KpiExceptionHandler pattern)
- Mutation roles: SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, STAFF_MAINTENANCE, PRODUCTION_LEADER (workorder-create parity); effectiveness verification: SUPER_ADMIN, MANAGER_MAINTENANCE only; AUDITOR/TECHNICIAN read-only

**Block If:**
- A change to V1..V12 or an existing API contract seems required → HALT (all compliance work is additive)

**Never:**
- No frontend UI (no AC requires it; consumption surface is a later story)
- No JPA relations across bounded contexts — workorder/machine links stay plain UUID/VARCHAR columns; NC→evidence is the existing `workOrderId` reference (evidence via existing `/api/v1/workorders/{id}/attachments`)
- No new dependencies, no Lombok/MapStruct, no compliance tables beyond H1/H2 (21.2/21.3 own the rest)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create NC | POST valid body, STAFF_MAINTENANCE | 201 + row + CREATE audit | 400 VALIDATION_ERROR on blank description/ncNumber |
| Duplicate nc_number | POST existing number | 409 `DUPLICATE_IDENTIFIER` | — |
| Close NC without analysis | PATCH status=CLOSED, rootCause null | 400 VALIDATION_ERROR | — |
| Illegal NC transition | OPEN→VERIFIED | 409 `INVALID_STATE_TRANSITION` | — |
| Second 8D for one NC | POST eight-d again | 409 `EIGHT_D_CONFLICT` | — |
| Verify effectiveness | MANAGER, 8D CLOSED, verdict EFFECTIVE | 200 + verifiedAt + UPDATE audit | 403 for other roles |
| Technician lists NCs | TECHNICIAN, scope = own groups | only in-scope machine NCs + unlinked | 403 via rego on mutations |
| Unauthenticated | no JWT | 401 `AUTHENTICATION_REQUIRED` | SecurityConfig |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V12__kpi_target_audit_type.sql` -- CHECK-constraint drop/recreate pattern for V13
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1530-1576` -- existing `non_conformances`/`eight_d_reports` DDL (no severity column yet)
- `syncro/apps/backend/src/main/java/com/syncro/compliance/infrastructure/db/NonConformanceEntity.java` -- add `severity` field; `EightDReportEntity.java` -- add update helpers as needed
- `syncro/apps/backend/src/main/java/com/syncro/compliance/domain/` -- new `NcSeverity.java` enum; `NcStatus.java`, `EightDStatus.java` exist
- `syncro/apps/backend/src/main/java/com/syncro/compliance/infrastructure/db/NonConformanceRepository.java` -- add scope/status queries
- `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiTargetService.java` -- service pattern: role gate + audit + Clock + view records
- `syncro/apps/backend/src/main/java/com/syncro/kpi/api/KpiTargetController.java` + `KpiExceptionHandler.java` -- controller/advice/envelope pattern
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java:526` -- `requireCreateRole` role-set parity; `authz/application/PolicyDecisionPoint.java`, `org/application/OperationalScopeService.java` (single derived scope, AD-2)
- `syncro/authz/policy/authz.rego` -- `mutation_allowed` + path-set pattern (inventory examples ~633-704)
- `syncro/.env.example:81` -- `SYNCRO_AUTHZ_ENFORCED_PATHS` (append `/api/v1/non-conformances/**`)
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add NON_CONFORMANCE, EIGHT_D_REPORT
- Tests: `SparepartAlertControllerTest.java` (@WebMvcTest), `KpiTargetServiceTest.java` (Mockito), `AbstractPostgresIntegrationTest.java` (Testcontainers), `PmAuthzEnforcementParityTest.java` (rego↔env parity)

## Tasks & Acceptance

**Execution:**
1. `syncro/apps/backend/src/main/resources/db/migration/V13__nc_severity_and_compliance_audit_types.sql` -- create -- add `severity` column + extend audit entity_type CHECK (V12 pattern)
2. `compliance/domain/NcSeverity.java` + `NonConformanceEntity.java` + `AuditEntityType.java` -- extend -- enum, entity field, audit types
3. `compliance/application/NonConformanceService.java` -- create -- CRUD + transitions + scope-filtered reads + audit (role gates per Always)
4. `compliance/application/EightDReportService.java` -- create -- create/get/update D1–D8 + verify-effectiveness + audit
5. `compliance/infrastructure/db/NonConformanceRepository.java` + `EightDReportRepository.java` -- extend -- queries (status filter, machine-scope join via machines)
6. `compliance/api/NonConformanceController.java` + `ComplianceExceptionHandler.java` -- create -- REST surface `/api/v1/non-conformances` (+`/{id}/eight-d`), DTO records with validation, house error envelope
7. `syncro/authz/policy/authz.rego` + `syncro/.env.example` -- extend -- compliance paths + mutation_allowed rules (parity with service gates)
8. Tests: `NonConformanceServiceTest` (Mockito, transition/audit matrix), `NonConformanceControllerTest` (@WebMvcTest, 401/403/400/409 envelope), `ComplianceIntegrationTest` (Testcontainers: V13 applies, severity persists, audit rows written, scope filtering)

**Acceptance Criteria:**
- Given V13 runs on the fresh schema, when compliance tables are inspected, then `non_conformances` carries severity + status + timestamps and `eight_d_reports` holds D1–D8 linked 1:1 to the NC (AC1)
- Given an authorized user creates/updates an NC or 8D, when the mutation lands, then it is persisted, OPA-gated (rego + service), and audit-logged with previous/new values and decisionId (AC2)
- Given an out-of-scope machine NC, when a leader lists non-conformances, then it is absent from the response; the NC detail exposes its `workOrderId` evidence reference (AC2 "reference a workorder and its evidence")
- Given an invalid status transition or duplicate business number, when submitted, then stable codes `INVALID_STATE_TRANSITION` / `DUPLICATE_IDENTIFIER` / `EIGHT_D_CONFLICT` are returned

## Spec Change Log

## Review Triage Log

### 2026-09-05 — Review pass 2 (bmad-code-review: blind + edge + verification-gap + acceptance)
- patch: 4 (high 0, medium 4, low 0)
- defer: 1 (medium 1 — two shared-fork Testcontainers flakes; zero layer failures)
- reject: 10
- addressed_findings:
  - `[medium]` `[patch]` USER_NOT_FOUND guard unverified — any NonConformanceServiceTest create/update passed
    null responsibleId, so the countUser guard could be deleted silently. Added
    `unknownResponsibleReference` (404 USER_NOT_FOUND + nothing persisted).
  - `[medium]` `[patch]` Blank-PATCH guard unverified — no test sent a blank update field, so
    requireNotBlankFields could be removed silently. Added `blankUpdateFieldsRejected`
    (fieldErrors on description + rootCause, saveAndFlush never called).
  - `[medium]` `[patch]` referenceNotFound/reportNotFound HTTP mappings unexercised — MockMvc never
    stubbed ComplianceReferenceNotFoundException/EightDReportNotFoundException. Added
    `referenceNotFound` (404 MACHINE_NOT_FOUND) + `eightDReportNotFound` (404 EIGHT_D_REPORT_NOT_FOUND).
  - `[medium]` `[patch]` scope-gate create + audit plant dimension unasserted — INT-004 extended with the
    leader-on-sibling-machine 403 case and linked/unlinked audit plantId assertions.
  - `[medium]` `[defer]` Two Testcontainers suites sharing one forked JVM hit stale reused-container ports
    (ComplianceEntityConventionIntegrationTest passes 4/4 alone). Pre-existing infra flake in
    AbstractPostgresIntegrationTest reuse config — out of scope for 21-1; note for a later test-infra story.
- Rejected (10): unbounded list (spec deferred DW entry already); NcvView/EightDView version exposure
  (If-Match contract is a later-story surface, @Version still protects writes via 409); D1-D8 Map schema
  guards (intent-contract fixes JSON as Map<String,Object>; bean-level schema is later-story work);
  post-closure content freeze + NC/8D lifecycle linkage + reopen edges (intent-level product decisions —
  triage-log deferred entry already covers INEFFECTIVE; same class as no-DELETE decision); targetCloseDate
  temporal rules + overdue derivation (no intent row); audit null-plant visibility rule (AuditLogRepository.search
  already treats null as visible-to-all — P2 intent preserved); traceId propagation (per-module envelope
  precedent mints per-error ids); DataIntegrityViolation catch-all (P5 classification covers the only
  reachable constraints; unknown causes rethrow by design); null-user/role guard (SecurityConfig
  authenticated() + non-null enum parse make null unreachable); migration DROP/ADD atomicity (single-statement
  DDL auto-commits; Flyway wraps the script in one transaction).

### 2026-09-05 — Pre-commit repair (found while verifying task #1)
- Tests were stale against the reviewed contract: `NonConformanceServiceTest` called removed
  `countMachine`/`countVisible(3-arg)`/`findScoped(3-arg)`; fixed to `findMachineScope` + 4-arg
  queries. `ComplianceIntegrationTest.leaderOn` granted a plant assignment, which under the
  reviewed plant-OR-group predicate hides the sibling test — dropped the assignment so the
  leader is group-only.
- Triage-log P6 (VERSION_CONFLICT mapping) was only half-landed: @Version + V13 column existed
  but no `ObjectOptimisticLockingFailureException` handler, so concurrent updates escaped as 500.
  Added the 409 mapping + `NonConformanceControllerTest.optimisticLockConflict`.
- addressed_findings:
  - `[high]` `[patch]` VERSION_CONFLICT mapping completed (handler + contract test).
  - `[medium]` `[patch]` Unit + integration tests re-anchored to the reviewed scope contract.

### 2026-09-04 — Review pass 1
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 1, medium 4, low 3)
- defer: 3 (medium 1, low 2)
- reject: 12
- addressed_findings:
  - `[high]` `[patch]` Scope filter dropped the plant dimension (AD-2 "empty groupIds = plant fallback" inverted; plant-only MANAGER sees zero machine NCs) — mirror WorkOrderRepository.findScopedPage predicate (plant OR group) + discriminating integration test
  - `[medium]` `[patch]` Audit rows plantId=null leak compliance entries to all plants — resolve plantId from linked machine's plant
  - `[medium]` `[patch]` update() skips responsibleId validation (FK 500 escape) + create() doesn't scope-check machineId — add both checks
  - `[medium]` `[patch]` PATCH description:"" overwrites stored text — reject blank on update path
  - `[medium]` `[patch]` No optimistic locking on NC/8D rows — @Version + V13 version column + 409 VERSION_CONFLICT mapping
  - `[low]` `[patch]` Race classification walks only one cause level; fk_eight_d_reports_nc unclassified — walk full chain, classify FK
  - `[low]` `[patch]` CreateNcRequest.description missing @Size cap (asymmetric with update DTO)
  - `[low]` `[patch]` Missing tests: USER_NOT_FOUND create path; rego TECHNICIAN PATCH-denied + MAINTENANCE_LEADER verify-denied cases
- Failed layers: verification-gap (stalled at 600s watchdog; ground covered by blind-hunter + edge-case-hunter + intent-alignment).

## Design Notes

- Scope filter SQL: NC list joins `machines` on `machine_id` and filters `machines.machine_group_id IN (:groupIds)` OR `machine_id IS NULL` — same derived set OPA uses (AD-2 single scope source); `ponytail: null-machine NCs visible to all authenticated; tighten to managers if a quality-audit review demands it`.
- NC PATCH is partial-update (null keeps stored value, KPI-target precedent); status change rides the same PATCH with transition validation.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Compliance*,*NonConformance*,*EightD*"` -- expected: green incl. Testcontainers V13
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=PmAuthzEnforcementParityTest"` -- expected: rego↔env parity green
- `opa test syncro/authz/policy` (if opa CLI available; else covered by parity test) -- expected: policy tests green
