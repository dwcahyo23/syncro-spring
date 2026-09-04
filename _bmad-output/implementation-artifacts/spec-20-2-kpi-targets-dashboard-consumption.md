---
title: 'Story 20-2: KPI Targets & Dashboard Consumption (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-04'
status: 'done'
baseline_revision: '24147d9'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-20-context.md'
  - '_bmad-output/implementation-artifacts/spec-20-1-kpi-materialized-tables.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Story 20-1 shipped the materialization engine, target CRUD, and the scope-filtered materialized read API, but the actual-vs-target comparison is not computed anywhere (the response carries raw rows + a raw TargetView and leaves the verdict to the client — violating "all KPI computation backend-owned"), the target is joined only when the caller passes `plantId`, and no dashboard consumes the materialized monthly rows yet (the 14-2 analytics page still computes live; 20-1's AC3 was explicitly deferred here).

**Approach:** Compute the comparison verdict server-side in `KpiQueryService` (per-row target join, stable uppercase status string), then add a "Monthly KPI" consumption surface to the analytics dashboard that reads `GET /api/v1/kpi/materialized/{type}` and renders actual vs target with non-color-only labels, plus a target-configuration dialog (SUPER_ADMIN/MANAGER_MAINTENANCE) writing through `PUT /api/v1/kpi/targets`. The 14-2 rolling-window live endpoints stay untouched (their contract is separate; the monthly materialized view is the new consumption path).

## Boundaries & Constraints

**Always:**
- Comparison verdict computed backend-side: MTBF `actual >= target` → ON_TARGET else BELOW_TARGET; MTTR/breakdown `actual <= target` → ON_TARGET else ABOVE_TARGET (lower is better); missing target or missing actual → NO_TARGET / INSUFFICIENT_DATA (never a fabricated verdict). MAR/PM-completion/technician rows have no comparable column in `kpi_target` (OEE percents are baseline factors, not MAR/PM targets) → always NO_TARGET with actuals rendered. MTTR verdict compares the actual-working variant when present, else wall-clock (matches the 14-2 reference dashboard).
- Status is a stable uppercase contract string consumed by the frontend (never branch UI on translated labels or color)
- Target join applies per row (every plant-level row carries its plant's target verdict), not only when the caller passes `plantId`
- Frontend renders only: no KPI math, no verdict logic in TS beyond formatting
- Target config UI visible only for roles the backend allows (SUPER_ADMIN, MANAGER_MAINTENANCE); security stays server-side (20-1 role gate already enforced)
- Dashboard states: loading, error, empty, INSUFFICIENT_DATA (explicit badge, never a zero), stale refresh-log evidence surfaced when the latest pass FAILED
- Non-native shadcn/Radix controls for month select and dialog (project UI rule)

**Block If:**
- The materialized response shape would need a breaking change for an existing consumer → HALT (20-1's endpoint has no consumers yet, so additive fields are safe; verify before assuming)

**Never:**
- No changes to the 14-2 live analytics endpoints (`/api/v1/dashboard/mtbf-mttr`, `/technician-kpi`) or `DashboardAnalyticsService`
- No new frontend dependencies, no server-side PDF, no on-the-fly KPI computation in the new view
- No target mutation path that bypasses the 20-1 audit trail

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| MTBF above target | machine row 10.42 days, target 8.00 | verdict ON_TARGET on the row | — |
| MTTR above target (worse) | plant row 75 min, target 60 min | verdict ABOVE_TARGET (lower-is-better) | — |
| No target configured | row present, kpi_target missing for plant/month | verdict NO_TARGET, actual still shown | — |
| No materialized row | month never refreshed | response status INSUFFICIENT_DATA, empty rows | frontend renders explicit badge |
| Manager configures target | PUT /api/v1/kpi/targets valid body | 200 + audit row (20-1 path) | 403 FORBIDDEN for other roles; 400 VALIDATION_ERROR on out-of-range |
| Leader opens Monthly KPI | scope = own machine groups | only in-scope rows with per-row verdicts | 403 envelope on out-of-scope plantId |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiQueryService.java` -- add per-row target join + server-side verdict to the materialized response records
- `syncro/apps/backend/src/main/java/com/syncro/kpi/api/KpiTargetController.java` -- unchanged endpoints; response shape grows verdict fields
- `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiTargetService.java` -- reuse as-is (upsert + audit from 20-1)
- `syncro/apps/backend/src/test/java/com/syncro/kpi/application/KpiQueryServiceTest.java` -- extend: verdict computation per metric direction, NO_TARGET, per-row join
- `syncro/apps/web/src/features/analytics/analytics-page-content.tsx` -- add "Monthly KPI" tab + target dialog wiring
- `syncro/apps/web/src/features/analytics/hooks/use-mtbf-mttr.ts` -- pattern anchor for the new hand-written hooks
- `syncro/apps/web/src/features/analytics/hooks/use-kpi-materialized.ts` -- create: GET /api/v1/kpi/materialized/{type}?month=&plantId=
- `syncro/apps/web/src/features/analytics/hooks/use-kpi-targets.ts` -- create: GET /api/v1/kpi/targets + PUT mutation with revalidation
- `syncro/apps/web/src/features/analytics/components/kpi-target-dialog.tsx` -- create: per-plant/month target form (RHF+Zod, non-native controls)
- `syncro/apps/web/src/components/month-picker.tsx` -- reuse for month selection
- `syncro/apps/web/src/features/plant-scope/plant-scope-store.ts` -- active plant source

## Tasks & Acceptance

**Execution:**
1. `syncro/apps/backend/src/main/java/com/syncro/kpi/application/KpiQueryService.java` -- extend -- per-row target lookup + `targetStatus` verdict string on every row record (MTBF/MTTR/MAR/PM/technician/breakdown); keep MaterializedResponse additive
2. `syncro/apps/backend/src/test/java/com/syncro/kpi/application/KpiQueryServiceTest.java` -- extend -- verdict matrix tests (both directions, NO_TARGET, null actual)
3. `syncro/apps/web/src/features/analytics/hooks/use-kpi-materialized.ts` -- create -- typed query hook for the materialized endpoint (month param, plantId optional)
4. `syncro/apps/web/src/features/analytics/hooks/use-kpi-targets.ts` -- create -- list + upsert mutation hooks with query invalidation and success/error toast
5. `syncro/apps/web/src/features/analytics/components/kpi-target-dialog.tsx` -- create -- target form (breakdown target, MTBF days, MTTR minutes, OEE percents), Zod range validation mirroring backend, role-gated trigger
6. `syncro/apps/web/src/features/analytics/analytics-page-content.tsx` -- extend -- "Monthly KPI" tab: month picker + per-type actual vs target rows with status badges (text labels, non-color-only), INSUFFICIENT_DATA badge, FAILED-refresh warning, Configure Targets button
7. `syncro/apps/web/src/features/analytics/analytics-page-content.test.tsx` -- extend -- monthly tab renders actual vs target verdicts, insufficient-data state, dialog role-gating

**Acceptance Criteria:**
- Given `kpi_target` exists for a plant/month, when the materialized read runs, then every in-scope row carries a backend-computed verdict (ON_TARGET/BELOW_TARGET/ABOVE_TARGET/NO_TARGET) alongside its actual
- Given a leader opens the Monthly KPI view, when it loads, then actual vs target renders with text status labels (never color-only) and an explicit insufficient-data state for months without rows
- Given a MANAGER_MAINTENANCE configures a target via the dialog, when the PUT succeeds, then the value is stored, audit-logged (20-1 path), and the next materialized read reflects the new verdict; a TECHNICIAN sees no configure affordance and is rejected server-side

### Review Findings

**Resolved decisions (unattended run — spec text amended, code kept):**
- [x] [Review][Decision] MAR/PM "OEE/percent metrics" verdict clause unsatisfiable — `kpi_target` has no MAR/PM columns (OEE percents are baseline factors); implementation's NO_TARGET is correct. Spec "Always" amended to match. KEEP code.
- [x] [Review][Decision] MTTR variant selection (`actualWorking ?? wallClock`) unspecified — implementation follows the 14-2 reference dashboard. Spec amended to pin the rule. KEEP code.

**Patch (applied this pass):**
- [x] [Review][Patch] Dialog: `monthlyBreakdownTarget` accepts decimals but backend is `Integer` — add `Number.isInteger` refine [kpi-target-dialog.tsx:46]
- [x] [Review][Patch] Dialog: `targetsQuery.isError` never rendered — existing targets silently show empty; add error state [kpi-target-dialog.tsx:118-138]
- [x] [Review][Patch] Refresh button `disabled` ignores monthly queries' `isLoading` (spinner/refetch already include them) [analytics-page-content.tsx:147]
- [x] [Review][Patch] MTTR wall-clock fallback branch untested — backend fixture (wall=75, working=null → ABOVE_TARGET vs 60) + frontend fixture (renders "75 min") [KpiQueryServiceTest.java, analytics-page-content.test.tsx]
- [x] [Review][Patch] Lower-better equality boundary unpinned — breakdown count == target → ON_TARGET test [KpiQueryServiceTest.java]
- [x] [Review][Patch] Dialog write path untested — submit with empty field asserts `null` in payload (partial-update semantics) + `useUpsertKpiTarget` invalidates both prefixes [kpi-target-dialog tests]
- [x] [Review][Patch] Verdict JSON contract unverified at serialization boundary — MockMvc test on `GET /api/v1/kpi/materialized/mtbf` asserting `$.mtbfRows[0].targetValue`/`targetStatus` field names (repo pattern: SparepartAlertControllerTest)

**Defer (pre-existing / out of scope):**
- [x] [Review][Defer] Stored targets cannot be cleared (empty → null → keep) — spec never required a clear path; product decision (DW-165)
- [x] [Review][Defer] KPI endpoints absent from committed OpenAPI snapshot — same hand-written-hook precedent as 14-2; snapshot regeneration is a separate chore (DW-166)

**Dismissed (noise/false-positive):** monthly-tab blank-cards scenario unreachable (`!isEnabled` early-returns a skeleton before tabs render); out-of-scope `vendor.d.ts`/`role-mapping.test.tsx` edits are load-bearing for the tsc gate and recorded in the Spec Change Log instead.

**Failed layers:** blind-hunter (stopped after 60+ min; its ground covered by the other three layers).

## Spec Change Log

- Pass 1 (2026-09-04, review): "Always" verdict clause amended — MAR/PM/technician have no comparable `kpi_target` column → NO_TARGET with actuals; MTTR verdict variant pinned to `actualWorking ?? wallClock`. KEEP instructions: verdict() direction table, targetsByPlant dedup, additive row records. Out-of-scope edits retained (load-bearing for the tsc gate): `vendor.d.ts` react-hook-form shim removal (real package 7.84.0 ships types), `role-mapping.test.tsx` optional-`data` mock typing (pre-existing tsc error).

## Review Triage Log

- Pass 1 (2026-09-04): layers edge-case-hunter + verification-gap + acceptance-auditor returned; blind-hunter stopped as failed layer (ran 60+ min, ground covered by the other three). Triage: 2 decision (resolved by amending spec text — MAR/PM NO_TARGET and MTTR variant precedence pinned), 7 patch (all applied; the dialog fix also uncovered a real bug: native step-validation shadowed the zod message, fixed with `noValidate`), 2 defer (DW-165/166), 2 dismissed (blank-cards unreachable; out-of-scope edits recorded in Spec Change Log). Verification: `mvn test -Dtest=*Kpi*` → 52 green; `tsc --noEmit` → 0; `biome check` → clean; `vitest src/features/analytics` → 28 green.

## Design Notes

- Verdict direction table (backend enum-free, plain strings): MTBF higher-better; MTTR, breakdown-count lower-better; MAR/PM-completion/OEE higher-better; technician metrics have no target column in kpi_target → always NO_TARGET (rows still render actuals).
- Target lookup: one `findByPlantIdAndMonth` per distinct plant in the result set (small N; no N+1 concern at pilot scale). `ponytail: per-plant lookup, batch query if a sweep ever returns many plants`.
- Frontend branches on `targetStatus`/`status` strings only; formatting (hours/percent/stars) stays display-only.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Kpi*"` -- expected: all KPI tests green
- `cd syncro/apps/web && npx tsc --noEmit` -- expected: no type errors
- `cd syncro/apps/web && npx biome check src/features/analytics` -- expected: clean
- `cd syncro/apps/web && npm test -- src/features/analytics` -- expected: analytics tests green

**Manual checks:**
- Monthly KPI tab shows actual vs target with text verdict badges; INSUFFICIENT_DATA month renders explicit badge, not zeros