---
workflowStatus: 'completed'
totalSteps: 5
stepsCompleted: ['step-01-detect-mode', 'step-02-load-context', 'step-03-risk-and-testability', 'step-04-coverage-plan', 'step-05-generate-output']
lastStep: 'step-05-generate-output'
nextStep: ''
lastSaved: '2026-05-27'
---

# Test Design: Epic 2 - Master Data & Setup Foundation

**Date:** 2026-05-27  
**Author:** Yusuf  
**Status:** Draft

---

## Executive Summary

**Scope:** Epic-level test design for Epic 2: master-data CRUD/setup foundation, plant scope adoption, immutable audit log, data integrity, backend authorization, and frontend states.

**Risk Summary:**

- Total risks identified: 10
- High-priority risks (score >= 6): 7
- Critical categories: DATA, SEC, BUS
- Highest mitigation priority: database constraints + service-level auth + immutable audit before broad UI polish.

**Coverage Summary:**

- P0 scenarios: 7 (~28-45 hours)
- P1 scenarios: 11 (~35-60 hours)
- P2 scenarios: 4 (~16-32 hours)
- P3 scenarios: 0-2 exploratory items (~0-6 hours)
- Total effort: ~79-143 hours (~2-4 calendar weeks depending on parallel backend/frontend capacity)

---

## Not in Scope

| Item | Reasoning | Mitigation |
|---|---|---|
| Full performance/load benchmark | Epic 2 has no explicit list-size or pagination threshold yet. | Defer to weekly exploratory/performance checks after pagination requirements exist. |
| Full NFR PASS/CONCERNS/FAIL decision | Test design plans evidence only; implementation evidence does not exist yet. | Run `nfr-assess` after Epic 2 implementation and test evidence exist. |
| Broad UI CRUD E2E matrix | Would duplicate API/component coverage and increase flakiness. | Keep E2E to one MANAGE happy path and one VIEWER read-only/forbidden smoke. |
| Contract testing with Pact | `tea_use_pactjs_utils: false`; no external contract scope identified for Epic 2. | Revisit only if Epic 2 exposes or consumes cross-service contracts. |

---

## Risk Assessment

### High-Priority Risks (Score >= 6)

| Risk ID | Category | Description | Probability | Impact | Score | Priority | Mitigation | Owner | Timeline |
|---|---|---:|---:|---:|---:|---|---|---|---|
| E2-R1 | DATA | Plant/machine-group/machine/sparepart mutations corrupt relational integrity or allow orphaned references. | 3 | 3 | 9 | P0 | Flyway constraints + repository integration tests with PostgreSQL/Testcontainers; API validation tests for missing/invalid FK and duplicate keys. | Backend | Story 2.1-2.7 before done |
| E2-R2 | SEC | MANAGE/VIEWER authorization or plant scope bypass allows cross-plant read/mutation. | 2 | 3 | 6 | P1 | Backend service-level permission tests for each CRUD endpoint; negative tests for out-of-scope plantId and VIEWER mutation 403. | Backend | Each Epic 2 story |
| E2-R3 | DATA | Immutable audit log missing, incomplete, mutable, or wrong before/after payload for master-data mutations. | 3 | 3 | 9 | P0 | Audit integration tests for create/update/delete where applicable; database immutability constraints; API tests verify actor/action/entity/timestamp/previous/new values. | Backend | Story 2.9 and mutation stories |
| E2-R4 | BUS | Sparepart installation lifetime baseline/threshold allows invalid values, causing later alert math to be wrong. | 2 | 3 | 6 | P1 | Unit tests for validation rules; integration/API tests for expected count, baseline counter, default 90%, override min/max. | Backend | Story 2.6 |
| E2-R5 | BUS | App role `MANAGE` confused with job scope `MANAGER`, creating wrong permissions or alert routing assumptions. | 2 | 3 | 6 | P1 | Contract/unit tests keep enums separate; API tests reject job-scope values where app role expected and vice versa. | Backend | Story 2.7 |
| E2-R8 | DATA | Duplicate machine group allowed within same plant or rejected across different plants contrary to requirements. | 3 | 2 | 6 | P1 | Unique constraint `(plant_id, name)` and integration/API tests for same-name different-plant allowed, same-plant duplicate rejected. | Backend | Story 2.2 |
| E2-R9 | DATA | Machine `ACTIVE/INACTIVE` manual state gets inferred from telemetry or stale state. | 2 | 3 | 6 | P1 | Domain/unit and API tests prove status set only by request/update; no telemetry dependency in master-data module. | Backend | Story 2.3 |

### Medium-Priority Risks (Score 3-4)

| Risk ID | Category | Description | Probability | Impact | Score | Priority | Mitigation | Owner |
|---|---|---:|---:|---:|---:|---|---|---|
| E2-R6 | TECH | Frontend duplicates backend domain rules for status, permissions, or setup completeness and drifts from API. | 2 | 2 | 4 | P2 | Component/page tests with backend-provided fixtures; review rule: UI displays backend status/allowed actions, no recalculation. | Frontend |
| E2-R7 | OPS | Master-data pages lack loading/empty/error/read-only/forbidden states, hiding operational blockers. | 2 | 2 | 4 | P2 | UI state tests/manual browser evidence per page; shared state component patterns. | Frontend |
| E2-R10 | MAINT/TECH | Test suite becomes slow/flaky due CRUD setup through UI or shared DB state. | 2 | 2 | 4 | P2 | API/database factories with cleanup; Testcontainers for integration; E2E smoke only for one happy path per major flow. | QA/Dev |

### Low-Priority Risks (Score 1-2)

None identified from current Epic 2 evidence.

### Risk Category Legend

- **TECH**: Technical/architecture risks.
- **SEC**: Security and access-control risks.
- **PERF**: Performance/scalability risks.
- **DATA**: Data integrity and consistency risks.
- **BUS**: Business-rule risks.
- **OPS**: Operational usability/support risks.

---

## NFR Planning

**Purpose:** Capture Epic 2 NFR thresholds, planned validation, and evidence expected for later `nfr-assess`. This is not final NFR evidence assessment.

| NFR Category | Requirement / Threshold | Risk Link | Planned Validation | Evidence Needed |
|---|---|---|---|---|
| Security/AuthZ | No cross-plant access; VIEWER mutations return 403; backend enforces all permission-sensitive actions. | E2-R2 | API/service tests for permission-sensitive actions; component/E2E evidence for forbidden/read-only states. | JUnit/MockMvc reports, component report, browser screenshot/snapshot if E2E exists. |
| Data Integrity | PostgreSQL enforces FK/unique/check constraints; Flyway migrations apply from empty DB. | E2-R1, E2-R8 | Flyway + PostgreSQL/Testcontainers integration tests; API negative tests for invalid references and duplicates. | Maven test report, Testcontainers migration log. |
| Auditability | Every master-data mutation creates immutable audit entry with actor/action/entity/timestamp/before/after. Retention threshold UNKNOWN. | E2-R3 | API/DB integration tests for audit creation; DB immutability test. | JUnit report plus DB immutability evidence. |
| Reliability | CRUD failures return safe error shape; frontend renders error state. No explicit uptime/latency threshold. | E2-R7 | API negative tests; component tests for loading/empty/error states. | Test reports and UI state fixtures. |
| Performance | Pagination/list-size threshold UNKNOWN. Dense admin tables likely need server-side pagination later. | E2-R10 | API pagination tests if pagination exists; large-list smoke after requirements exist. | Pending threshold/evidence. |
| Maintainability | No new framework/package manager; tests isolated, deterministic, under 1.5 min each, under 300 lines. | E2-R10 | CI test review and suite timing. | CI/Maven/npm reports. |
| Accessibility | WCAG AA baseline; keyboard-operable forms/tables/errors. | E2-R7 | Manual browser evidence now; future Playwright accessibility assertions if E2E added. | Browser notes/screenshots; later accessibility assertions. |

**Unknown thresholds / clarification items:**

1. Audit retention and delete/anonymization policy for master-data audit entries: UNKNOWN.
2. Admin table pagination threshold and max page size: UNKNOWN.
3. Hard delete vs soft delete semantics for plants/machines/spareparts: not fully specified.
4. Story 2.1 migration strategy must preserve existing Story 1.7 `plants` table data.

---

## Entry Criteria

- [ ] Epic 2 story acceptance criteria agreed by QA, Dev, PM.
- [ ] Story 2.1 migration plan preserves existing Story 1.7 `plants` data.
- [ ] Backend test environment supports PostgreSQL/Testcontainers and Flyway from empty DB.
- [ ] Test data factories exist or are planned for plant, machine group, machine, sparepart taxonomy, sparepart, installation, responsibility assignment, and users/roles/scopes.
- [ ] Auth fixtures exist for SUPER_ADMIN, MANAGE, VIEWER, and no-plant users.
- [ ] Audit retention/delete semantics either decided or explicitly waived as UNKNOWN for this epic.

## Exit Criteria

- [ ] All P0 tests pass 100%.
- [ ] P1 pass rate >= 95%, with owner and waiver for any failure.
- [ ] No open high-severity bug tied to E2-R1, E2-R2, E2-R3, E2-R4, E2-R5, E2-R8, or E2-R9.
- [ ] Backend mutation endpoints have validation, authz, plant-scope, and audit evidence before UI polish is accepted as done.
- [ ] NFR evidence sources exist for each in-scope NFR category; final status deferred to `nfr-assess`.

---

## Test Coverage Plan

**Note:** P0/P1/P2/P3 = priority/risk, not execution timing. Execution timing is defined separately in Execution Strategy.

### P0 (Critical)

**Criteria:** Blocks core functionality + high risk + no workaround.

| Test ID | Requirement | Test Level | Risk Link | Owner | Notes |
|---|---|---|---|---|---|
| E2-API-001 | Plant CRUD preserves existing Story 1.7 `plants` data and validates required fields. | API/Integration | E2-R1 | Backend/QA | Migration + API evidence. |
| E2-API-005 | Machine create/update validates plant, machine group, required identity fields, and duplicate keys. | API/Integration | E2-R1 | Backend/QA | Core master-data integrity. |
| E2-API-007 | Sparepart create/update validates taxonomy FK, part number uniqueness, and plant scope if plant-scoped. | API/Integration | E2-R1/E2-R2 | Backend/QA | Core data integrity + scope. |
| E2-API-008 | Sparepart installation API creates machine-sparepart relation and rejects invalid machine/sparepart FK. | API/Integration | E2-R1/E2-R4 | Backend/QA | Prevents invalid alert math seed data. |
| E2-API-013 | Each create/update/delete mutation writes audit entry with actor/action/entity/timestamp/before/after. | API/Integration | E2-R3 | Backend/QA | Core compliance-readiness evidence. |
| E2-DB-001 | Audit rows are immutable after insert. | DB/Integration | E2-R3 | Backend/QA | DB-level guard. |
| E2-DB-002 | Flyway migrations apply from empty PostgreSQL and preserve Story 1.7 baseline. | DB/Integration | E2-R1 | Backend/QA | Schema safety gate. |

**Total P0:** 7 scenarios, ~28-45 hours.

### P1 (High)

**Criteria:** Important feature, medium/high risk, common workflow.

| Test ID | Requirement | Test Level | Risk Link | Owner | Notes |
|---|---|---|---|---|---|
| E2-API-002 | Plant list/detail honors backend-derived plant scope for SUPER_ADMIN, MANAGE, VIEWER. | API/Service | E2-R2 | Backend/QA | Server-side scope. |
| E2-API-003 | Machine group create/update enforces `(plant_id, name)` uniqueness. | API/Integration | E2-R8 | Backend/QA | Same-name cross-plant allowed; same-plant rejected. |
| E2-API-004 | Machine group cannot reference out-of-scope or missing plant. | API/Integration | E2-R1/E2-R2 | Backend/QA | FK + authz negative paths. |
| E2-UNIT-001 | Machine `ACTIVE/INACTIVE` is request-owned manual state only. | Unit | E2-R9 | Backend | No telemetry-derived transition. |
| E2-API-006 | Sparepart taxonomy create/update/delete validates hierarchy and duplicate names. | API/Integration | E2-R1 | Backend/QA | Prevents orphan references. |
| E2-UNIT-002 | Sparepart installation lifetime baseline and threshold rules reject invalid values. | Unit | E2-R4 | Backend | Fast business-rule coverage. |
| E2-UNIT-003 | Application roles and job-scope responsibility enum values remain separate. | Unit | E2-R5 | Backend | Prevents MANAGE vs MANAGER drift. |
| E2-API-009 | Responsibility assignment rejects app-role values where job-scope values expected. | API | E2-R5 | Backend/QA | Boundary validation. |
| E2-API-010 | Setup completeness endpoint reports backend-derived missing master-data prerequisites. | API/Service | E2-R6 | Backend/QA | UI consumes backend status. |
| E2-API-011 | VIEWER mutation attempts return 403 for every master-data mutation endpoint. | API | E2-R2 | Backend/QA | Security regression suite. |
| E2-API-012 | Out-of-scope plant reads/mutations return forbidden or not found consistently. | API/Service | E2-R2 | Backend/QA | Plant-scope bypass prevention. |
| E2-E2E-001 | MANAGE user completes one plant-scoped master-data setup happy path from UI. | E2E | E2-R2/E2-R7 | QA/Frontend | One stable smoke only. |

**Total P1:** 12 scenarios, ~35-60 hours.

### P2 (Medium)

**Criteria:** Secondary flow, lower risk, UI states, edge cases.

| Test ID | Requirement | Test Level | Risk Link | Owner | Notes |
|---|---|---|---|---|---|
| E2-COMP-001 | Master-data pages render loading, empty, error, read-only, forbidden, and success states. | Component | E2-R7 | Frontend/QA | State coverage without backend rule duplication. |
| E2-COMP-002 | UI action availability follows backend permissions/status fixtures. | Component | E2-R2/E2-R6 | Frontend/QA | Prevents frontend drift. |
| E2-E2E-002 | VIEWER opens master-data UI and sees read-only/forbidden states. | E2E | E2-R2/E2-R7 | QA/Frontend | Browser UX evidence. |
| E2-MAINT-001 | Test factories clean up DB state and avoid UI setup for CRUD tests. | Review/CI | E2-R10 | QA/Dev | Flake prevention. |

**Total P2:** 4 scenarios, ~16-32 hours.

### P3 (Low)

**Criteria:** Nice-to-have, exploratory, benchmark.

| Requirement | Test Level | Owner | Notes |
|---|---|---|---|
| Large-list master-data exploratory smoke after pagination threshold exists. | Exploratory/API | QA | Deferred until threshold and page-size requirements exist. |
| Additional accessibility assertions in Playwright after stable UI routes exist. | E2E/Accessibility | QA/Frontend | Add when E2E harness exists. |

**Total P3:** 0-2 optional items, ~0-6 hours.

---

## Execution Strategy

Philosophy: run everything in PRs if total functional suite remains under 15 minutes; defer only expensive, flaky, long-running, or threshold-dependent checks.

- **PR:** P0/P1 unit, API, DB integration, migration, and component tests when total suite remains under 15 minutes.
- **PR:** One stable E2E smoke can run if cheap; otherwise run tagged smoke before release branch merge.
- **Nightly:** Full E2E smoke set, cross-role browser coverage, larger fixture matrix, frontend accessibility checks.
- **Weekly:** Large-list/pagination exploratory runs and future performance/load checks after thresholds exist.

---

## Resource Estimates

| Priority | Estimate | Notes |
|---|---:|---|
| P0 | ~28-45 hours | DB/Flyway/audit setup and integration fixtures dominate. |
| P1 | ~35-60 hours | Broad API/security/business-rule coverage. |
| P2 | ~16-32 hours | Component states, E2E viewer smoke, fixture hardening. |
| P3 | ~0-6 hours | Optional exploratory only. |
| Total | ~79-143 hours | Includes setup and uncertainty. |
| Timeline | ~2-4 calendar weeks | Depends on parallel backend/frontend capacity. |

### Prerequisites

**Test Data:**

- Plant, machine group, machine, sparepart taxonomy, sparepart, sparepart installation, responsibility assignment factories.
- Auth fixtures for SUPER_ADMIN, MANAGE, VIEWER, and no-plant user.
- Audit fixture helpers for actor/action/entity/before/after assertions.

**Tooling:**

- JUnit/MockMvc or equivalent backend API test stack already present.
- PostgreSQL/Testcontainers for migration/repository/API integration tests.
- Frontend component test tool if adopted for Epic 2 UI state coverage.
- Playwright/browser automation only for limited E2E smoke and manual evidence.

**Environment:**

- Local/test PostgreSQL available through Testcontainers.
- Frontend/backend dev stack available for browser evidence.
- No secrets or local runtime artifacts committed.

---

## Quality Gate Criteria

### Pass/Fail Thresholds

- **P0 pass rate:** 100% before Epic 2 release candidate.
- **P1 pass rate:** >= 95%; any failing P1 needs documented waiver and owner.
- **P2 pass rate:** >= 90% or documented as non-blocking with owner.
- **High-risk mitigations:** 100% complete or explicitly waived for E2-R1, E2-R2, E2-R3, E2-R4, E2-R5, E2-R8, E2-R9.

### Coverage Targets

- **Touched backend service/domain code:** >= 80% meaningful coverage.
- **Security scenarios:** 100% for permission-sensitive master-data mutations.
- **Data integrity scenarios:** 100% for FK/unique/check/migration critical paths.
- **Frontend state coverage:** loading, empty, error, read-only, forbidden, success for master-data pages.

### Non-Negotiable Requirements

- [ ] All P0 tests pass.
- [ ] No unmitigated high-risk score >= 6 before release.
- [ ] Security/API authz tests pass 100% for mutation endpoints.
- [ ] Audit creation and immutability evidence exists before Story 2.9 done.
- [ ] NFR evidence source identified for every in-scope NFR category.
- [ ] Full NFR PASS/CONCERNS/FAIL status deferred to `nfr-assess` after implementation evidence exists.

---

## Mitigation Plans

### E2-R1: Relational integrity/orphaned references (Score: 9)

**Mitigation Strategy:** Flyway FK/unique/check constraints; Testcontainers migration tests; API negative tests for missing/invalid FK and duplicates.  
**Owner:** Backend  
**Timeline:** Story 2.1-2.7 before done  
**Status:** Planned  
**Verification:** E2-API-001, E2-API-005, E2-API-007, E2-API-008, E2-DB-002.

### E2-R2: Authorization or plant-scope bypass (Score: 6)

**Mitigation Strategy:** Server-side permission and plant-scope checks for every read/mutation; VIEWER mutation 403 tests; out-of-scope negative tests.  
**Owner:** Backend  
**Timeline:** Each Epic 2 story  
**Status:** Planned  
**Verification:** E2-API-002, E2-API-011, E2-API-012, E2-E2E-001, E2-E2E-002.

### E2-R3: Immutable audit missing/incomplete/mutable (Score: 9)

**Mitigation Strategy:** Audit integration tests for create/update/delete; DB immutability constraints/triggers; API assertions for actor/action/entity/timestamp/before/after.  
**Owner:** Backend  
**Timeline:** Story 2.9 and mutation stories  
**Status:** Planned  
**Verification:** E2-API-013, E2-DB-001.

### E2-R4: Invalid sparepart lifetime baseline/threshold (Score: 6)

**Mitigation Strategy:** Unit validation tests and API integration tests for expected count, baseline counter, default threshold, override bounds.  
**Owner:** Backend  
**Timeline:** Story 2.6  
**Status:** Planned  
**Verification:** E2-UNIT-002, E2-API-008.

### E2-R5: App role `MANAGE` confused with job scope `MANAGER` (Score: 6)

**Mitigation Strategy:** Keep enums separate; unit tests for value sets; API validation rejects app-role values where job-scope values expected.  
**Owner:** Backend  
**Timeline:** Story 2.7  
**Status:** Planned  
**Verification:** E2-UNIT-003, E2-API-009.

### E2-R8: Plant-scoped machine group uniqueness wrong (Score: 6)

**Mitigation Strategy:** Unique constraint `(plant_id, name)` and integration/API tests for same-name different-plant allowed, same-plant duplicate rejected.  
**Owner:** Backend  
**Timeline:** Story 2.2  
**Status:** Planned  
**Verification:** E2-API-003.

### E2-R9: Manual machine status inferred from telemetry (Score: 6)

**Mitigation Strategy:** Domain/service tests prove `ACTIVE/INACTIVE` changes only through request/update; master-data module has no telemetry dependency.  
**Owner:** Backend  
**Timeline:** Story 2.3  
**Status:** Planned  
**Verification:** E2-UNIT-001.

---

## Assumptions and Dependencies

### Assumptions

1. Backend remains source of truth for authorization, plant scope, setup completeness, and audit payloads.
2. Frontend consumes backend status and allowed-action fixtures; it does not recalculate domain decisions.
3. Epic 2 can add/extend tables using Flyway while preserving Story 1.7 `plants` and `auth_user_plant_assignments` data.
4. E2E coverage stays intentionally small to avoid slow/flaky CRUD setup through UI.

### Dependencies

1. Stable master-data API contracts per story before component/E2E assertions lock fixtures.
2. Testcontainers PostgreSQL works in local/CI environment before migration gate becomes mandatory.
3. Decision needed for audit retention/delete/anonymization before final compliance assessment.
4. Decision needed for pagination threshold/max page size before performance assessment.

### Risks to Plan

- **Risk:** Audit retention remains undefined at Story 2.9 completion.
  - **Impact:** `nfr-assess` likely records CONCERNS for auditability/compliance-readiness.
  - **Contingency:** Document retention as explicit product/compliance decision and gate only mutation audit integrity in Epic 2.
- **Risk:** Pagination requirements remain undefined while admin tables grow.
  - **Impact:** Performance validation cannot move beyond smoke/exploratory evidence.
  - **Contingency:** Use API-level pagination once requirement exists; defer load thresholds.

---

## Interworking & Regression

| Service/Component | Impact | Regression Scope |
|---|---|---|
| Backend auth/role/plant scope | New master-data endpoints must reuse server-side role and scope model. | Existing auth, role access, plant-scope tests must keep passing. |
| PostgreSQL/Flyway | Epic 2 adds master-data/audit schema and must preserve Story 1.7 data. | Empty-DB migration and existing V1/V2 migration tests. |
| Frontend admin shell/navigation | Master-data UI adds role-aware pages/states. | Existing login, protected routes, role navigation, plant selector behavior. |
| Audit log | Master-data mutations become audit producers. | Audit creation, immutability, actor/action/entity payload correctness. |

---

## Appendix

### Knowledge Base References

- `risk-governance.md` - Risk classification and mitigation thresholds.
- `probability-impact.md` - Probability x impact scoring.
- `test-levels-framework.md` - Unit/API/component/E2E level selection.
- `test-priorities-matrix.md` - P0-P3 priority mapping.
- `nfr-criteria.md` - NFR planning categories and evidence framing.
- `test-quality.md` - Deterministic, isolated, fast test quality rules.
- `playwright-cli.md` - Browser exploration/session hygiene guidance.

### Related Documents

- Project context: `_bmad-output/project-context.md`
- Epic scope: `_bmad-output/planning-artifacts/epics.md`
- Architecture context: `_bmad-output/planning-artifacts/architecture.md`
- Progress artifact: `_bmad-output/test-artifacts/test-design-progress.md`

---

**Generated by:** BMad TEA Agent - Test Architect Module  
**Workflow:** `bmad-testarch-test-design`  
**Version:** 4.0 (BMad v6)
