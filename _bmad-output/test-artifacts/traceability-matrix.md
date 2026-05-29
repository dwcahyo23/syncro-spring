---
stepsCompleted: ['step-01-load-context', 'step-02-discover-tests', 'step-03-map-criteria', 'step-04-analyze-gaps', 'step-05-gate-decision']
lastStep: 'step-05-gate-decision'
lastSaved: '2026-05-29'
workflowType: 'testarch-trace'
storyId: '2.6'
storyKey: '2-6-install-spareparts-on-machines-with-lifetime-baseline'
coverageBasis: 'acceptance_criteria'
oracleConfidence: 'high'
oracleResolutionMode: 'formal_requirements'
oracleSources:
  - _bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md
  - _bmad-output/test-artifacts/automation-summary.md
externalPointerStatus: 'not_used'
gateDecision: 'CONCERNS'
---

# Traceability Report: Story 2.6 Install Spareparts on Machines with Lifetime Baseline

**Gate Decision:** CONCERNS  
**Date:** 2026-05-29  
**Coverage Basis:** Story acceptance criteria (AC1-AC18)  
**Oracle Confidence:** High  
**Collection Status:** COLLECTED

## Rationale

Backend API, service, authorization, validation, persistence, and FK-conflict coverage map to all P0/P1 acceptance criteria. Gate remains CONCERNS because the service integration suite was blocked by the local Docker/Testcontainers environment during verification, browser/manual UI evidence is not yet captured for AC14-AC16, and the story Dev Agent Record is still incomplete.

## Coverage Summary

- Total Requirements: 18
- Fully Covered: 15 (83%)
- Partially Covered: 3
- Uncovered: 0
- P0 Coverage: 13/13 mapped to active backend evidence, with integration execution blocked locally
- P1 Coverage: 2/2 mapped to generated-client and FK evidence, with integration execution blocked locally for AC18
- P2 Coverage: 0/3 full; all 3 are partial through source inspection and skipped Playwright scaffolds
- P3 Coverage: N/A

## Traceability Matrix

| AC | Priority | Coverage | Evidence |
|---|---|---|---|
| AC1 create installation persists machine, sparepart, lifetime fields, timestamps | P0 | FULL | `2.6-API-003`; `2.6-SVC-001`; entity/migration evidence in `V8__create_machine_sparepart_installations.sql` |
| AC2 omitted/null/blank threshold defaults to 90% | P0 | FULL | `2.6-API-004`; `2.6-API-004B`; `2.6-SVC-001`; DTO blank-to-null handling |
| AC3 threshold override stores allowed value | P0 | FULL | `2.6-API-003`; `2.6-SVC-002`; service normalization range check |
| AC4 invalid/missing/malformed numeric fields reject safely | P0 | FULL | `2.6-API-005`; `2.6-API-006`; `2.6-API-012`; `2.6-SVC-003`; `2.6-SVC-009`; UI parse errors preserve blank numeric fields |
| AC5 unknown machine/sparepart or invalid UUID returns safe error | P0 | FULL | `2.6-API-007`; `2.6-API-008`; `2.6-API-012`; `2.6-API-014`; exception handler safe codes |
| AC6 list shows identity/context/lifetime evidence and predictable ordering | P0 | FULL | `2.6-API-002`; `2.6-SVC-011`; UI table renders plant, group, machine, sparepart, expected, baseline, threshold, nullable evidence |
| AC7 missing telemetry remains nullable and frontend does not fabricate values | P0 | FULL | `2.6-API-002`; `2.6-SVC-001`; UI unavailable rendering for current/consumed evidence |
| AC8 calculation basis is counter-based, not date-based | P0 | FULL | `2.6-API-002`; `2.6-SVC-001`; service returns `COUNTER_BASED` and null telemetry calculations until current count exists |
| AC9 update safely changes lifetime fields and preserves machine/sparepart links | P0 | FULL | `2.6-API-013`; `2.6-SVC-002`; update command excludes machine/sparepart relink fields |
| AC10 delete removes installation and maps integrity conflicts to 409 | P0 | FULL | `2.6-API-011` delete success; `2.6-API-013` conflict mapping; service `delete` catches `DataIntegrityViolationException` |
| AC11 VIEWER can list/get within plant scope | P0 | FULL | `2.6-API-002`; `2.6-SVC-004`; scoped list/get service paths |
| AC12 VIEWER create/update/delete denied safely | P0 | FULL | `2.6-API-009`; `2.6-API-010`; `2.6-API-011`; `2.6-SVC-004`; mutation role guard |
| AC13 MANAGE/VIEWER plant scope enforced; SUPER_ADMIN all access | P0 | FULL | `2.6-SVC-004`; `2.6-SVC-005`; scoped/unscoped repository query paths; `validateFilterScope` machine filter enforcement |
| AC14 SUPER_ADMIN/MANAGE UI create/edit/delete with non-native selectors | P2 | PARTIAL | Source uses feature component and shadcn/Radix selectors; skipped E2E scaffold exists; no live browser/manual evidence captured |
| AC15 VIEWER/forbidden UI read-only state and mutation controls hidden/disabled | P2 | PARTIAL | Source renders read-only/forbidden state and hides mutation actions; skipped E2E scaffold exists; no role browser evidence captured |
| AC16 UI loading/empty/error/read-only/forbidden durable states | P2 | PARTIAL | Source renders skeleton, empty setup prompt, retryable errors, read-only badge/state, forbidden copy; skipped E2E scaffold exists; no active UI-state test/browser evidence captured |
| AC17 generated OpenAPI client/hooks/types and TanStack invalidation used | P1 | FULL | Generated model/client files updated; UI imports generated Orval hooks/types; `npm --prefix syncro/apps/web run check` passed |
| AC18 real FK delete conflicts for machine/sparepart dependents | P1 | FULL | `2.6-SVC-007`; `2.6-SVC-008`; migration FK constraints to `machines` and `spareparts`; execution blocked locally by Testcontainers environment |

## Gap Analysis

### Critical gaps

None. No acceptance criterion is uncovered.

### High gaps

- Verification gap: `MachineSparepartInstallationServiceIntegrationTest` contains P0/P1 data-integrity evidence but the combined Maven run was blocked by the local Docker/Testcontainers environment, so FK, uniqueness, DB constraint, and scope integration evidence is mapped but not freshly executable in this session.
- Evidence hygiene gap: Story Dev Agent Record still contains placeholders and does not record final commands/results.

### Medium gaps

- AC14: no active browser/manual evidence for create/edit/delete flow with non-native selectors.
- AC15: no active browser/manual evidence for VIEWER/read-only/forbidden UI behavior.
- AC16: no active browser/manual evidence for loading, empty, API error retry, read-only, and forbidden UI states.

## Heuristics

- Endpoint gaps: 0
- Auth negative-path gaps: 0
- Error-path gaps: 0
- Data-integrity gaps: 0 mapped, 1 local execution blocker
- UI journey gaps: 1 (`AC14`)
- UI state gaps: 2 (`AC15`, `AC16`)
- Documentation/evidence gaps: 1 Dev Agent Record incomplete

## Verification Evidence

| Check | Result | Notes |
|---|---|---|
| `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineSparepartInstallationControllerTest"` | PASS | Controller/API contract evidence verified after review patches |
| `mvn -f syncro/apps/backend/pom.xml test -Dtest="MachineSparepartInstallationControllerTest,MachineSparepartInstallationServiceIntegrationTest"` | BLOCKED | Testcontainers/Docker environment failed before service integration verification completed |
| `npm --prefix syncro/apps/web run check` | PASS | Passed after Biome formatting fix |
| Playwright API/E2E ATDD scaffolds | WAIVED/SKIPPED | Stable role tokens, canonical seed data, and browser auth/scope fixtures not established |
| Browser/manual UI route verification | MISSING | Required before closeout if story is to be marked done |

## Gate Criteria

| Criterion | Required | Actual | Status |
|---|---:|---:|---|
| P0 criteria mapped to evidence | 100% | 100% | MET |
| P1 criteria mapped to evidence | >=90% | 100% | MET |
| Overall full coverage | >=80% | 83% | MET |
| Fresh execution of critical integration evidence | Required or explicit blocker | Blocked by environment | CONCERN |
| UI/browser evidence for user-visible flows | Required for closeout | Missing for AC14-AC16 | CONCERN |
| Story Dev Agent Record complete | Required for closeout | Incomplete | CONCERN |

## Recommendations

1. Fix or provision the Docker/Testcontainers environment, then rerun `MachineSparepartInstallationServiceIntegrationTest` to refresh P0/P1 persistence, scope, uniqueness, and FK evidence.
2. Capture manual/browser evidence for Installations create/edit/delete, VIEWER/read-only/forbidden, and loading/empty/error UI states.
3. Complete the Story 2.6 Dev Agent Record with exact commands, outcomes, limitations, and changed file list.
4. Keep Playwright ATDD scaffolds skipped until stable role-token, seed-data, and browser-auth fixtures exist; do not claim them as active evidence.

## Decision

CONCERNS. Coverage is structurally sufficient and no acceptance criterion is uncovered, but final closeout should wait for integration-test environment verification, browser/manual UI evidence, and Dev Agent Record completion.
