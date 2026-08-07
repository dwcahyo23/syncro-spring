---
workflowStatus: 'completed'
totalSteps: 5
stepsCompleted: ['step-01-detect-mode', 'step-02-load-context', 'step-03-risk-and-testability', 'step-04-coverage-plan', 'step-05-generate-output']
lastStep: 'step-05-generate-output'
nextStep: ''
lastSaved: '2026-08-07'
---

# Step 1: Detect Mode & Prerequisites

Mode: Epic-Level Mode.

Reason: User explicitly requested Epic 2 test design after Epic 1 TEA pre-retrospective. Epic/story requirements with acceptance criteria exist in `_bmad-output/planning-artifacts/epics.md`; project and architecture context exist in `_bmad-output/project-context.md` and planning artifacts.

Prerequisites: satisfied.

# Step 2: Load Context & Knowledge Base

Configuration loaded from `_bmad/tea/config.yaml`:
- `tea_use_playwright_utils: true`
- `tea_use_pactjs_utils: false`
- `tea_pact_mcp: none`
- `tea_browser_automation: auto`
- `test_stack_type: auto`
- `test_artifacts: _bmad-output/test-artifacts`

Detected stack: fullstack. Evidence: Maven backend under `syncro/apps/backend/pom.xml`; Next/React frontend under `syncro/apps/web/package.json`.

Input documents loaded:
- `_bmad-output/project-context.md`
- `_bmad-output/planning-artifacts/epics.md`
- `_bmad-output/planning-artifacts/architecture.md` (available context via project artifacts)
- `_bmad/tea/config.yaml`
- TEA knowledge: `risk-governance.md`, `probability-impact.md`, `test-levels-framework.md`, `test-priorities-matrix.md`, `nfr-criteria.md`, `test-quality.md`, `playwright-cli.md`

Existing test coverage found:
- Backend: auth, health, role, plant-scope controller/service/repository integration tests under `syncro/apps/backend/src/test/java/com/syncro/**`.
- Frontend app-owned tests: none found; only dependency tests under `node_modules`, ignored for coverage.
- E2E fixtures: placeholders only under `syncro/tests/e2e/.gitkeep` and `syncro/tests/fixtures/.gitkeep`.

Known coverage gaps for Epic 2:
- No master-data CRUD tests yet.
- No immutable audit-log tests yet.
- No frontend tests for Epic 2 tables/forms/states yet.
- No E2E path yet for plant-scoped master-data UI.

# Step 3: Risk & Testability Assessment

Epic-Level run: system-level testability review skipped by workflow rule. Risk and NFR planning performed.

## Risk Register

| ID | Category | Risk | P | I | Score | Priority | Mitigation | Owner | Timeline |
|---|---|---|---:|---:|---:|---|---|---|---|
| E2-R1 | DATA | Plant/machine-group/machine/sparepart mutations corrupt relational integrity or allow orphaned references. | 3 | 3 | 9 | P0 | Flyway constraints + repository integration tests with PostgreSQL/Testcontainers; API validation tests for missing/invalid FK and duplicate keys. | Backend | Story 2.1-2.7 before done |
| E2-R2 | SEC | MANAGE/VIEWER authorization or plant scope bypass allows cross-plant read/mutation. | 2 | 3 | 6 | P1 | Backend service-level permission tests for each CRUD endpoint; negative tests for out-of-scope plantId and VIEWER mutation 403. | Backend | Each Epic 2 story |
| E2-R3 | DATA | Immutable audit log missing, incomplete, mutable, or wrong before/after payload for master-data mutations. | 3 | 3 | 9 | P0 | Audit integration tests for create/update/delete where applicable; database immutability constraints; API tests verify actor/action/entity/timestamp/previous/new values. | Backend | Story 2.9 and mutation stories |
| E2-R4 | BUS | Sparepart installation lifetime baseline/threshold allows invalid values, causing later alert math to be wrong. | 2 | 3 | 6 | P1 | Unit tests for validation rules; integration/API tests for expected count, baseline counter, default 90%, override min/max. | Backend | Story 2.6 |
| E2-R5 | BUS | App role `MANAGE` confused with job scope `MANAGER`, creating wrong permissions or alert routing assumptions. | 2 | 3 | 6 | P1 | Contract/unit tests keep enums separate; API tests reject job-scope values where app role expected and vice versa. | Backend | Story 2.7 |
| E2-R6 | TECH | Frontend duplicates backend domain rules for status, permissions, or setup completeness and drifts from API. | 2 | 2 | 4 | P2 | Component/page tests with backend-provided fixtures; review rule: UI displays backend status/allowed actions, no recalculation. | Frontend | Story 2.1-2.8 |
| E2-R7 | OPS | Master-data pages lack loading/empty/error/read-only/forbidden states, hiding operational blockers. | 2 | 2 | 4 | P2 | UI state tests/manual browser evidence per page; shared state component patterns. | Frontend | Each UI story |
| E2-R8 | DATA | Duplicate machine group allowed within same plant or rejected across different plants contrary to requirements. | 3 | 2 | 6 | P1 | Unique constraint `(plant_id, name)` and integration/API tests for same-name different-plant allowed, same-plant duplicate rejected. | Backend | Story 2.2 |
| E2-R9 | DATA | Machine `ACTIVE/INACTIVE` manual state gets inferred from telemetry or stale state. | 2 | 3 | 6 | P1 | Domain/unit and API tests prove status set only by request/update; no telemetry dependency in master-data module. | Backend | Story 2.3 |
| E2-R10 | MAINT/TECH | Test suite becomes slow/flaky due CRUD setup through UI or shared DB state. | 2 | 2 | 4 | P2 | API/database factories with cleanup; Testcontainers for integration; E2E smoke only for one happy path per major flow. | QA/Dev | Epic 2 automation |

High risks: E2-R1, E2-R2, E2-R3, E2-R4, E2-R5, E2-R8, E2-R9.

## NFR Planning

| NFR Category | In Scope? | Threshold | Evidence Source |
|---|---|---|---|
| Security/AuthZ | Yes | No cross-plant access; VIEWER mutations return 403; backend enforces all permission-sensitive actions. | MockMvc/API tests, service tests, browser forbidden states. |
| Data Integrity | Yes | All FK/unique/check constraints enforced by PostgreSQL; migrations apply from empty DB. | Flyway/Testcontainers migration and repository tests. |
| Auditability/Compliance-readiness | Yes | UNKNOWN retention threshold; every master-data mutation creates immutable audit entry with actor/action/entity/previous/new/timestamp. | Integration tests; DB immutability tests; API tests. |
| Reliability | Yes | No explicit uptime/latency threshold. CRUD failures return safe error shape, not crashes. | API negative tests; frontend error states. |
| Performance | Limited | UNKNOWN list size/pagination threshold. Dense admin tables need server-side pagination later. | API pagination tests if implemented; manual large-list smoke deferred until data APIs exist. |
| Maintainability | Yes | No new framework/package manager; tests under 1.5 min each, <300 lines, isolated. | CI checks, test review checklist. |
| Accessibility | Yes | WCAG AA baseline; keyboard-operable forms/tables/errors. | Browser manual evidence; future Playwright accessibility assertions if E2E added. |

Clarification items:
1. Audit retention and delete/anonymization policy for master data audit entries: UNKNOWN.
2. Admin table pagination threshold and max page size: UNKNOWN.
3. Hard delete vs soft delete semantics for plants/machines/spareparts: not fully specified; affects audit and FK behavior.
4. Whether Plant CRUD in Story 2.1 owns plants table created in Story 1.7 or migrates/adds columns only: must preserve existing V2 data.

Highest mitigation priority: database constraints + service-level auth + immutable audit before broad UI polish.

# Step 4: Coverage Plan & Execution Strategy

## Coverage Matrix

| ID | Scenario | Requirement/Risk | Level | Priority | Rationale / Evidence |
|---|---|---|---|---|---|
| E2-API-001 | Plant CRUD preserves existing Story 1.7 `plants` data and validates required fields. | Story 2.1, E2-R1 | API/Integration | P0 | Migration + API tests prove no V2 regression and DB constraints hold. |
| E2-API-002 | Plant list/detail honors backend-derived plant scope for SUPER_ADMIN, MANAGE, VIEWER. | Story 2.1, E2-R2 | API/Service | P1 | Server-side scope enforcement, not UI hiding. |
| E2-API-003 | Machine group create/update enforces `(plant_id, name)` uniqueness. | Story 2.2, E2-R8 | API/Integration | P1 | Same name allowed across plants, rejected inside same plant. |
| E2-API-004 | Machine group cannot reference out-of-scope or missing plant. | Story 2.2, E2-R1/E2-R2 | API/Integration | P1 | FK + service auth negative paths. |
| E2-API-005 | Machine create/update validates plant, machine group, required identity fields, and duplicate keys. | Story 2.3, E2-R1 | API/Integration | P0 | Core master-data integrity. |
| E2-UNIT-001 | Machine `ACTIVE/INACTIVE` is request-owned manual state only. | Story 2.3, E2-R9 | Unit | P1 | Domain/service test proves no telemetry-derived transition. |
| E2-API-006 | Sparepart taxonomy create/update/delete validates hierarchy and duplicate names. | Story 2.4, E2-R1 | API/Integration | P1 | Prevents orphan taxonomy references. |
| E2-API-007 | Sparepart create/update validates taxonomy FK, part number uniqueness, and plant scope if plant-scoped. | Story 2.5, E2-R1/E2-R2 | API/Integration | P0 | Core data integrity + scope. |
| E2-UNIT-002 | Sparepart installation lifetime baseline and threshold rules reject invalid values. | Story 2.6, E2-R4 | Unit | P1 | Fast validation for count, baseline counter, default 90%, override bounds. |
| E2-API-008 | Sparepart installation API creates machine-sparepart relation and rejects invalid machine/sparepart FK. | Story 2.6, E2-R1/E2-R4 | API/Integration | P0 | Prevents invalid alert math seed data. |
| E2-UNIT-003 | Application roles and job-scope responsibility enum values remain separate. | Story 2.7, E2-R5 | Unit | P1 | Prevents MANAGE vs MANAGER semantic drift. |
| E2-API-009 | Responsibility assignment rejects app-role values where job-scope values expected. | Story 2.7, E2-R5 | API | P1 | Boundary validation catches wrong payloads. |
| E2-API-010 | Setup completeness endpoint reports backend-derived missing master-data prerequisites. | Story 2.8, E2-R6 | API/Service | P1 | UI consumes status, does not recalculate. |
| E2-COMP-001 | Master-data pages render loading, empty, error, read-only, forbidden, and success states. | Stories 2.1-2.8, E2-R7 | Component | P2 | UI state coverage without duplicating backend rules. |
| E2-COMP-002 | UI action availability follows backend permissions/status fixtures. | Stories 2.1-2.8, E2-R2/E2-R6 | Component | P2 | Prevents frontend drift and documents read-only behavior. |
| E2-API-011 | VIEWER mutation attempts return 403 for every master-data mutation endpoint. | Stories 2.1-2.7, E2-R2 | API | P1 | Backend security regression suite. |
| E2-API-012 | Out-of-scope plant reads/mutations return forbidden or not found consistently. | Stories 2.1-2.7, E2-R2 | API/Service | P1 | Plant-scope bypass prevention. |
| E2-API-013 | Each create/update/delete mutation writes audit entry with actor/action/entity/timestamp/before/after. | Story 2.9, E2-R3 | API/Integration | P0 | Core compliance-readiness evidence. |
| E2-DB-001 | Audit rows are immutable after insert. | Story 2.9, E2-R3 | DB/Integration | P0 | Database-level immutability, not only service convention. |
| E2-DB-002 | Flyway migrations apply from empty PostgreSQL and preserve Story 1.7 baseline. | Stories 2.1-2.9, E2-R1 | DB/Integration | P0 | Schema safety gate for all Epic 2 data work. |
| E2-E2E-001 | MANAGE user completes one plant-scoped master-data setup happy path from UI. | Stories 2.1-2.8, E2-R2/E2-R7 | E2E | P1 | One smoke path only; deeper CRUD stays API/component. |
| E2-E2E-002 | VIEWER opens master-data UI and sees read-only/forbidden states. | Stories 2.1-2.8, E2-R2/E2-R7 | E2E | P2 | Browser evidence for security UX. |

## NFR Coverage and Evidence Plan

| NFR | Planned Validation | Level / Tool | Expected Evidence | Status |
|---|---|---|---|---|
| Security/AuthZ | VIEWER mutation 403; out-of-scope plant read/mutation denied; UI forbidden/read-only states. | API/service tests, component tests, one E2E viewer smoke. | JUnit/MockMvc reports, component test report, Playwright screenshot/snapshot if E2E exists. | Required. |
| Data Integrity | FK, unique, check constraints; invalid references; migration from empty DB. | Flyway + PostgreSQL/Testcontainers integration tests. | Maven test report, Testcontainers migration log. | Required. |
| Auditability | Audit entry created for mutations; audit row immutable. | API/DB integration tests. | JUnit report plus DB immutability test evidence. | Required; retention policy UNKNOWN. |
| Reliability | API negative paths return safe error shape; frontend error states render. | API tests, component tests. | Test reports and UI state fixtures. | Required. |
| Performance | Pagination/list-size behavior if implemented; no hard threshold yet. | API tests if pagination exists; manual large-list smoke later. | Pending evidence; threshold missing. | Assumption / clarification needed. |
| Maintainability | Tests isolated, deterministic, under 1.5 min each, no shared DB state, no hard waits. | CI test review, suite timing. | CI/Maven/npm reports. | Required. |
| Accessibility | Keyboard-operable tables/forms/errors; forbidden/read-only states announced clearly. | Manual browser evidence now; future Playwright assertions if E2E added. | Browser notes/screenshots; later accessibility assertions. | Required baseline. |

Blockers/assumptions for later `nfr-assess`:
- Audit retention/delete/anonymization policy remains UNKNOWN.
- Admin table pagination threshold and max page size remain UNKNOWN.
- Hard delete vs soft delete semantics remain partially unspecified.
- Performance final verdict deferred until list size and pagination requirements exist.

## Execution Strategy

- **PR:** P0/P1 unit, API, DB integration, migration, and component tests when total suite remains under 15 minutes.
- **PR:** E2E only if one smoke path is stable and cheap; otherwise run tagged smoke before merge to release branch.
- **Nightly:** Full E2E smoke set, cross-role browser coverage, larger fixture matrix, frontend accessibility checks.
- **Weekly:** Large-list/pagination exploratory runs and any future performance/load checks after thresholds exist.

## Resource Estimates

| Priority | Estimate |
|---|---:|
| P0 | ~28–45 hours |
| P1 | ~35–60 hours |
| P2 | ~16–32 hours |
| P3 | ~0–6 hours |
| Total | ~79–143 hours |
| Timeline | ~2–4 calendar weeks depending on parallel backend/frontend capacity |

## Quality Gates

- P0 pass rate = 100% before Epic 2 release candidate.
- P1 pass rate >= 95%; any failing P1 needs documented waiver and owner.
- All high-risk mitigations for E2-R1, E2-R2, E2-R3, E2-R4, E2-R5, E2-R8, E2-R9 complete before release.
- Backend mutation endpoints must have authz, plant-scope, validation, and audit evidence before UI polish is accepted as done.
- Coverage target >= 80% for touched backend service/domain code and meaningful component state coverage for master-data pages.
- NFR evidence source identified for every in-scope NFR category.
- Full NFR PASS/CONCERNS/FAIL status deferred to `nfr-assess` after implementation evidence exists.

# Step 5: Generate Outputs & Validate

Mode used: Epic-Level Mode, sequential output generation.

Output file:
- `_bmad-output/test-artifacts/test-design-epic-2.md`

Validation summary:
- Template structure applied for epic-level single document.
- Risk assessment matrix included.
- NFR planning and evidence plan included without final PASS/CONCERNS/FAIL decisions.
- Coverage matrix mapped to priorities and test levels.
- Execution strategy kept PR / Nightly / Weekly and avoids re-listing tests.
- Resource estimates use ranges only.
- Quality gate criteria defined with P0 100%, P1 >=95%, high-risk mitigation completion, and NFR evidence expectations.
- CLI/browser sessions: none opened in this step, so no orphan sessions.
- Temp artifacts: none created outside `_bmad-output/test-artifacts/`.

Key open assumptions:
- Audit retention/delete/anonymization policy UNKNOWN.
- Admin table pagination threshold and max page size UNKNOWN.
- Hard delete vs soft delete semantics partly unspecified.
- Performance final verdict deferred until list size and pagination requirements exist.

Workflow status: completed.

---

# ATDD Gap-Closing Run: 2-9 Immutable Audit Log for Master Data

Run ID: `20260807-214614-fc56` (bmad-testarch-atdd, RED phase)
Date: 2026-08-07
Baseline: `283d36b232058a90af50ba734e896ea85041a3f1`

## Mode

Red-phase ATDD scaffold generation for the gaps identified in `test-design-story-2-9-immutable-audit-log.md` (story already implemented; this run produces RED scaffolds + implementation checklist).

## Output

- `_bmad-output/test-artifacts/atdd-checklist-2-9-implement-immutable-audit-log-for-master-data.md`
- `syncro/apps/backend/src/test/java/com/syncro/audit/api/AuditLogAtddGapApiScaffoldTest.java` (4 `@Disabled`)
- `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java` (5 `@Disabled`)
- `syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx` (7 `it.skip`)
- `syncro/apps/web/tests/e2e/audit-log.atdd-red.spec.ts` (5 `test.skip`)

## Flagship RED Tests

- `2.9-SVC-017` R-2.9-1: `UPDATE audit_log SET plant_id/id` must raise — FAILS today (trigger `OF`-list gap).
- `[P0]` R-2.9-6: page resets to 0 on entityType/actor/from/to change — FAILS today (`audit-log-page.tsx`).

## Verification

- Backend scaffolds compile (`mvn test-compile`, JDK 25).
- Frontend scaffold: Biome clean, Vitest runs 7 skipped.
- Remaining P1/P2 items are activation locks (expected green once activated).

Workflow status: completed.

---

# Story-Level Run: 2-9 Immutable Audit Log for Master Data

Run ID: `20260807-214614-fc56`
Date: 2026-08-07
Baseline: `283d36b232058a90af50ba734e896ea85041a3f1`

## Mode

Story-level risk + coverage design for story 2-9, produced as evidence-grounded follow-up to the Epic 2 test design (story implementation already exists; this run verifies risk coverage against actual code).

## Output

- `_bmad-output/test-artifacts/test-design-story-2-9-immutable-audit-log.md`

## Risk Summary

Story-level register (14 risks) mapped from code review of the shipped implementation:

- P0 (score 9): R-2.9-1 DB immutability trigger `OF`-list omits `plant_id`/`id` — `UPDATE audit_log SET plant_id` succeeds via direct SQL today (existing test only covers `actor_name`).
- P0 (score 6): R-2.9-2 all 7 aggregate wiring, R-2.9-3 same-tx capture, R-2.9-4 plant-scope reads, R-2.9-5 payload fidelity.
- P1 (score 4-6): R-2.9-6 frontend stale page on filter change, R-2.9-7 role/authn, R-2.9-8 invalid filter 400s, R-2.9-9 timezone boundary, R-2.9-10 Orval client drift.
- P2/P3: FK SET NULL on plant delete, corrupt-JSON 500, LIKE escaping, composite-index NFR, retention/ops.

## Coverage Summary

- P0: 9 scenarios (~14-20 h), P1: 9 (~16-24 h), P2: 6 (~6-12 h), P3: 3 manual/ops (~2-6 h).
- Total ~38-62 h. Reuses 22 existing tests (6 API + 8 service + 8 wiring).

## Follow-Up Gaps Flagged

1. Decide/fix R-2.9-1 (add `plant_id` handling or accept + document) + add Testcontainers probe.
2. Frontend page-reset fix in `audit-log-page.tsx` (entityType/actor/from/to/sort do not reset page).
3. No frontend test harness — add component-level coverage.
4. Missing API 400 tests for bad `sort` and malformed `from`/`to`.
5. Retention/cleanup decision for the append-only table (P3/ops).
6. Operator actions remain the runtime/UI proof gate (spec status: awaiting-operator).

Workflow status: completed.

