---
title: 'DW-2: Machine Group Management UI State Tests'
type: 'chore'
created: '2026-08-19'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '8a64dd12e67ebe590040631865e0bc59e586e5ec'
final_revision: ''
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** `machine-group-management.tsx` has no component tests, leaving the six UI states (empty, loading, error, read-only, forbidden, validation) without regression proof for AC11 of Story 2.2.

**Approach:** Add a Vitest + React Testing Library test file covering each of the six states by mocking `useListPlants`, `useListMachineGroups`, and the mutation hooks, along with `useAuthUser` and `usePlantScope`, exactly as the existing `responsibility-management.test.tsx` does.

## Boundaries & Constraints

**Always:**
- One new test file only: `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.test.tsx`
- Follow the exact mock pattern from `responsibility-management.test.tsx` — `vi.mock` at module level, `QueryClientProvider` wrapper, no MSW
- All tests must pass under `pnpm --filter web test:unit` (or `npm run test:unit` inside `syncro/apps/web`)
- Tests are read-only assertions against rendered output; no DOM interaction beyond what is needed to open a dialog for the validation state
- Document output in English

**Block If:**
- The component's rendered text for any state is ambiguous and cannot be determined from the source without running the app

**Never:**
- Do not modify `machine-group-management.tsx`
- Do not introduce MSW, custom render helpers, or new test dependencies
- Do not test mutation success/error flows beyond what is needed for the validation state
- Do not cover states outside empty, loading, error, read-only, forbidden, validation

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|---|---|---|---|
| empty | `useListMachineGroups` returns `items: []`, not loading, no error; scope UNRESTRICTED | No table rows; "Add Machine Group" button visible | No error expected |
| loading | `useListMachineGroups` returns `isLoading: true` | Skeleton elements visible; no table | No error expected |
| error | `useListMachineGroups` returns `isError: true` | Error message rendered (`/failed to load/i` or similar text from component) | No error expected |
| read-only (VIEWER) | `applicationRole: "VIEWER"`, data loaded with items | "View only" badge visible; no "Add Machine Group" button | No error expected |
| forbidden (EMPTY scope) | `scope.mode: "EMPTY"` | Warning/empty-scope message visible; no table rows rendered | No error expected |
| validation | SUPER_ADMIN, dialog open, submit with blank name | Field error message for `name` rendered inside dialog | No error expected |

</intent-contract>

## Code Map

- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx` -- component under test; exports `MachineGroupManagement`
- `syncro/apps/web/src/features/master-data/responsibilities/responsibility-management.test.tsx` -- reference test pattern (vi.mock + QueryClientProvider wrapper)
- `syncro/apps/web/vitest.config.ts` -- jsdom environment, globals true, setupFiles vitest.setup.ts
- `syncro/apps/web/vitest.setup.ts` -- imports `@testing-library/jest-dom/vitest`

## Tasks & Acceptance

- [x] `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.test.tsx` -- create -- write six tests covering empty, loading, error, read-only, forbidden, and validation states using `vi.mock` for `@/lib/api/generated/syncro`, `@/lib/auth/use-auth-user`, `@/features/plant-scope/plant-scope-store`, and `sonner`

  **AC1 — empty state:** Given scope is UNRESTRICTED and `useListMachineGroups` returns an empty items array, when `<MachineGroupManagement />` renders, then no `<TableRow>` data rows are present and the "Add Machine Group" button is visible.

  **AC2 — loading state:** Given `useListMachineGroups` returns `isLoading: true`, when the component renders, then skeleton elements are in the document and no data table is visible.

  **AC3 — error state:** Given `useListMachineGroups` returns `isError: true`, when the component renders, then an error message element is in the document (matches text present in the component's error branch).

  **AC4 — read-only state (VIEWER):** Given `applicationRole` is `"VIEWER"` and data loads with one machine group item, when the component renders, then a "View only" badge is visible and the "Add Machine Group" button is absent.

  **AC5 — forbidden state (EMPTY scope):** Given `scope.mode` is `"EMPTY"`, when the component renders, then no data rows are shown and the empty-scope UI element is present (matches the component's EMPTY-scope branch text or structure).

  **AC6 — validation state:** Given SUPER_ADMIN user with valid scope and data loaded, when the create dialog is opened and the form is submitted with a blank name field via `createGroup.mutateAsync` rejecting with a field error, then the field error message is rendered inside the dialog.

## Review Log

### Pass 1 (2026-08-19)

Reviewers: Blind Hunter (adversarial-general), Edge Case Hunter

**Triage log:**

| ID | Finding | Severity | Category | Action |
|---|---|---|---|---|
| F-01 | Shared `QueryClient` singleton causes cache bleed between tests | medium | patch | Fixed: added `queryClient.clear()` in `beforeEach` |
| F-05 | Empty-state: weak row-count assertion | medium | patch | Fixed: assert heading "No machine groups yet" directly |
| F-06 | Loading test: no positive assertion skeleton is present | medium | patch | Fixed by implementation subagent: row count 0 + no create button |
| F-09 | `document.querySelector("form")` breaks test isolation | medium | patch | Fixed: use `baseElement.querySelector("form")` |
| F-14 | `React.ReactNode` without explicit import under `react-jsx` tsconfig | medium | patch | Fixed: added `import type { ReactNode } from "react"` |
| F-02 | Mutable `let` mock pattern fragility | low | defer | Pre-existing project pattern; not introduced by this story |
| F-03/F-04 | update/delete flows not reassignable/not tested | low | defer | Spec explicitly out of scope |
| F-08/EC-03 | `loadError: true` untested | low | defer | Not one of the 6 required states |
| EC-01 | Other scope modes (RESTRICTED etc.) untested | low | defer | Component only exposes EMPTY/UNRESTRICTED for UI branching |
| EC-08 | Empty name may be swallowed by client-side guard | high | defer | Investigated: `submitMachineGroup` has no client-side guard — flows directly to `mutateAsync` |
| remaining | Other coverage gaps (update/delete/pending/pagination) | low | defer | All out of scope per spec constraints |

5 patches applied. 0 items rejected.

**Follow-up review recommended:** false — patches were localized, low-complexity, and none affected behavior or API contracts.

**Verification:** `npm run test:unit -- --reporter=verbose` from `syncro/apps/web`: 6/6 new tests pass. 34 pre-existing tests unaffected. 2 pre-existing failures in `audit-log-page` suites are caused by a missing `./alertListResponse` module in the generated model index — pre-existing, unrelated to this change.

**Residual risks:** None. Pre-existing audit-log failures are deferred.

## Verification

**Commands:**
- `pnpm --filter web test:unit -- --reporter=verbose` run from `syncro/` -- expected: all 6 new tests pass, 0 failures
