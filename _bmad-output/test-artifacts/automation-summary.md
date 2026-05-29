---
stepsCompleted:
  - step-01-preflight-and-context
  - step-02-identify-targets
  - step-03-generate-tests
  - step-03c-aggregate
  - step-04-validate-and-summarize
lastStep: step-04-validate-and-summarize
lastSaved: '2026-05-29'
storyId: '2.6'
storyKey: 2-6-install-spareparts-on-machines-with-lifetime-baseline
storyFile: _bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md
mode: create
inputDocuments:
  - _bmad/tea/config.yaml
  - _bmad-output/project-context.md
  - _bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md
  - _bmad-output/test-artifacts/atdd-checklist-2-6-install-spareparts-on-machines-with-lifetime-baseline.md
  - syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java
  - syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java
  - syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts
  - syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts
  - syncro/apps/web/src/features/master-data/installations/installation-management.tsx
---

# Test Automation Summary: Story 2.6 Machine Sparepart Installations

## Step 1: Preflight & Context

- User: Yusuf.
- Language: Indonesia.
- Detected stack: fullstack.
- Framework readiness: PASS.
  - Backend: Maven/Spring tests under `syncro/apps/backend/src/test`.
  - Frontend: Playwright config at `syncro/apps/web/playwright.config.ts` and tests under `syncro/apps/web/tests`.
- BMad-integrated mode selected because the story, ATDD checklist, backend tests, and implementation files exist.
- Core context loaded from `_bmad-output/project-context.md` and Story 2.6 artifacts.

## Step 2: Automation Targets and Coverage Plan

### Existing Active Backend Evidence

| Priority | Target | Existing active evidence |
| --- | --- | --- |
| P0 | Authenticated list/create/update/delete contracts, safe validation, malformed JSON, not found, forbidden, conflict, invalid UUID | `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/MachineSparepartInstallationControllerTest.java` |
| P0 | Persistence, default threshold, threshold override, nullable telemetry evidence, counter basis, update link stability, plant scope, VIEWER read/mutation denial, delete behavior | `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/MachineSparepartInstallationServiceIntegrationTest.java` |
| P0 | Real PostgreSQL FK conflicts for machine/sparepart delete and DB constraints for invalid numeric persisted values | `MachineSparepartInstallationServiceIntegrationTest.java` |

### Existing Red-Phase / Waived Browser Evidence

| Priority | Target | Status |
| --- | --- | --- |
| P0 | UI create/edit/delete with non-native selectors | Covered by skipped ATDD scaffold in `syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts`; not activated in this workflow. |
| P0 | UI list evidence: plant, group, machine, sparepart, expected, baseline, current nullable, consumed nullable, threshold, counter basis | Covered by skipped ATDD scaffold; implementation inspection shows rendered columns in `installation-management.tsx`. |
| P1 | VIEWER read-only, forbidden, loading, empty, error, delete conflict state | Covered by skipped ATDD scaffold plus implementation inspection. |
| P1 | Live API Playwright ATDD scenarios | Covered as skipped scaffold in `syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts`; not activated because stable role tokens/seed IDs are still fixture prerequisites. |

## Step 3: Test Generation Decision

No new test files were generated.

Reason:
- Backend P0 API and integration coverage is already active and directly exercises the implementation boundary with MockMvc and PostgreSQL Testcontainers.
- Existing Playwright API/E2E files are deliberate ATDD red-phase scaffolds and depend on stable role tokens, canonical seed data, and browser auth/scope fixtures.
- Activating those skipped Playwright scenarios now would likely produce brittle or false-failing tests because fixture prerequisites are not established in this story evidence.
- Adding duplicate tests would violate selective-testing guidance and duplicate backend coverage without improving confidence.

## Coverage Mapping by Acceptance Criteria

| AC | Evidence |
| --- | --- |
| AC1 | Backend service create test and controller create contract. |
| AC2 | Backend service default threshold test and controller omitted/null threshold tests. |
| AC3 | Backend service threshold override/update test. |
| AC4 | Controller validation/malformed JSON tests and DB constraint tests. |
| AC5 | Controller missing machine/sparepart, invalid UUID, missing installation tests. |
| AC6 | Controller list response shape and service list scope/order evidence. |
| AC7 | Controller/service nullable current/consumed evidence tests; UI renders nullable fields as unavailable. |
| AC8 | Controller/service `COUNTER_BASED` response assertion and UI basis badge. |
| AC9 | Backend update preserves installation identity and machine/sparepart links. |
| AC10 | Backend delete success and safe conflict mapping. |
| AC11 | Service VIEWER read access evidence. |
| AC12 | Controller/service VIEWER mutation denial evidence. |
| AC13 | Service plant-scope denial for out-of-scope machine and filters. |
| AC14 | UI implementation uses shadcn/Radix `Select`; E2E scaffold remains skipped until browser fixtures are stable. |
| AC15 | UI implementation hides mutation actions for non-mutating roles; E2E scaffold remains skipped. |
| AC16 | UI implementation has loading, empty, API error retry, read-only, and forbidden states; E2E scaffold remains skipped. |
| AC17 | Web implementation imports generated Orval hooks/types; generation/check evidence must remain in Dev Agent Record. |
| AC18 | Service integration tests prove real FK delete conflicts through PostgreSQL. |

## Validation Checklist

- Framework readiness: PASS.
- Coverage mapping: PASS with explicit UI/browser waiver.
- Test quality and structure: PASS for active backend tests; Playwright scaffolds remain skipped red-phase by design.
- Fixtures/factories/helpers: PARTIAL. Backend factories are local test helpers. Playwright role-token and canonical seed fixtures remain a future activation prerequisite.
- CLI sessions cleaned up: PASS. No browser CLI/MCP session was opened by this automation run.
- Temp artifacts in test artifacts: PASS. This summary is saved under `_bmad-output/test-artifacts/`.

## Key Assumptions and Risks

- Assumption: Backend tests listed above are run and pass as part of Dev Story evidence before trace/closeout.
- Risk: UI behavior is implemented and scaffolded but not active automated browser evidence yet.
- Risk: The story file Dev Agent Record is incomplete and must be updated with commands/results before final closeout.

## Recommended Next Workflow

Proceed to code review if not already finalized, or TEA traceability if review patches are confirmed resolved.

Recommended next command through story flow: Code Review checkpoint, then TEA Traceability Gate.
