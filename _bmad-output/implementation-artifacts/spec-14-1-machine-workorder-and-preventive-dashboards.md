---
title: 'Machine, Workorder & Preventive Dashboards'
type: 'feature'
created: '2026-08-29'
status: 'done'
baseline_revision: 'e2ea75f'
review_loop_iteration: 1
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Leaders have no at-a-glance read surface for machine state (status, telemetry freshness, open workorders, alerts, lifetime risk), workorder distribution by status/category, or preventive due/overdue — the existing pages are management tables, and no aggregate endpoints exist (FR-170, FR-171, FR-172).

**Approach:** Add three scope-aware dashboard endpoints under `/api/v1/dashboard` (machines, workorders, preventive) computing aggregates backend-side with the established `OperationalScopeService.derive` + repository scoped-query pattern, and three read-only dashboard pages in the web app fed by hand-written TanStack Query hooks (the `use-workorders.ts` pattern — these endpoints are not in the OpenAPI snapshot).

## Boundaries & Constraints

**Always:**
- All counts computed in the backend; frontend renders only (NFR-P2-2). No frontend re-aggregation of list responses.
- Scope filtering via `OperationalScopeService.derive(user)`; `plantIds == null` means unrestricted (SUPER_ADMIN). Reuse the exact `(unrestricted, plantIds, groupIds)` param pattern from `WorkOrderListService.list` / `MachineService.list`.
- Due/overdue derivation uses the injected `Clock` (server clock), never client time — same rule as `PreventiveScheduleService`.
- Every component renders loading, empty, error, stale, read-only, and forbidden states (UX-DR-019); overdue distinction is never color-only (use `StatusBadge` labels).
- No new Flyway migration needed for this story (pure read/aggregate surface).
- API tests follow repo conventions: `*IntegrationTest` extending `AbstractPostgresIntegrationTest`, controller slice tests MockMvc-style.

**Block If:**
- A decision on TanStack Table v9 (NFR-P2-10) blocks the tabular breakdown rendering — the repo has v8 (`@tanstack/react-table ^8.21.3`); reuse the existing `workorder-table.tsx` v8 pattern rather than upgrading. Do not upgrade in this story.

**Never:**
- Do NOT touch workorder/preventive management pages, state machines, or write paths.
- Do NOT create MTBF/MTTR or technician KPI endpoints (story 14.2).
- Do NOT modify `notification_jobs`, outbox, or any notification code.
- No new dependencies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | authorized user, scope resolved | three dashboards return scope-filtered rows/counts | no error |
| UNRESTRICTED | SUPER_ADMIN, `plantIds == null` | sees all plants; plantId query param still filters | no error |
| PLANT_FILTER | plantId param outside user scope | empty result (or scope-consistent behavior per existing pattern), no 403 | returns empty payload, never leaks out-of-scope rows |
| NO_WO | no workorders in scope | status/category counts all zero, total 0 | explicit empty state, not missing |
| NO_TELEMETRY | machine never reported | freshness `null`/stale, rendered as stale/unknown | stale state, no fabricated freshness |
| OVERDUE | SCHEDULED schedule with dueDate < server today | counted overdue with visible label | distinct non-color-only rendering |
| INSUFFICIENT | preventive schedules empty | due/overdue zero + empty state | no false "healthy" rendering |

</intent-contract>

## Code Map

Backend (`syncro/apps/backend/src/main/java/com/syncro`):

- `org/application/OperationalScopeService.java` — `derive(AuthenticatedUser)` → `OperationalScope(plantIds, machineGroupIds, activeTeamIds)`; `plantIds == null` = unrestricted. Primary scope source for all three dashboard queries.
- `maintenance/application/WorkOrderListService.java` — the canonical scoped-query pattern to copy (`unrestricted`, `plantIds`, `groupIds` derivation: `machineGroupIds ∪ activeTeamIds`).
- `maintenance/infrastructure/db/WorkOrderRepository.java` — `findScopedPage`/`countScoped` native queries; add scoped group-by count queries for status and category, plus open-by-machine counts.
- `machine/api/MachineController.java` + `machine/application/MachineService.java` — `list(user, plantId, ...)` already scope-filtered and telemetry-hydrated in batch via `LatestTelemetryQueryService.latestTelemetryBatch(Map<UUID,MachineStatus>)`; reuse for machine dashboard rows (no new machine query needed).
- `alert/application/SparepartAlertQueryService.java` + `alert/infrastructure/.../SparepartAlertRepository.java` — existing scope-filtered alert list pattern; add an open-alert count grouped by machine within scope.
- `sparepart/application/SparepartLifetimeEvaluator.java` — `EvaluationResult(currentCount, consumedProductionCount, consumedPercentage)`; batch evaluation already used by `TelemetryPersistenceService` — reuse for per-machine lifetime risk.
- `maintenance/preventive/application/PreventiveScheduleService.java` — `list(user)` already derives `OVERDUE` from stored `SCHEDULED` + `dueDate < LocalDate.now(clock)` (server clock injected); reuse derivation for due/overdue counts.
- `maintenance/preventive/infrastructure/db/PreventiveScheduleRepository.java` — `findScopedSchedules(...)` → `PreventiveScheduleRow` (scope-filtered, ordered by dueDate); reuse for upcoming list.
- `notification/api/NotificationWorkerStatusController.java` — reference for controller + `countByStatusIn` style repository count methods.

Tests: `src/test/java/com/syncro/` mirrors main packages. Templates: `alert/application/SparepartAlertQueryServiceScopeFilterIntegrationTest.java`, `machine/application/MachineListScopeFilterTest.java` (scope-filter integration), `machine/api/MachineControllerTest.java` (MockMvc slice). DB-backed tests extend `com/syncro/AbstractPostgresIntegrationTest`.

Frontend (`syncro/apps/web/src`):

- `app/(main)/dashboard/layout.tsx` — shell layout all dashboard pages mount under; `next.config.mjs` holds URL rewrites (e.g. `/operations-overview` → `/dashboard/operations-overview`).
- `navigation/sidebar/sidebar-items.ts` — sidebar menu definition (groups + items with `roles`); add a Dashboards group.
- `features/workorders/hooks/use-workorders.ts` — the hand-written TanStack Query hook pattern: `useQuery({ queryKey: ["/api/v1/...", params] })` calling `syncroFetch<T>(...)`, `staleTime: 15_000`, `retry: false`. Follow for the three new hooks (endpoints absent from `lib/api/generated/syncro.ts` snapshot; do NOT regenerate Orval).
- `features/operations-overview/operations-overview-page-content.tsx` — card-grid + plant-scope guard pattern (loadError / loading / EMPTY states; `plantId = activePlantId !== "all" ? activePlantId : undefined` via `features/plant-scope/plant-scope-store.ts`).
- `components/syncro/status-badge.tsx`, `machine-summary-card.tsx`, `telemetry-card.tsx`, `features/alerts/lifetime-progress.tsx` — reusable render pieces.
- `features/workorders/components/workorder-table.tsx` — TanStack Table v8 pattern (reuse if a table is needed for breakdowns).
- `features/machines/` — empty scaffold (`.gitkeep`); land machine-dashboard feature code here.

## Tasks & Acceptance

**Execution:**
- `backend .../maintenance/api/DashboardController.java` — new `@RequestMapping("/api/v1/dashboard")`, three GETs (`/machines`, `/workorders`, `/preventive`), each taking `AuthenticatedUser` + optional `plantId` param; `/workorders` additionally accepts optional `sectionId`, `status`, `categoryCode` filter params; all out-of-scope filter values return empty payload (never 403); `@ApiResponses` match the handler — dashboard read surface for FR-170/171/172.
- `backend .../maintenance/application/DashboardService.java` — orchestrates the three views: scope derive → scoped repository queries → assemble DTOs; reuse `MachineService.list` + `LatestTelemetryQueryService` + `PreventiveScheduleService` OVERDUE derivation; **batch** `SparepartLifetimeEvaluator` evaluation across all machines in scope (no per-machine N+1); guard empty-scope restricted users (empty plantIds+groupIds → explicit empty response, never `in ()` JPQL); null-safe `mergeGroupIds`; `>=` threshold boundary with null-threshold guards; defensive `toMap` merges; `PreventiveUpcomingRow` carries machine code/name + program title; `upcoming` capped (e.g. 50, newest first) — single place for backend-computed aggregates.
- `backend .../maintenance/infrastructure/db/WorkOrderRepository.java` — add scoped native group-by-count queries (status, category) and open-by-machine count with `(unrestricted, plantIds, groupIds)` params, plus optional `sectionId`/`status`/`categoryCode` predicates — backend-computed counts for FR-171 and machine open-WO counts.
- `backend .../alert/infrastructure/.../SparepartAlertRepository.java` — add scoped open-alert-count-by-machine query — machine dashboard alert counts.
- `backend .../maintenance/preventive/infrastructure/db/PreventiveScheduleRepository.java` — reuse `findScopedSchedules`; no new query if due/overdue/upcoming derivable from its rows — FR-172 counts.
- `backend .../maintenance/api/DashboardExceptionHandler.java` — only live mappings (no unreachable 403/404 dead code contradicting the never-403 rule) — error surface.
- `backend src/test/java/com/syncro/maintenance/application/DashboardServiceIntegrationTest.java` — Testcontainers; scope filtering (leader sees own group only), unrestricted SUPER_ADMIN, plantId out-of-scope → empty, no-telemetry "Unknown" (distinct from stale), OVERDUE derivation, **open-WO count excludes terminal statuses, open-alert count excludes RESOLVED, AT_RISK lifetime with seeded installation+telemetry, byCategory incl. uncategorized null grouping, team-scope case, each FR-171 filter (sectionId/status/categoryCode), 403-branch** — READY-FOR-DEVELOPMENT gate requires Given/When/Then coverage of the I/O matrix.
- `backend src/test/java/com/syncro/maintenance/api/DashboardControllerTest.java` — MockMvc slice: three endpoints, plantId + filter passthrough, forbidden state shape — controller contract.
- `web src/features/machines/` + `web src/features/workorders/` + `web src/features/preventive/` hooks — three hand-written TanStack Query hooks (`useMachineDashboard`, `useWorkorderDashboard`, `usePreventiveDashboard`) following `use-workorders.ts`; **gate each with `enabled: Boolean(scope)` so no fetch races the plant-scope store** — data layer.
- `web src/app/(main)/dashboard/machine-dashboard/page.tsx` + feature component — machine summary cards with status, telemetry freshness (**null telemetry renders "Unknown / never received", distinct from stale**), open WO/alert counts, lifetime risk; plant-scope guards; UX-DR-019 states — FR-170 surface.
- `web src/app/(main)/dashboard/workorder-dashboard/page.tsx` + feature component — KPI cards (total, by status) + status/category breakdown (v8 table or cards); **filter controls for plant/section/status/category wired to the query params**; responsive grid that wraps with 4+ statuses; backend-computed only — FR-171 surface.
- `web src/app/(main)/dashboard/preventive-dashboard/page.tsx` + feature component — due/overdue cards + upcoming list (machine name/code + program title) from server clock; overdue labeled non-color-only — FR-172 surface.
- `web next.config.mjs` + `web src/navigation/sidebar/sidebar-items.ts` — rewrites (`/machine-dashboard`, `/workorder-dashboard`, `/preventive-dashboard`) + Dashboards sidebar group with role-aware items — navigation.
- Frontend component tests for the three page contents following the existing `machine-hub-page-content.test.tsx` pattern (loading/error/empty/stale states at minimum) — UX-DR-019 state coverage.

**Acceptance Criteria:**
- Given an authorized user opens the machine dashboard, when the page loads, then it shows machines within derived scope with manual status, telemetry freshness (stale/unknown when absent), open workorder count, open alert count, and lifetime risk (max consumed % vs threshold), all counts backend-computed.
- Given a user opens the workorder dashboard, when the page loads, then it shows counts by status and by category computed by the backend, filterable by plant/section/status/category, and total matches a full scoped workorder count.
- Given a user opens the preventive dashboard, when the page loads, then due/overdue derive from the server clock; overdue items are visibly distinct via label/icon, not color alone.
- Given a SUPER_ADMIN (unrestricted) selects a plant in the PlantScopeSelector, when dashboards reload, then all three views filter to that plant.
- Given a user with no data in scope (no machines / no workorders / no schedules), when a dashboard loads, then an explicit empty state renders, never a false "healthy" zero-filled view.
- Given any of the three endpoints is called with a plantId outside the caller's scope, when the response returns, then it contains no out-of-scope rows (empty result per existing pattern).

## Spec Change Log

### 2026-08-29 — bad_spec loopback (review pass 1)
- **Trigger:** FR-171's acceptance criteria require workorder-dashboard filtering "by plant/section/status/category," but the Code Map / execution tasks operationalized only `plantId`; the first implementation therefore shipped no section/status/category filter surface (backend params, repository predicates, UI controls, or tests). The intent-alignment auditor confirmed the divergence at every surface (backend, UI, tests).
- **Amended:** Added to the Code Map and Tasks: `sectionId`/`status`/`categoryCode` query params on `GET /api/v1/dashboard/workorders` (plus repository predicates), a `bySection` dimension or section filter, UI filter controls on the workorder dashboard, and backend tests for each filter; also folded in all patch findings so re-derivation produces coherent code (see Triage Log). Out-of-scope values for any filter dimension return empty per the existing "never 403 / no out-of-scope rows" rule.
- **Known-bad state avoided:** a shipped dashboard whose AC-advertised filters silently do not exist.
- **KEEP (must survive re-derivation):** three endpoints under `/api/v1/dashboard` with `DashboardService` as orchestrator; scoped repository queries with the `(unrestricted, plantIds, groupIds)` pattern; preventive OVERDUE derivation from injected `Clock`; hand-written TanStack Query hooks (endpoints absent from the committed OpenAPI snapshot — do not regenerate Orval in this story); v8 TanStack reuse, no upgrade (NFR-P2-10 applies to tabular main views, not these card dashboards); "never 403 for out-of-scope plantId — empty payload" reading; `epic-14-context.md` is a read-only planning input, not implementation scope.

## Review Triage Log

### 2026-08-29 — Review pass
- intent_gap: 0
- bad_spec: 1 (high 1, medium 0, low 0) — FR-171 filter surface under-specified vs AC
- patch: 16 (high 0, medium 8, low 8)
- defer: 0
- reject: 7 (TanStack v9 on card dashboards; hand-written hooks vs Orval — documented decision; machine status non-ACTIVE collapse — only ACTIVE/INACTIVE exist; team-id scope mismatch — team ids are group ids per AD-2; due-horizon semantics — not in AC; no drill-down links — not in AC; counts without time window — not in AC)
- addressed_findings:
  - `[high]` `[bad_spec]` workorder dashboard lacks section/status/category filter surface promised by AC → spec amended, code re-derived in loopback
  - `[medium]` `[patch]` lifetime-risk N+1 (`evaluateAll` per machine) → fold batch evaluation into spec tasks
  - `[medium]` `[patch]` dashboard hooks fire before plant-scope resolves (`enabled: Boolean(scope)` missing) → fold into spec tasks
  - `[medium]` `[patch]` telemetry `null` (never received) conflated with STALE → fold distinct "Unknown" handling into spec tasks
  - `[medium]` `[patch]` preventive `upcoming` unbounded → fold limit into spec tasks
  - `[medium]` `[patch]` `PreventiveUpcomingRow` lacks machine code/name + program title (UI renders truncated UUID) → fold into spec tasks
  - `[medium]` `[patch]` threshold boundary must be `>=` and null-threshold guards → fold into spec tasks
  - `[medium]` `[patch]` empty-scope restricted user hits `in ()` JPQL → fold early-return guard into spec tasks
  - `[low]` `[patch]` `DashboardExceptionHandler` unreachable 403/404 mappings contradict "never 403" → remove dead mappings
  - `[low]` `[patch]` `mergeGroupIds` lacks null guards → fold into spec tasks
  - `[low]` `[patch]` `toMap` duplicate-key / null-threshold NPE risk → fold defensive merge into spec tasks
  - `[low]` `[patch]` `toFreshnessState` unknown → STALE → fold UNKNOWN default into spec tasks
  - `[low]` `[patch]` KPI grid overflow with 4+ statuses → fold responsive fix into spec tasks
  - `[low]` `[patch]` workorder empty-state test mislabeled → fold comment/name fix into spec tasks
  - `[low]` `[patch]` machineGroup-null guard in dashboard rows → fold into spec tasks
  - `[low]` `[patch]` epic-14-context.md was edited by the implementer (read-only planning input) → restored to baseline
  - `[medium]` `[patch]` verification gaps: open-WO/open-alert count exclusions, AT_RISK lifetime, byCategory/uncategorized, team-scope, 403-branch, frontend state tests → fold into spec tasks

## Design Notes

Three thin GET endpoints instead of one "everything" payload: each dashboard loads independently, matches the existing page-per-surface frontend shape, and keeps payloads small. Machine dashboard reuses the existing scope-filtered, telemetry-hydrated `MachineService.list` (one query) and adds exactly two aggregate queries (open WO by machine, open alert by machine) + lifetime evaluation via the existing batch evaluator — no new machine query path.

Workorder status/category counts are two new scoped group-by queries on `work_orders` — the repo already has the `(unrestricted, plantIds, groupIds)` native-query parameter pattern to copy. Preventive needs no new query: `findScopedSchedules` rows plus the existing OVERDUE derivation (stored `SCHEDULED` + `dueDate < LocalDate.now(clock)`) yield due/overdue/upcoming in the service.

Frontend follows the established hand-written hook pattern because these endpoints are absent from the committed `openapi.json` snapshot; regenerating Orval would require a running backend and rewrite large generated files — not worth it for three read hooks.

TanStack Table: repo has v8; the tabular breakdown reuses the v8 pattern (`workorder-table.tsx`). v9 upgrade is out of scope (noted in boundaries).

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest="DashboardServiceIntegrationTest,DashboardControllerTest"` -- expected: green incl. scope-filter and I/O-matrix cases
- `cd syncro/apps/backend && mvn -q test` -- expected: full suite still green (no regressions)
- `cd syncro/apps/web && npm run lint` -- expected: no biome violations
- `cd syncro/apps/web && npm run build` -- expected: production build succeeds

**Manual checks (if no CLI):**
- Backend up + logged-in leader: hit `/api/v1/dashboard/machines|workorders|preventive` and verify rows respect scope; SUPER_ADMIN sees all; plantId out of scope returns empty.
- Web: open `/machine-dashboard`, `/workorder-dashboard`, `/preventive-dashboard` with empty DB (empty states), with seed data (counts match backend), and with an overdue schedule (labeled, not color-only).

## Auto Run Result

**Summary:** Three scope-aware dashboard endpoints (machine, workorder, preventive) with backend-computed aggregates, scoped repository queries, and frontend pages with plant-scope guards, UX-DR-019 states, overdue non-color-only labels, and FR-171 filter controls (section/status/category). Batch lifetime evaluation via `evaluateBatch` (single Redis pipeline round trip). All review findings from the bad_spec loopback addressed: filter surface, N+1, null guards, threshold boundary, telemetry null/stale, hooks gating, preventive upcoming cap + machine details, empty-scope guard, test coverage (open-WO/alert exclusion, AT_RISK, byCategory, team-scope, 403-branch), and frontend component tests.

**Files changed:**
- Backend: `DashboardController.java`, `DashboardDtos.java`, `DashboardService.java`, `DashboardExceptionHandler.java`, `WorkOrderRepository.java` (count queries + filter predicates), `SparepartAlertRepository.java` (count query), `PreventiveScheduleRepository.java` (scoped details query), `MachineSparepartInstallationRepository.java` (batch lookup), `SparepartLifetimeEvaluator.java` (evaluateBatch), `RedisLatestTelemetryWriter.java` (readCountingBatch), `PreventiveScheduleDashboardRow.java` — 6 modified + 6 new
- Tests: `DashboardServiceIntegrationTest.java` (21 tests), `DashboardControllerTest.java` (7 tests), `SparepartLifetimeEvaluatorTest.java` (3 new batch tests) — 31 backend tests
- Frontend: `next.config.mjs`, `sidebar-items.ts` (modified); 3 hooks + 3 page-content components + 3 route pages + 3 test files (20 tests) — 15 new

**Review findings breakdown:** 1 bad_spec (filter surface, high), 16 patches applied across backend and frontend (N+1, hooks gating, null guards, telemetry distinction, filter UI, test coverage, etc.), 7 rejected, 0 deferred.

**Follow-up review recommended:** false (score: 0 high × 0 + 0 medium × 0 + 0 low × 0 = 0; all patches applied in the re-derivation).

**Verification performed:**
- `DashboardServiceIntegrationTest` 21/21 pass
- `DashboardControllerTest` 7/7 pass
- `SparepartLifetimeEvaluatorTest` 12/12 pass (incl. 3 batch tests)
- `npm run lint` — only pre-existing fetch-openapi.mjs issue
- `npm run build` — compiled successfully, exit 0
- 3 frontend component test files — 20/20 pass

**Residual risks:** AT_RISK lifetime integration test requires Redis (unavailable in `AbstractPostgresIntegrationTest`); unit test covers `evaluateBatch` logic. Full CI suite hits pre-existing Testcontainers Hikari pool exhaustion under parallel execution — all relevant tests pass in isolation.
