---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04-generate-tests', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-05-28'
storyId: '2.6'
storyKey: '2-6-install-spareparts-on-machines-with-lifetime-baseline'
storyFile: '_bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-2-6-install-spareparts-on-machines-with-lifetime-baseline.md'
generatedTestFiles:
  - syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts
  - syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts
inputDocuments:
  - _bmad/tea/config.yaml
  - _bmad-output/project-context.md
  - _bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md
  - _bmad-output/implementation-artifacts/2-5-manage-spareparts.md
  - syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java
  - syncro/apps/backend/src/main/java/com/syncro/sparepart/application/SparepartService.java
  - syncro/apps/web/src/features/master-data/spareparts/sparepart-management.tsx
  - syncro/apps/web/playwright.config.ts
  - syncro/apps/web/tests/e2e/spareparts.spec.ts
  - syncro/apps/web/tests/api/spareparts-atdd.spec.ts
---

# ATDD Checklist Progress: Story 2.6 Install Spareparts on Machines with Lifetime Baseline

## Step 1: Preflight & Context

- Mode: BMad-integrated ATDD Create workflow.
- Story file: `_bmad-output/implementation-artifacts/2-6-install-spareparts-on-machines-with-lifetime-baseline.md`.
- Story status: `ready-for-dev`.
- Stack: fullstack.
- Frontend framework: Playwright present at `syncro/apps/web/playwright.config.ts`; existing web tests under `syncro/apps/web/tests`.
- Backend framework: Spring Boot/Maven present; backend tests present under `syncro/apps/backend/src/test/java`.
- Existing Story 2.5 patterns detected for sparepart API, service integration, generated web client, and E2E management UI.
- ATDD caveat: Story 2.6 implementation does not exist yet; generated tests intentionally remain skipped red-phase scaffolds until fixtures/tokens and endpoints exist.

## Story Context

- Story: SUPER_ADMIN or MANAGE installs spareparts on machines with expected production-count lifetime, baseline counter, and threshold percentage.
- Core roles: `SUPER_ADMIN`, `MANAGE`, `VIEWER`.
- Scope boundary: install state is plant-scoped through machine; spareparts remain global master data.
- Current telemetry boundary: current count and consumed evidence are nullable until Epic 3 latest telemetry exists; frontend must not fabricate values.
- Counter semantics: consumption basis is counter-based from current counter minus baseline counter, never date/installedAt based.
- Key backend acceptance areas: Flyway migration, real FKs to machines/spareparts, numeric validation, safe errors, authn/authz, plant scope, delete conflict proof.
- Key UI acceptance areas: generated Orval hooks/types, shadcn/Radix selectors, create/edit/delete, read-only/forbidden/loading/empty/error states, evidence table.

## Knowledge Loaded

- Core ATDD/testing fragments loaded:
  - data factories
  - test quality
  - test levels framework
  - test priorities matrix
- Frontend/browser testing fragments loaded:
  - selector resilience
- Project-specific guardrails loaded:
  - no native selects for dropdown/pickers
  - no mocked DB for persistence/FK behavior
  - backend authorization is security boundary

## Step 2: Generation Mode Selection

- Chosen mode: AI generation.
- Reason: acceptance criteria are clear CRUD/API/UI/data-integrity scenarios and no implemented page exists to record with browser automation.
- Recording decision: skipped. Browser recording is not useful before implementation; scaffolds express expected future behavior.

## Step 3: Test Strategy

### Acceptance Criteria Strategy Map

| AC | Scenario | Level | Priority | Target evidence |
| --- | --- | --- | --- | --- |
| AC1 | SUPER_ADMIN/MANAGE creates installation and persists machine, sparepart, expected count, baseline, threshold, timestamps | Integration + API + E2E | P0 | Service integration, API scaffold 001/002, E2E scaffold 001 |
| AC2 | Threshold defaults to 90 when omitted/null | Integration + API | P0 | Service integration, API scaffold 003 |
| AC3 | Threshold override within range persists | Integration + API + E2E | P0 | API scaffold 002, E2E scaffold 001 |
| AC4 | Invalid numeric ranges, malformed JSON, missing fields rejected safely | API | P0 | API scaffold 004/005 |
| AC5 | Unknown IDs and invalid UUIDs return safe not-found/validation errors | API | P0 | API scaffold 005 |
| AC6 | List exposes machine, plant/group, sparepart taxonomy, baseline/current/expected/threshold evidence with ordering | Integration + API + E2E | P0 | API scaffold 006/012, E2E scaffold 002 |
| AC7 | Current count and consumed evidence are nullable without telemetry; UI does not fabricate values | API + E2E | P0 | API scaffold 006, E2E scaffold 002 |
| AC8 | Counter-based calculation basis is explicit and not installedAt/date-based | Integration + API + E2E | P0 | API scaffold 001/006, E2E scaffold 002 |
| AC9 | Update changes lifetime fields safely without unintended relink | Integration + API + E2E | P0 | API scaffold 007, E2E scaffold 003 |
| AC10 | Delete installation works without dependents; future FK conflicts map to safe 409 | Integration + API + E2E | P0 | API scaffold 008/014, E2E scaffold 004/007 |
| AC11 | VIEWER can list/detail within plant scope | API + E2E | P1 | API scaffold 002/006, E2E scaffold 005 |
| AC12 | VIEWER mutation denied server-side | API | P0 | API scaffold 009 |
| AC13 | MANAGE/VIEWER plant scope enforced through machine; SUPER_ADMIN all access | Integration + API | P0 | API scaffold 011 |
| AC14 | SUPER_ADMIN/MANAGE web UI creates/edits/deletes with non-native selectors | E2E | P0 | E2E scaffold 001/003/004 |
| AC15 | VIEWER/forbidden UI read-only or forbidden without mutation controls | E2E | P1 | E2E scaffold 005/006 |
| AC16 | UI renders loading, empty setup prompt, API error retry, read-only, forbidden | E2E | P1 | E2E scaffold 006 |
| AC17 | Web uses generated Orval hooks/types and TanStack invalidation | Build/static + E2E | P1 | Dev evidence after API generation; no direct scaffold duplicate |
| AC18 | Machine/sparepart delete conflicts proven by real FK rows | Integration + API | P0 | API scaffold 013 plus backend integration tests |

### Level Selection

- Unit: optional only for pure value normalization if implementation adds one; not primary.
- Integration: primary backend level for Flyway migration, constraints, real FK conflict, repository queries, plant scope, and delete semantics.
- API/Controller: primary boundary level for authn/authz, validation response shape, malformed JSON, invalid UUID, not-found, forbidden, and safe 409.
- E2E: primary UI level for user-visible create/edit/delete, selector behavior, read-only/forbidden/error/empty states, and nullable telemetry evidence.
- Component: not selected now; would duplicate E2E unless installation UI grows complex isolated state.

### Priority Decision

- P0: data integrity, FK behavior, counter semantics, mutation authorization, plant scope, numeric validation, user-visible CRUD.
- P1: UI durable states, generated-client wiring evidence, list filters/order, VIEWER read-only UX.
- P2/P3: no new targets needed now.

## Step 4: Red-Phase Test Scaffold Generation

- Execution mode: AI generation in main context.
- TDD phase: RED.
- Red-phase compliance: generated tests use `test.skip()` and contain no vacuous `expect(true).toBe(true)` assertions.

### Generated Red-Phase Files

- `syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts` — 14 skipped API ATDD scaffold tests.
- `syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts` — 7 skipped E2E ATDD scaffold tests.

### Fixture Needs

- API scaffolds need stable role tokens through `SYNCRO_ATDD_SUPER_ADMIN_TOKEN`, `SYNCRO_ATDD_MANAGE_TOKEN`, and `SYNCRO_ATDD_VIEWER_TOKEN`, or project auth fixture replacement.
- API scaffolds need seeded plants, machine groups, machines, spareparts, and installation rows with deterministic UUIDs.
- Plant-scope scaffolds need users assigned to distinct plants.
- FK conflict scaffolds need real `machine_sparepart_installations` rows before deleting machines/spareparts.
- E2E scaffolds currently mock network responses from expected generated API shapes and should switch to generated model types after Orval creates Story 2.6 types.

### Summary Statistics

| Metric | Count |
| --- | ---: |
| Total red-phase scaffold tests | 21 |
| API scaffold tests | 14 |
| E2E scaffold tests | 7 |
| Active tests generated | 0 |
| Skipped tests generated | 21 |
| Fixture files created | 0 |

### Acceptance Criteria Covered

- API scaffolds represent planned red-phase coverage for AC1-AC13 and AC18.
- E2E scaffolds represent planned red-phase coverage for AC1, AC3, AC6, AC7, AC8, AC9, AC10, AC14, AC15, and AC16.
- AC17 remains build/static implementation evidence: OpenAPI generation, Orval generation, generated hooks/types usage, and TanStack Query invalidation checks.

## Step 5: Validate & Complete

### Validation Results

- Generated files present:
  - `syncro/apps/web/tests/api/machine-sparepart-installations-atdd.spec.ts`
  - `syncro/apps/web/tests/e2e/machine-sparepart-installations.atdd-red.spec.ts`
- Red-phase scaffold guard: PASS by design.
  - API scaffold contains 14 `test.skip()` tests.
  - E2E scaffold contains 7 `test.skip()` tests.
  - Active tests generated: 0.
- Placeholder assertion guard: PASS by design.
  - Scaffolds assert HTTP status, response shape, request payloads, UI states, and visible evidence.
- Formatting/check: PASS.
  - Command: `npm --prefix "syncro/apps/web" run format -- "tests/api/machine-sparepart-installations-atdd.spec.ts" "tests/e2e/machine-sparepart-installations.atdd-red.spec.ts"`
  - Result: `Formatted 2 files in 62ms. Fixed 2 files.`
  - Command: `npm --prefix "syncro/apps/web" run check`
  - Result: `Checked 100 files in 2s. No fixes applied.`

### Completion Notes

- Story 2.6 ATDD red-phase scaffolds are ready but skipped because implementation endpoints, Orval types, and stable fixtures do not exist yet.
- Backend implementation must add real integration/controller tests, not rely only on Playwright API scaffolds.
- Generated E2E scaffold intentionally uses local expected shapes until Story 2.6 Orval models exist; after generation, convert fixture types to generated model types.

### Step 5 Result

ATDD Create workflow complete for Story 2.6. Red-phase scaffold and checklist are saved under project test artifacts and web tests.
