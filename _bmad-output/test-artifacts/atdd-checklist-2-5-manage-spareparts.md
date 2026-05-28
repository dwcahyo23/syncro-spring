---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04-generate-tests', 'step-04c-aggregate', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-05-28'
storyId: '2.5'
storyKey: '2-5-manage-spareparts'
storyFile: '_bmad-output/implementation-artifacts/2-5-manage-spareparts.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-2-5-manage-spareparts.md'
generatedTestFiles:
  - syncro/apps/web/tests/api/spareparts-atdd.spec.ts
  - syncro/apps/web/tests/e2e/spareparts.atdd-red.spec.ts
inputDocuments:
  - _bmad/tea/config.yaml
  - _bmad-output/project-context.md
  - _bmad-output/implementation-artifacts/2-5-manage-spareparts.md
  - syncro/apps/web/package.json
  - syncro/apps/web/playwright.config.ts
  - syncro/apps/backend/pom.xml
  - syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartControllerTest.java
  - syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java
  - syncro/apps/web/tests/e2e/dashboard.spec.ts
  - syncro/apps/web/tests/e2e/plant-api.spec.ts
  - syncro/apps/web/tests/e2e/spareparts.spec.ts
  - .claude/skills/bmad-testarch-atdd/resources/tea-index.csv
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/data-factories.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/component-tdd.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/test-quality.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/test-healing-patterns.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/selector-resilience.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/timing-debugging.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/overview.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/api-request.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/network-recorder.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/auth-session.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/intercept-network-call.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/recurse.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/log.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/file-utils.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/network-error-monitor.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/fixtures-composition.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/playwright-cli.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/test-levels-framework.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/test-priorities-matrix.md
  - .claude/skills/bmad-testarch-atdd/resources/knowledge/ci-burn-in.md
---

# ATDD Checklist Progress: Story 2.5 Manage Spareparts

## Step 1: Preflight & Context

- Mode: BMad-integrated ATDD Create workflow.
- Story file: `_bmad-output/implementation-artifacts/2-5-manage-spareparts.md`.
- Story status: `review`.
- Stack: fullstack.
- Frontend framework: Playwright present at `syncro/apps/web/playwright.config.ts`; `@playwright/test` present in `syncro/apps/web/package.json`.
- Backend framework: Spring Boot/Maven present at `syncro/apps/backend/pom.xml`; backend tests present under `syncro/apps/backend/src/test/java`.
- Existing Story 2.5 backend tests detected:
  - `syncro/apps/backend/src/test/java/com/syncro/sparepart/api/SparepartControllerTest.java`
  - `syncro/apps/backend/src/test/java/com/syncro/sparepart/application/SparepartServiceIntegrationTest.java`
- Existing web E2E tests detected:
  - `syncro/apps/web/tests/e2e/dashboard.spec.ts`
  - `syncro/apps/web/tests/e2e/plant-api.spec.ts`
  - `syncro/apps/web/tests/e2e/spareparts.spec.ts`
- ATDD caveat: pure red-phase does not apply because Story 2.5 implementation and automation already exist; workflow can still produce acceptance checklist/scaffolding from current story and tests.

## Story Context

- Story: SUPER_ADMIN or MANAGE user can create and maintain spareparts using taxonomy dimensions.
- Core roles: `SUPER_ADMIN`, `MANAGE`, `VIEWER`.
- Taxonomy dimensions: `CATEGORY`, `BRAND`, `KIND`, `TYPE`.
- Key UI acceptance areas: CRUD, taxonomy selectors, read-only VIEWER state, loading/empty/error/taxonomy-missing states, delete conflict message.
- Key backend acceptance areas: authn/authz, validation, duplicate code/name, taxonomy reference errors, malformed JSON, invalid UUID, not found, delete conflict.

## Knowledge Loaded

- Core ATDD/testing fragments loaded:
  - data factories
  - component TDD
  - test quality
  - test healing patterns
- Frontend/browser testing fragments loaded:
  - selector resilience
  - timing debugging
  - Playwright CLI
- Playwright Utils fragments loaded:
  - overview
  - api request
  - network recorder
  - auth session
  - intercept network call
  - recurse
  - log
  - file utils
  - network error monitor
  - fixtures composition
- Backend/test strategy fragments loaded:
  - test levels framework
  - test priorities matrix
  - CI burn-in

## Step 1 Result

Preflight complete. Inputs confirmed from provided story path, project context, framework config, existing tests, and loaded knowledge fragments. Proceed to Step 2 generation-mode selection.

## Step 2: Generation Mode Selection

- Chosen mode: AI generation.
- Reason: acceptance criteria are clear, scenarios are standard CRUD/auth/API/navigation, and Story 2.5 already has implementation plus Playwright E2E coverage for UI behavior.
- Recording decision: skipped. Live browser recording is not needed for ATDD scaffold/checklist because current UI selectors and flows are already represented in `syncro/apps/web/tests/e2e/spareparts.spec.ts`; `playwright-cli` was also previously unavailable in this session.
- Next step: proceed to test strategy.

## Step 3: Test Strategy

### Acceptance Criteria Strategy Map

| AC | Scenario | Level | Priority | Existing / target evidence |
| --- | --- | --- | --- | --- |
| AC1 | SUPER_ADMIN/MANAGE creates sparepart with code, name, category, brand, kind, type and persisted references | Integration + E2E | P0 | Existing service integration and E2E create selector flow |
| AC2 | Create/update rejects missing or invalid taxonomy references | Integration + API | P0 | Existing service/controller validation coverage |
| AC3 | API returns safe validation errors without leaking internals | API | P0 | Existing controller validation/malformed JSON coverage |
| AC4 | Duplicate code/name rejected with stable conflict response | Integration + API | P0 | Existing service/controller duplicate coverage |
| AC5 | Distinct unique spareparts remain allowed | Integration | P1 | Existing service integration coverage |
| AC6 | List supports global search and taxonomy filters | Integration + E2E | P1 | Existing backend list/search and E2E table/filter/search coverage |
| AC7 | SUPER_ADMIN/MANAGE updates sparepart fields and taxonomy references | Integration + E2E | P0 | Existing service integration and E2E edit flow |
| AC8 | SUPER_ADMIN/MANAGE deletes unused sparepart | Integration + E2E | P0 | Existing service integration and E2E delete flow |
| AC9 | Delete blocked when installed spareparts depend on target and conflict message is visible | Integration + E2E | P0 | Existing backend FK/conflict and E2E conflict message |
| AC10 | VIEWER can list/view spareparts | API + E2E | P1 | Existing service/controller read coverage and E2E read-only state |
| AC11 | VIEWER cannot create/update/delete spareparts | API | P0 | Existing authorization tests |
| AC12 | Unauthenticated user cannot access protected endpoints/pages | API + E2E smoke | P0 | Existing backend auth tests; dashboard smoke covers protected redirect pattern |
| AC13 | Spareparts are global, not plant-scoped | Integration | P1 | Existing backend global list behavior |
| AC14 | Dense desktop UI supports create/edit/delete with taxonomy selectors | E2E | P0 | Existing `spareparts.spec.ts` CRUD flow |
| AC15 | VIEWER UI is read-only with mutation controls hidden/disabled | E2E | P1 | Existing `spareparts.spec.ts` viewer test |
| AC16 | UI renders loading, empty, failed, read-only, forbidden/taxonomy-missing states | E2E + Component candidate | P1 | Existing E2E empty/API error/taxonomy-missing; component tests optional if UI grows |
| AC17 | Orval client generated and web uses generated hooks/types | Build/check + static verification | P1 | Existing `generate:api`, `check`, build evidence in story |

### Level Selection

- Unit: not primary for current story because core sparepart rules are persistence/service-bound and already covered by integration tests.
- Integration: primary backend level for persistence, uniqueness, taxonomy reference integrity, global query behavior, and delete conflict.
- API/Controller: primary boundary level for authn/authz, validation response shape, malformed JSON, invalid UUID, not found, forbidden, and safe errors.
- E2E: primary UI level for user-visible CRUD, selector behavior, read-only state, error/empty/taxonomy-missing states.
- Component: optional future level only if sparepart UI splits into isolated components with complex local state; avoid adding now to prevent duplicate coverage.

### Priority Decision

- P0: data integrity, security, mutation authorization, duplicate prevention, delete conflict, user-visible CRUD.
- P1: read-only UX, list/filter/search, global behavior, generated client wiring, non-critical UI states.
- P2/P3: no new ATDD targets needed for this story because lower priority behavior would duplicate existing tests.

### Red Phase Note

Pure red phase is not possible now because Story 2.5 implementation and tests already exist. If this ATDD checklist had run before implementation, P0 scenarios above should fail first, then drive backend service/API and web E2E implementation. Current output acts as acceptance strategy/checklist against existing implementation rather than new red tests.

### Step 3 Result

Acceptance criteria mapped to level-appropriate, priority-based scenarios. Duplicate coverage avoided by retaining existing backend/API tests and using E2E only for user-visible behavior.

## Step 4: Red-Phase Test Scaffold Generation

- Execution mode requested: `auto`.
- Capability probe result: subagents available; agent-team not used.
- Resolved execution mode: `subagent`.
- API worker output: `C:/Windows/Temp/tea-atdd-api-tests-2026-05-28T-atdd-2-5.json`.
- E2E worker output: `C:/Windows/Temp/tea-atdd-e2e-tests-2026-05-28T-atdd-2-5.json`.
- TDD phase: RED.
- Red-phase compliance: PASS. Generated scaffold files contain `test.skip()` and no `expect(true).toBe(true)` placeholder assertions.

### Generated Red-Phase Files

- `syncro/apps/web/tests/api/spareparts-atdd.spec.ts` — 15 skipped API ATDD scaffold tests.
- `syncro/apps/web/tests/e2e/spareparts.atdd-red.spec.ts` — 7 skipped E2E ATDD scaffold tests.
- `_bmad-output/test-artifacts/atdd-generation-summary.json` — aggregate generation summary.

### Fixture Needs

- API scaffolds need stable taxonomy rows for `CATEGORY`, `BRAND`, `KIND`, `TYPE`.
- API scaffolds need role tokens via `SYNCRO_ATDD_SUPER_ADMIN_TOKEN`, `SYNCRO_ATDD_MANAGE_TOKEN`, `SYNCRO_ATDD_VIEWER_TOKEN`, or project auth fixture replacement.
- API scaffolds need seeded spareparts for update/delete/not-found/duplicate scenarios when tests are activated.
- Delete-conflict scaffold needs installed-sparepart dependency fixture once dependent story/table exists.
- E2E scaffolds reuse existing `../support/fixtures` and spec-local auth/network mocking patterns.

### Summary Statistics

| Metric | Count |
| --- | ---: |
| Total red-phase scaffold tests | 22 |
| API scaffold tests | 15 |
| E2E scaffold tests | 7 |
| Active tests generated | 0 |
| Skipped tests generated | 22 |
| Fixture files created | 0 |

### Acceptance Criteria Covered

- API scaffolds represent planned coverage for AC1-AC13; they are not active acceptance evidence until unskipped and wired to stable fixtures/tokens.
- E2E scaffolds represent planned coverage for AC1, AC6, AC7, AC8, AC9, AC10, AC14, AC15, AC16; active Story 2.5 E2E evidence remains in `syncro/apps/web/tests/e2e/spareparts.spec.ts`.
- AC17 remains build/static evidence from existing story implementation (`generate:api`, `check`, build evidence), not duplicate ATDD scaffold.

### Step 4C Result

ATDD red-phase scaffold generation aggregated successfully. Files written to disk, summary saved, checklist metadata updated. Ready for Step 5 validation.

## Step 5: Validate & Complete

### Validation Results

- Generated files present:
  - `syncro/apps/web/tests/api/spareparts-atdd.spec.ts`
  - `syncro/apps/web/tests/e2e/spareparts.atdd-red.spec.ts`
- Red-phase scaffold guard: PASS.
  - API scaffold contains 15 `test.skip()` tests.
  - E2E scaffold contains 7 `test.skip()` tests.
  - Total skipped ATDD scaffolds: 22.
  - Active tests generated: 0.
- Placeholder assertion guard: PASS.
  - No `expect(true).toBe(true)` assertions found.
  - Text match for `placeholder` is only UI placeholder label usage (`Search code or name`) in E2E scaffold.
- API assertion quality: PASS.
  - API scaffold was strengthened to assert expected status codes and key response shape for create, read, validation, conflict, authn/authz, delete, and not-found flows.
- Formatting/check: PASS.
  - Command: `npm --prefix "syncro/apps/web" run format -- "tests/api/spareparts-atdd.spec.ts" "tests/e2e/spareparts.atdd-red.spec.ts"`
  - Command: `npm --prefix "syncro/apps/web" run check`
  - Result: `Checked 98 files in 1553ms. No fixes applied.`
- Artifact location: PASS.
  - Raw API worker artifact copied to `_bmad-output/test-artifacts/atdd-api-tests-2026-05-28T-atdd-2-5.json`.
  - Raw E2E worker artifact copied to `_bmad-output/test-artifacts/atdd-e2e-tests-2026-05-28T-atdd-2-5.json`.
  - Aggregate summary updated at `_bmad-output/test-artifacts/atdd-generation-summary.json`.

### Completion Notes

- Story 2.5 already has implementation and active automation, so generated ATDD tests remain skipped by design.
- AC1-AC16 planned coverage is represented by skipped ATDD scaffolds; active acceptance evidence comes from existing backend/API tests, active Story 2.5 E2E tests, and build/static checks recorded in the story.
- Future activation requires stable backend fixtures/tokens for API scaffolds and approval to convert selected `test.skip()` cases into active regression tests.

### Step 5 Result

ATDD Create workflow validation complete for Story 2.5. Generated red-phase scaffold, checklist, and raw worker artifacts are saved under project test artifacts.
