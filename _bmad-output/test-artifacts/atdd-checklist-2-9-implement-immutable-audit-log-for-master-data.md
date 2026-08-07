---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04c-aggregate', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-08-07T23:50:00+07:00'
workflowType: 'testarch-atdd'
storyId: '2.9'
storyKey: '2-9-implement-immutable-audit-log-for-master-data'
storyFile: '_bmad-output/implementation-artifacts/spec-2-9-implement-immutable-audit-log-for-master-data.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-2-9-implement-immutable-audit-log-for-master-data.md'
generatedTestFiles:
  - 'syncro/apps/backend/src/test/java/com/syncro/audit/api/AuditLogAtddGapApiScaffoldTest.java'
  - 'syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java'
  - 'syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx'
  - 'syncro/apps/web/tests/e2e/audit-log.atdd-red.spec.ts'
inputDocuments:
  - '_bmad-output/implementation-artifacts/spec-2-9-implement-immutable-audit-log-for-master-data.md'
  - '_bmad-output/test-artifacts/test-design-story-2-9-immutable-audit-log.md'
  - '_bmad-output/test-artifacts/test-design-epic-2.md'
  - '_bmad-output/project-context.md'
---

# ATDD Checklist - Epic 2, Story 9: Implement Immutable Audit Log for Master Data

**Date:** 2026-08-07
**Author:** Yusuf (TEA / bmad-testarch-atdd)
**Primary Test Level:** Integration + API (backend), Component + E2E (frontend)

---

## Story Summary

Add an immutable `audit_log` table and `com.syncro.audit` module so every master data mutation (plant, machine group, machine, sparepart taxonomy, sparepart, installation, responsibility) records exactly one entry (actor, action, entity, plant, before/after JSON, timestamp). Entries are insert-only at app and DB level; reads are plant-scoped via `GET /api/v1/audit-log` with filters + pagination, rendered by a responsive AuditLog page.

**As a** SUPER_ADMIN / MANAGE operator
**I want** a tamper-proof, filterable change history for master data
**So that** operational accountability and compliance readiness exist in Phase 1.

> **Run context:** Story 2-9 was already implemented and verified (status `awaiting-operator`). This ATDD run is a **gap-closing red-phase scaffold** produced from the story-level test design (`test-design-story-2-9-immutable-audit-log.md`): it adds RED scaffolds for the uncovered risks (R-2.9-1 trigger column gap, R-2.9-6 frontend page-reset bug, R-2.9-8/11/12/13, pagination) plus an implementation checklist to turn them green.

---

## Acceptance Criteria

1. Given any master data mutation (plant, machine group, machine, sparepart taxonomy, sparepart, installation, responsibility) is persisted, then exactly one immutable audit entry records actor, action, entity type, entity ID, entity label, plantId, previous value, new value, and timestamp.
2. Given the audit log API, when filtered by entity type, actor, plant, or date range, then only matching entries are returned, paginated newest-first.
3. Given an attempt to edit or delete an audit entry (app or direct SQL), then it is rejected — no such API exists and the DB trigger raises.
4. Given a SUPER_ADMIN, MANAGE (assigned), VIEWER (assigned/empty) user lists the log, then results are plant-scoped per `effectiveScope` and out-of-scope plant filters return 403.
5. Given the Audit Log page renders, then desktop shows a dense table and mobile shows stacked cards grouped by date, both with expandable before/after detail, plus loading, error, empty, and filtered-empty-with-reset states.

---

## Story Integration Metadata

- **Story ID:** `2.9`
- **Story Key:** `2-9-implement-immutable-audit-log-for-master-data`
- **Story File:** `_bmad-output/implementation-artifacts/spec-2-9-implement-immutable-audit-log-for-master-data.md`
- **Checklist Path:** `_bmad-output/test-artifacts/atdd-checklist-2-9-implement-immutable-audit-log-for-master-data.md`
- **Generated Test Files:**
  - `syncro/apps/backend/src/test/java/com/syncro/audit/api/AuditLogAtddGapApiScaffoldTest.java`
  - `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`
  - `syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx`
  - `syncro/apps/web/tests/e2e/audit-log.atdd-red.spec.ts`

---

## Red-Phase Test Scaffolds Created

### Backend API Tests (MockMvc slice, 4 tests — all `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/api/AuditLogAtddGapApiScaffoldTest.java`

- ✅ **Test:** `2.9-API-007` invalid sort property returns 400
  - **Status:** RED - activation lock for R-2.9-8; assert `400 INVALID_QUERY_VALUE` for `sort=plantId,asc` (unreachable from the sort allowlist).
  - **Verifies:** AC2 (invalid filter values produce 400, not silent defaulting).
- ✅ **Test:** `2.9-API-008` malformed `from` date returns 400
  - **Status:** RED - activation lock for R-2.9-8; assert `400 INVALID_QUERY_VALUE` for `from=not-a-date`.
  - **Verifies:** AC2 (malformed date range rejected at API boundary).
- ✅ **Test:** `2.9-API-009` malformed `to` date returns 400
  - **Status:** RED - activation lock for R-2.9-8; assert `400 INVALID_QUERY_VALUE` for `to=not-a-date`.
  - **Verifies:** AC2 (malformed date range rejected at API boundary).
- ✅ **Test:** `2.9-API-010` no write endpoints exist for the audit log
  - **Status:** RED - activation lock for R-2.9-1 API immutability; `PUT`/`DELETE /api/v1/audit-log` must return 405.
  - **Verifies:** AC3 (no edit/delete API exists).

### Backend Integration Tests (Testcontainers, 5 tests — all `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

- ✅ **Test:** `2.9-SVC-017` DB trigger guards primary key and `plant_id` columns
  - **Status:** RED - **the flagship RED test for R-2.9-1**. Today `UPDATE audit_log SET plant_id` / `SET id` succeeds because the V16 trigger `OF`-list omits those columns. The scaffold asserts `DataAccessException` — it will FAIL when activated until the trigger (or a documented decision) changes.
  - **Verifies:** AC3 (DB-level immutability across ALL columns).
- ✅ **Test:** `2.9-SVC-018` deleting a plant nulls the audit `plant_id` and keeps entries
  - **Status:** RED - activation lock for R-2.9-11; `PlantService.delete` → FK `ON DELETE SET NULL` → `plant_id` null, entries still listable.
  - **Verifies:** AC1/AC4 (FK behavior documented + tested; scoped visibility of the entry afterwards).
- ✅ **Test:** `2.9-SVC-019` corrupt `previous_value` surfaces read failure today (behavior lock)
  - **Status:** RED - behavior lock for R-2.9-12; today `readJson` throws `IllegalStateException` (500 on list). Documented so a future graceful-decode fix changes this test.
  - **Verifies:** AC2 (robustness decision for corrupt stored JSON).
- ✅ **Test:** `2.9-SVC-020` actor filter treats LIKE wildcards literally
  - **Status:** RED - activation lock for R-2.9-13; actor `yusuf_dev` matched exactly, `yusuf%dev` matches nothing.
  - **Verifies:** AC2 (LIKE escaping — no wildcard injection).
- ✅ **Test:** `2.9-SVC-021` pagination beyond the first page reports accurate totals
  - **Status:** RED - activation lock; page=1 size=2 → 2 items, `totalElements=5`.
  - **Verifies:** AC2 (pagination contract + totalElements accuracy).

### Component Tests (Vitest + Testing Library, 7 tests — all `it.skip`)

**File:** `syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx`

- ✅ **Test:** `[P0]` resets page to 0 when a filter changes while on page > 0 (R-2.9-6)
  - **Status:** RED - **the flagship RED frontend test**. Drives pagination to page 1, changes the `From` date input, asserts captured params `page=0`. Today it fails (`page` stays 1). VERIFIED to fail against current code.
  - **Verifies:** AC5 (filter UX — no stale/out-of-range page).
- ✅ **Test:** `[P1]` renders a skeleton while audit entries are loading — RED activation lock (AC5 loading state).
- ✅ **Test:** `[P1]` renders the error state and refetches when Retry is clicked — RED activation lock (AC5 error+retry state).
- ✅ **Test:** `[P1]` renders the empty state when there are no entries and no filters — RED activation lock (AC5 empty state).
- ✅ **Test:** `[P1]` renders filtered-empty state and Reset filters clears filters and page — RED activation lock (AC5 filtered-empty+reset).
- ✅ **Test:** `[P1]` renders the desktop dense table with sortable headers and expandable before/after detail — RED activation lock (AC5 desktop table).
- ✅ **Test:** `[P1]` renders mobile cards grouped by date with expandable detail — RED activation lock (AC5 mobile cards).

### E2E Tests (Playwright, 5 tests — all `test.skip`)

**File:** `syncro/apps/web/tests/e2e/audit-log.atdd-red.spec.ts`

- ✅ **Test:** `2.9-ATDD-E2E-001` SUPER_ADMIN sees heading and a dense sortable desktop table — RED (needs running app + auth).
- ✅ **Test:** `2.9-ATDD-E2E-002` filtering by entity type refetches and resets page to 0 — RED (mirrors R-2.9-6 at browser level).
- ✅ **Test:** `2.9-ATDD-E2E-003` empty list shows "No audit entries yet" — RED.
- ✅ **Test:** `2.9-ATDD-E2E-004` filtered-empty shows "No matching entries" and Reset restores the list — RED.
- ✅ **Test:** `2.9-ATDD-E2E-005` mobile viewport shows date-grouped stacked cards with expandable detail — RED.

---

## Data Factories Created

None. Backend tests use existing `plant(...)` / `persistedUser(...)` / `authenticatedUser(...)` helpers copied from `AuditLogServiceIntegrationTest`; frontend tests use inline fixture builders (`updateEntry()` / `createEntry()`) matching the generated `AuditLogEntryView` type.

---

## Fixtures Created

None new. E2E spec imports the existing `tests/support/fixtures` (Playwright extended fixtures).

---

## Mock Requirements

### Backend (MockMvc slice)

- `@MockitoBean AuditLogService` — `2.9-API-007` stubs `InvalidAuditLogQueryException` on `list`; all others use default stubs. `@MockitoBean JwtTokenService` required by the JWT filter.

### Frontend (Vitest)

- `useListAuditLogEntries` from `@/lib/api/generated/syncro` — module-level mock returning a mutable `auditLogQuery` state and capturing `latestParams` (the flagship R-2.9-6 assertion reads `latestParams.page`).
- `useListPlants` from `@/lib/api/generated/syncro` — returns `{ items: [] }`.
- `usePlantScope` from `@/features/plant-scope/plant-scope-store` — returns `{ scope: { mode: "UNRESTRICTED", availablePlants: [] } }`.
- `useAuthUser` from `@/lib/auth/use-auth-user` — returns a SUPER_ADMIN user.

---

## Required data-testid Attributes

Not required. Tests use resilient queries:

### Audit Log Page (`audit-log-page.tsx`)

- `getByLabelText("From")` / `getByLabelText("To")` — the `type="date"` inputs (labels already wired via `htmlFor`).
- `getByLabelText("Actor")` — actor filter input.
- `getByRole("button", { name: "Go to next page" })` — pagination next button (`sr-only` label already present).
- `getByRole("button", { name: "Reset filters" })` / `getByText("No matching entries")` / `getByText("No audit entries yet")`.
- Breakpoint scoping: desktop `.hidden.md\:block`, mobile `.md\:hidden` (jsdom renders both).
- `data-slot="skeleton"` — used only for loading-state probe (already present in `skeleton.tsx`).

---

## Implementation Checklist

> Red-phase scaffolds stay `@Disabled` / `it.skip` / `test.skip` until a developer activates the current task. Activate ONE at a time: remove the skip, confirm it fails (red), implement the minimal fix, confirm green. Backend commands use `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"`.

### Test: 2.9-SVC-017 (P0) — DB trigger guards `id` and `plant_id`

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Decide R-2.9-1 resolution: either (a) fix `V16__create_audit_log.sql` so the UPDATE trigger also rejects `id` and `plant_id`, OR (b) accept + document that `plant_id` is mutable via SQL (FK `ON DELETE SET NULL` requires a separate `BEFORE UPDATE OF plant_id` trigger that only permits the FK's nulling, or an INSTEAD handler) and scope the scaffold assertion to `id` only.
- [ ] If fixing the trigger: add a new migration `V17__...` (do NOT edit applied V16) that recreates/extends the trigger to cover `id` and `plant_id`, keeping the FK `ON DELETE SET NULL` working.
- [ ] Verify `2.9-SVC-007` (actor_name) still passes and `2.9-SVC-018` (FK SET NULL) still passes after the fix.
- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapIntegrationScaffoldTest#dbTriggerGuardsPrimaryKeyAndPlantId"`
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 2-3 hours (incl. migration + FK action verification)

### Test: 2.9-SVC-018 (P2) — FK `ON DELETE SET NULL` on plant delete

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapIntegrationScaffoldTest#plantDeleteNullsAuditPlantId"`
- [ ] Confirm behavior already matches (expected green when activated); if red, inspect `PlantService.delete` + V16 FK.
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 1-2 hours

### Test: 2.9-SVC-019 (P2) — corrupt JSON behavior lock

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] This is a behavior lock. Decide R-2.9-12: either keep `IllegalStateException` (500) and leave the test asserting it, or add graceful decode (skip entry / raw string / error item) and update the test to assert the new behavior.
- [ ] If graceful handling is chosen, implement in `AuditLogService.readJson` and update the scaffold assertion.
- [ ] ✅ Test reflects decided behavior (green phase)

**Estimated Effort:** 1-2 hours

### Test: 2.9-SVC-020 (P2) — actor LIKE escaping

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapIntegrationScaffoldTest#actorFilterEscapesLikeWildcards"`
- [ ] Confirm `normalizeActor` escaping (already present) yields green; if red, fix escaping + ensure the JPQL uses the escaped param.
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 1 hour

### Test: 2.9-SVC-021 (P2) — pagination accuracy

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/application/AuditLogAtddGapIntegrationScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate + run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapIntegrationScaffoldTest#paginationBeyondFirstPage"`
- [ ] Confirm `search(...)` returns correct slice + `getTotalElements`; fix if `totalElements` is page-local.
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 1 hour

### Tests: 2.9-API-007/008/009/010 (P1) — API 400 + immutability contract

**File:** `syncro/apps/backend/src/test/java/com/syncro/audit/api/AuditLogAtddGapApiScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate each and run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapApiScaffoldTest"`
- [ ] Confirm handler maps `InvalidAuditLogQueryException`/`MethodArgumentTypeMismatchException` → `400 INVALID_QUERY_VALUE` (AuditLogExceptionHandler already does); confirm no write routes (expected 405).
- [ ] If `2.9-API-010` surfaces 401 instead of 405 (auth-before-routing), align the assertion with the security chain contract.
- [ ] ✅ Tests pass (green phase)

**Estimated Effort:** 1 hour

### Test: [P0] audit-log-page page reset on filter change (R-2.9-6) — flagship

**File:** `syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx`

**Tasks to make this test pass:**

- [ ] Fix `audit-log-page.tsx`: call `setPage(0)` in the `entityType`, `actor`, `from`, `to`, and `sort` change handlers (currently only plant/size/reset reset the page).
- [ ] Activate the test (remove `it.skip`), run: `npm --prefix syncro/apps/web run test:unit -- --run src/features/audit-log/audit-log-page.atdd.test.tsx`
- [ ] Confirm it fails BEFORE the fix (red) and passes AFTER (green).
- [ ] ✅ Test passes (green phase)

**Estimated Effort:** 0.5-1 hour (fix) — harness already scaffolded

### Tests: [P1] audit-log-page UI states + desktop/mobile (6 acceptance locks)

**File:** `syncro/apps/web/src/features/audit-log/audit-log-page.atdd.test.tsx`

**Tasks to make this test pass:**

- [ ] Activate each lock (loading skeleton, error+retry, empty, filtered-empty+reset, desktop table + expandable detail, mobile cards + expandable detail).
- [ ] Run: `npm --prefix syncro/apps/web run test:unit -- --run src/features/audit-log/audit-log-page.atdd.test.tsx`
- [ ] All 6 locks are expected to pass when activated (implementation already satisfies them); fix any red as a real regression.
- [ ] ✅ Tests pass (green phase)

**Estimated Effort:** 0.5 hour verification

### Tests: 2.9-ATDD-E2E-001..005 (P1) — browser journeys

**File:** `syncro/apps/web/tests/e2e/audit-log.atdd-red.spec.ts`

**Tasks to make this test pass:**

- [ ] Requires running backend (`SPRING_PROFILES_ACTIVE=local` + Docker postgres), seeded audit entries, and an authenticated SUPER_ADMIN session (operator actions #1-#5 in the spec).
- [ ] Activate each test (remove `test.skip`), run: `npm --prefix syncro/apps/web run test:e2e`
- [ ] Confirm `2.9-ATDD-E2E-002` (page reset) passes only after the R-2.9-6 fix is applied.
- [ ] ✅ Tests pass (green phase)

**Estimated Effort:** 3-6 hours (browser + auth + seeded data)

---

## Running Tests

```bash
# Backend — activate one at a time, run the specific scaffold class
$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapApiScaffoldTest"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogAtddGapIntegrationScaffoldTest"

# Full existing suite (regression)
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="AuditLogControllerTest,AuditLogServiceIntegrationTest,AuditLogWiringIntegrationTest"

# Frontend component scaffold
npm --prefix syncro/apps/web run test:unit -- --run src/features/audit-log/audit-log-page.atdd.test.tsx

# Frontend E2E scaffold (requires running app + auth)
npm --prefix syncro/apps/web run test:e2e

# Static checks
npm --prefix syncro/apps/web run check
npm --prefix syncro/apps/web run build
```

---

## Red-Green-Refactor Workflow

### RED Phase (Complete) ✅

- ✅ All tests written as red-phase scaffolds: 4 backend API `@Disabled`, 5 backend integration `@Disabled`, 7 component `it.skip`, 5 E2E `test.skip`.
- ✅ Scaffolds assert EXPECTED behavior (no placeholder assertions); 2 flagship RED tests (`2.9-SVC-017` DB trigger gap, `[P0]` page-reset) are verified to fail against current code.
- ✅ Fixtures/helpers documented; mock requirements listed; Biome + typecheck clean; backend compiles.

### GREEN Phase (DEV Team - Next Steps)

1. Pick one scaffolded test from the implementation checklist (start with P0: `2.9-SVC-017` or the `[P0]` page-reset).
2. Remove `@Disabled` / `it.skip` / `test.skip` for that test and confirm it fails first.
3. Read the test; implement the minimal code fix.
4. Run the test; verify green.
5. Check off the task; move to the next.

### REFACTOR Phase (DEV Team - After All Tests Pass)

1. Verify all activated tests pass; review for quality.
2. Ensure `2.9-SVC-018/020/021` and API locks still pass after the trigger change.
3. Update `test-design-story-2-9-immutable-audit-log.md` risk statuses (R-2.9-1, R-2.9-6, R-2.9-8, R-2.9-12).
4. When all activated tests pass, manually update story status in `sprint-status.yaml`.

---

## Notes

- **Two intentional RED flagships:** (1) `2.9-SVC-017` proves the V16 trigger `OF`-list omits `id`/`plant_id` (R-2.9-1) — resolving it needs a decision (fix trigger vs. document `plant_id` mutability) because the FK `ON DELETE SET NULL` must keep working. (2) `[P0]` page-reset (R-2.9-6) — `audit-log-page.tsx` only resets `page` on plant/size/reset.
- **Activation-lock vs behavior-lock:** the P1/P2 locks assert already-implemented behavior and are expected to go green immediately once activated; `2.9-SVC-019` is a behavior lock documenting today's 500 on corrupt JSON.
- Existing evidence already covers most ACs (`AuditLogControllerTest` 6, `AuditLogServiceIntegrationTest` 8, `AuditLogWiringIntegrationTest` 8 — all passing). This run adds the uncovered-gap surface.
- Operator verification (boot, API smokes, browser, psql immutability) remains gated by the spec's `awaiting-operator` status.

---

## Knowledge Base References Applied

- **api-testing-patterns / api-request** - MockMvc slice + Testcontainers patterns mirrored from existing audit tests.
- **component-tdd** - Vitest + Testing Library component scaffold with module-level hook mocks.
- **test-quality** - Given-When-Then, one assertion intent per test, deterministic data, breakpoint-scoped queries.
- **selector-resilience** - `getByRole`/`getByLabel`/`getByText`, sr-only labels, no class-string brittle selectors (except breakpoint wrappers).
- **test-levels-framework** - Integration (DB constraints), API (contract), Component (UI states), E2E (journeys).
- **test-priorities-matrix / risk-governance** - P0-P3 ordering driven by the story risk register.

---

## Contact

- Tag @TEA in team standup
- Consult `./resources/knowledge` for testing best practices

---

**Generated by BMad TEA Agent** - 2026-08-07
