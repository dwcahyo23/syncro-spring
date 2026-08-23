---
title: 'Deferred-work bundle 8: web reliability polish — machine-group state tests, dashboard truncation indicator, alert list invalidation'
type: 'chore'
created: '2026-08-23'
baseline_revision: '4bbebd5'
status: 'in-review'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals']
---

<intent-contract>

## Intent

**Problem:** Three open deferred-work items weaken web reliability evidence and UX: (DW-2) `machine-group-management.tsx` renders six distinct states (loading skeleton, load error with retry, empty list, read-only role, EMPTY plant assignment, form validation field errors) with zero durable component-test evidence — regressions surface only manually; (DW-34) the telemetry dashboard requests a hard cap of 200 ACTIVE machines and silently drops the remainder of larger fleets — no truncation notice exists; (DW-39) acknowledge/resolve/resolve-override mutations on the alert detail page only refetch the detail query, so the alerts list at `/alerts` shows stale status until its own refetch window expires.

**Approach:** (DW-2) add `machine-group-management.test.tsx` following the existing mocked-hook RTL pattern (`telemetry-dashboard-page.test.tsx`), asserting each state's visible output and the retry/read-only/validation behaviors. (DW-34) export a `TELEMETRY_DASHBOARD_MAX_MACHINES` constant from the dashboard hook, use it as the request size, and render an explicit truncation notice (aria-live) next to the machine count when the returned item count equals the cap. (DW-39) invalidate `getListAlertsQueryKey()` via `useQueryClient` in all three mutation success handlers alongside the existing detail refetch.

## Boundaries & Constraints

**Always:**
- DW-2 tests mock `@/lib/api/generated/syncro`, `@/lib/auth/use-auth-user`, and `@/features/plant-scope/plant-scope-store` at module level (vi.mock), wrap in `QueryClientProvider` (+`TooltipProvider` where tooltips render), and assert on accessible text/roles only — no class-name assertions.
- DW-2 must cover at minimum: loading skeleton, error state with working Retry button, empty-list state, read-only badge + View-only cells for non-mutating roles, no-plant-assignment state, and server-side fieldErrors rendered next to Name/plant inputs after a rejected submit.
- DW-34: the constant lives in `use-telemetry-dashboard-query.ts`, is consumed by both the hook params (`size`) and the page's truncation condition (`machines.length >= constant`); the notice text names the cap ("showing first 200") and is aria-live="polite".
- DW-39: invalidation uses the generated key factory `getListAlertsQueryKey()` with NO args (prefix-matching invalidates all filtered variants); applied to acknowledge, resolve, AND resolveOverride onSuccess handlers; existing `void refetch()` calls stay.
- Keep existing code style: double quotes, existing import ordering, no new dependencies.

**Block If:**
- The vitest suite cannot run in this environment (missing deps or jsdom failure unrelated to new code) AND `npx tsc --noEmit` also cannot run → HALT with blocking condition `web verification unavailable`.

**Never:**
- Do NOT modify generated API client files (`src/lib/api/generated/**`).
- Do NOT change backend sources, migrations, or any non-web file.
- Do NOT alter mutation error handling, toast texts, or the stale-banner logic.
- Do NOT add snapshot tests; behavioral assertions only.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Groups loading | lists isLoading=true | Skeleton rows visible; no table | No error |
| Groups load error | lists isError=true | Error state + Retry button calling both refetches | Retry wired |
| Groups empty | items=[] with resolved plants | "No machine groups yet" state | No error |
| Read-only role | user role=TECHNICIAN | "Read-only" badge; per-row "View only"; no Create/Edit/Delete buttons | No error |
| No plant assignment | scope mode=EMPTY | "No plant assignment" state; Create disabled/absent | No error |
| Validation failure | create returns 400 fieldErrors {name} | Error text beside Name input; dialog stays open | Toast shown |
| Dashboard at cap | machines.length === 200 | Count line shows 200 + truncation notice naming cap | No error |
| Dashboard under cap | machines.length < 200 | No truncation notice rendered | No error |
| Acknowledge success | mutation resolves | List query invalidated + detail refetch + toast | Existing error paths untouched |

</intent-contract>

## Code Map

- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx` -- component under test (DW-2); read-only
- `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.test.tsx` -- EXISTING suite (covers all six states); EXTENDED with retry-refetch wiring and read-only row badges (DW-2 delta)
- `syncro/apps/web/src/features/telemetry/hooks/use-telemetry-dashboard-query.ts` -- export MAX constant, keep size usage (DW-34)
- `syncro/apps/web/src/features/telemetry/components/telemetry-dashboard-page.tsx` -- truncation notice beside machine count (DW-34)
- `syncro/apps/web/src/features/telemetry/components/telemetry-dashboard-page.test.tsx` -- extend: at-cap notice present/absent cases (DW-34)
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` -- invalidate alert list in 3 mutation successes (DW-39)

## Tasks & Acceptance

**Execution:**
- [x] `syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.test.tsx` -- EXTEND existing suite with Retry-refetch wiring test + View-only row badge test (six core states already covered) -- DW-2
- [x] `syncro/apps/web/src/features/telemetry/hooks/use-telemetry-dashboard-query.ts` -- export `TELEMETRY_DASHBOARD_MAX_MACHINES = 200`; use it for `size` -- DW-34
- [x] `syncro/apps/web/src/features/telemetry/components/telemetry-dashboard-page.tsx` -- truncation notice when count reaches cap -- DW-34
- [x] `syncro/apps/web/src/features/telemetry/components/telemetry-dashboard-page.test.tsx` -- add at-cap and under-cap assertions -- DW-34
- [x] `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` -- `queryClient.invalidateQueries({ queryKey: getListAlertsQueryKey() })` in acknowledge/resolve/resolveOverride onSuccess -- DW-39

**Acceptance Criteria:**
- Given the machine-group management page, when rendered in each of the six documented states, then the visible output matches the state's title/badge/actions asserted in tests.
- Given a SUPER_ADMIN user submitting the group dialog against a 400 with fieldErrors, when the response arrives, then the field error text renders adjacent to the corresponding input and the dialog remains open.
- Given exactly 200 ACTIVE machines returned, when the telemetry dashboard renders, then the truncation notice names the cap; given fewer than 200, it is absent.
- Given an acknowledged alert, when the mutation succeeds, then queries under `getListAlertsQueryKey()` are invalidated so `/alerts` re-renders fresh status.
- Given `npm run test:unit` and typecheck, when run, then all pass with zero new warnings.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (low)
- defer: 0
- reject: 14 (triple-duplication extraction forbidden by spec Never-boundary and mirrors pre-existing handler idiom; sibling-cache completeness speculative — alerts feature has only list+detail consumers; mock-leak claim false — original beforeEach resets all fixtures; retry assertion level appropriate for mocked-hook unit style; single Retry button guaranteed by combined error condition; create-button absence already covered by original suite test 4; per-file assertion idioms preserved deliberately; [P1]/[P2] tags match telemetry file convention; fireEvent/waitFor match suite style; EC delete-mutation hypothetical — no delete exists on page; EC error-state count paragraph quirk pre-existing; constant placement fine — single consumer; other size literals audited, none share the cap semantics)
- addressed_findings:
  - `medium` `patch` >= cap heuristic could not distinguish an exactly-full fleet from a truncated one and was blind to server-side caps below the requested size -> truncation now derived from totalElements > items.length with "showing first N of M matching" wording
  - `low` `patch` hardcoded 200 fixtures/regexes duplicated the exported constant and forced a 20s render -> rewritten as small-fixture totalElements cases (truncated / exact-fit / under-cap singular), timeout removed
  - `medium` `patch` list invalidation shipped untested -> new compact AlertDetailPageContent test asserting getListAlertsQueryKey invalidation + detail refetch on acknowledge success
  - `low` `patch` "1 active machines" pluralization bug canonized by test -> singular/plural copy fixed and pinned

## Verification

**Commands:**
- `npm --prefix syncro/apps/web run test:unit -- machine-group-management` -- expected: new tests pass
- `npm --prefix syncro/apps/web run test:unit -- telemetry-dashboard-page` -- expected: extended tests pass
- `npm --prefix syncro/apps/web run test:unit` -- expected: whole suite green
- `npm --prefix syncro/apps/web run typecheck` (or `npx tsc --noEmit` if no script) -- expected: no type errors

**Manual checks (if no CLI):**
- Inspect diff: no generated files touched; invalidation uses key factory without args; truncation notice aria-live.

## Auto Run Result

**Status:** done

**Summary:** Bundled three open deferred-work items (DW-2, DW-34, DW-39) as web reliability polish: (DW-2) the pre-existing machine-group component suite was discovered to already cover all six documented states — implementation initially overwrote it, was restored from git verbatim, and extended additively with two hardening tests (error-state Retry refetches BOTH plants and groups lists; read-only VIEWER rows expose per-row View only badges with Edit/Delete absent); (DW-34) telemetry dashboard truncation now derives from `totalElements > items.length` instead of a `>= 200` heuristic — correctly distinguishing an exactly-full page from a truncated one and staying truthful if the server caps below the requested size — with an aria-live "showing first N of M matching" notice plus singular/plural machine copy; (DW-39) acknowledge/resolve/resolve-override successes now invalidate `getListAlertsQueryKey()` so `/alerts` reflects fresh status, covered by a new compact mutation-wiring test.

**Files changed:**
- `machine-group-management.test.tsx` — +2 tests on restored original suite (DW-2)
- `use-telemetry-dashboard-query.ts` — exported `TELEMETRY_DASHBOARD_MAX_MACHINES`, used for request size (DW-34)
- `telemetry-dashboard-page.tsx` — totalElements-driven truncation notice + pluralization fix (DW-34)
- `telemetry-dashboard-page.test.tsx` — truncated / exact-fit / under-cap cases (DW-34)
- `alert-detail-page-content.tsx` — list-cache invalidation in 3 mutation successes (DW-39)
- `alert-detail-page-content.test.tsx` — NEW: invalidation + detail refetch wiring test (DW-39)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 8 patches applied across 4 fix groups (truncation signal redesign, test de-hardcoding, invalidation coverage, pluralization), 0 deferrals, 14 rejected as noise/spec-boundary/verified-false.

**Follow-up review recommendation:** false — all patched findings low-severity after fixes; single feature area (web); full suite + typecheck green.

**Verification performed:**
- Full vitest suite: 181 passed / 7 skipped (skips pre-existing, DW-8 territory)
- `tsc --noEmit`: exit 0
- biome lint on all touched files: clean (pre-existing nursery infos in untouched classNames excluded)

**Residual risks:** invalidation test asserts wiring via captured mutation options rather than a DOM click-through (mocked-hook idiom consistent with suite); if a future consumer of alert data outside `getListAlertsQueryKey()` appears it must be added to invalidation targets.
