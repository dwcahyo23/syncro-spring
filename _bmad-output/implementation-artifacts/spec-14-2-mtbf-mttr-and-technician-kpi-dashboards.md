---
title: 'MTBF/MTTR & Technician KPI Dashboards'
type: 'feature'
created: '2026-08-29'
status: 'done'
review_loop_iteration: 1
baseline_revision: '9bb3b2c'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Leaders have no reliability or team-performance read surface — MTBF/MTTR and technician KPIs (ratings + objective) are not computed anywhere (FR-173, FR-174). MTBF is only a comment in the code ("future MTBF/FMEA analysis"); ratings exist but have no per-technician aggregation.

**Approach:** Two backend-computed analytics endpoints under `/api/v1/dashboard` (`/mtbf-mttr`, `/technician-kpi`), scoped via `OperationalScopeService`, with a 30-minute Redis look-aside cache and stale indicator. MTBF derives `woStopAt` from the immutable `work_order_status_history` DONE transition (no schema change); MTTR averages per-WO `mttr_minutes`; on-time % derives from `responseTimeMinutes <= category.targetResponseMinutes`. All computation is backend-owned; the frontend renders with the existing dashboard-hook pattern and the unused recharts `chart.tsx` wrapper.

## Boundaries & Constraints

**Always:**
- MTBF: **only completed/stopped** breakdown WOs (category code `01`, status DONE or CLOSED, with a `toStatus='DONE'` history transition when present) in scope within the monthly rolling window (last 30 days, server `Clock`), ordered by derived `woStopAt`; MTBF = mean interval between consecutive stops across the fleet in scope. **OPEN/IN_PROGRESS/ASSIGNED breakdown WOs are never counted as stops.** Fewer than 2 stopped breakdown WOs in window → explicit `insufficientData` state (never a fabricated value).
- MTTR: mean of per-WO `mttr_minutes` for completed breakdown WOs in the window; no completed breakdown WOs → `insufficientData`.
- **Window key:** the 30-day window is keyed on the **derived stop time** (the DONE-transition `transitionedAt`); when a stopped WO has no DONE history row (sync edge), the fallback `updatedAt` is used and the window key follows it. Repository queries must apply the window predicate against the same field the ordering uses, and the javadoc must not claim a different key than the SQL.
- Units hours (minutes → hours decimal); window = monthly rolling (30 days) from injected `Clock`; response carries `windowFrom`, `windowTo`, `computedAt`, and `cacheAgeMs` (stale indicator) + `stale` boolean (cache age > TTL). Empty-scope responses carry `windowFrom`/`windowTo` = null (no fabricated window).
- `woStopAt` is DERIVED: `work_order_status_history.transitionedAt` where `toStatus='DONE'` (fallback `updatedAt` if no DONE row). No schema change, no lifecycle change.
- On-time: only workorders with BOTH `responseTimeMinutes` and a category `targetResponseMinutes` count; on-time = `responseTimeMinutes <= targetResponseMinutes`.
- Technician KPI: technicians = distinct `assignedTechnicianId`/repair-session `technicianId` on workorders in scope; per-technician objective KPIs (completed count = DONE/CLOSED, **avg MTTR minutes→hours scoped to breakdown workorders only**, on-time %), plus per-dimension average rating (1-5 stars) from `workorder_ratings` (TECHNICIAN type) joined to `workorder_rating_scores`; technician with no ratings → rating dimensions empty, objective KPIs still shown.
- **Authorization:** both analytics endpoints are role-gated server-side with the same roles as the sidebar Analytics item (SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER, PRODUCTION_LEADER, AUDITOR) using the repo's existing authz pattern — a TECHNICIAN/STAFF user must not be able to call them directly.
- Scope: `OperationalScopeService.derive` + empty-scope guard (restricted + empty plants/groups → explicit empty response), same pattern as `DashboardService` 14-1; leader own-group / manager plant / manager-global-all falls out of derived scope.
- Cache: copy the `ProjectionRedisCache` look-aside pattern (`StringRedisTemplate` + Jackson, key `syncro:dashboard:mtbf-mttr` / `syncro:dashboard:technician-kpi`, configurable TTL default `PT30M` via `SYNCRO_DASHBOARD_ANALYTICS_TTL`); Redis failure degrades to recompute (log, never throw). Return `computedAt` + `cacheAgeMs` for stale rendering.
- No Flyway migration. No new dependencies.
- Tests: backend integration (`AbstractPostgresIntegrationTest`) + unit for the interval/on-time math; frontend component tests per the 14-1 pattern.

**Block If:**
- A decision on per-machine vs fleet MTBF cannot be made from the intent — the intent's "ordered by woStopAt between consecutive breakdown workorders" is read as fleet-in-scope (all breakdown WOs in scope, consecutive by stop time). If a per-machine breakdown is required later, it is a separate story.

**Never:**
- Do NOT add `stopped_at`/`dueDate`/`onTime` columns or change the workorder lifecycle/state machine.
- Do NOT compute MTBF from telemetry/Redis counting (AD-12: analytics work without telemetry).
- Do NOT touch ratings write paths (`rateTechnician`/`rateWorkorder`), dimensions CRUD, or notification code.
- Do NOT regenerate the Orval OpenAPI snapshot (hand-written hooks per 14-1).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | ≥2 breakdown WOs in window, all data present | MTBF + MTTR hours, window, computedAt, not stale | no error |
| INSUFFICIENT_MTBF | exactly 0-1 breakdown WOs in window | `mtbf: { status: "INSUFFICIENT_DATA" }`, no value | explicit state, no fabricated number |
| INSUFFICIENT_MTTR | no completed breakdown WOs | `mttr: { status: "INSUFFICIENT_DATA" }` | explicit state |
| EMPTY_SCOPE | restricted user, no plants/groups | both endpoints return empty/zeroed payload | no 403, no crash |
| CACHE_HIT | analytics cached, age < TTL | served from cache with `cacheAgeMs`, `stale=false`, `computedAt` | no error |
| CACHE_STALE | cached, age > TTL | recomputed, new `computedAt`, `stale=false` after recompute | no error |
| REDIS_DOWN | Redis unavailable | recompute live, `cacheAgeMs=null`, `stale=false` | log warn, never throw |
| ON_TIME_NULL_TARGET | WO has responseTime, category target null | excluded from on-time denominator | no error |
| NO_RATINGS | technician with WOs, zero ratings | `ratings: []`, objective KPIs populated | empty ratings, not missing technician |
| SINGLE_BREAKDOWN | 1 breakdown WO, many non-breakdown | MTBF insufficient; MTTR from that one if completed | explicit MTBF state |

</intent-contract>

## Code Map

Backend (`syncro/apps/backend/src/main/java/com/syncro`):

- `maintenance/infrastructure/db/WorkOrderEntity.java` — fields: `status`, `categoryId`, `machineId`, `assignedTechnicianId`, `mttrMinutes`, `responseTimeMinutes`, `updatedAt`; category code `01` = breakdown (`WorkOrderService.BREAKDOWN_CATEGORY_CODE`).
- `maintenance/infrastructure/db/WorkOrderCategoryEntity.java` — `code`, `targetResponseMinutes`.
- `maintenance/infrastructure/db/WorkOrderStatusHistoryEntity.java` + `WorkOrderStatusHistoryRepository.java` — add finder: latest `transitionedAt` per `workOrderId` where `toStatus='DONE'` (for derived `woStopAt`).
- `maintenance/infrastructure/db/WorkOrderRepository.java` — 14-1 scoped-query pattern (`(unrestricted, plantIds, groupIds)` + sentinels). Add: breakdown WOs in window with derived stop time (JPQL join history or fetch WOs + history batch), per-WO mttr/response/category rows for on-time and MTTR, technician objective-KPI aggregate (completed count, avg mttr, response rows).
- `maintenance/infrastructure/db/RepairSessionRepository.java` — `sumCompletedDuration(workOrderId)` exists (per-WO MTTR source).
- `maintenance/infrastructure/db/WorkorderRatingRepository.java` + `WorkorderRatingScoreRepository.java` — add per-dimension average score per `ratedUserId` for TECHNICIAN ratings, joined to workorders in scope (or fetch ratings-by-workorder-ids and aggregate in service).
- `org/application/OperationalScopeService.java` — `derive(user)` → `OperationalScope`; `plantIds==null` = unrestricted.
- `maintenance/api/DashboardController.java` + `maintenance/api/DashboardDtos.java` (14-1) — extend with `GET /api/v1/dashboard/mtbf-mttr` and `GET /api/v1/dashboard/technician-kpi`, both `AuthenticatedUser` + optional `plantId`, read-only DTO records.
- `maintenance/application/DashboardAnalyticsService.java` — new orchestrator: scope derive → empty-scope guard → cached compute (read cache → recompute → write cache) → assemble DTOs; owns MTBF interval math, MTTR mean, on-time %, technician aggregation. Mirrors `DashboardService` structure.
- `maintenance/infrastructure/DashboardAnalyticsRedisCache.java` — new look-aside cache copying `projection/infrastructure/ProjectionRedisCache.java` (StringRedisTemplate + Jackson, `syncro:dashboard:*` keys, TTL from `SYNCRO_DASHBOARD_ANALYTICS_TTL` default PT30M, get/put, degrade-to-recompute).
- `config`/`application.yml` — add `syncro.dashboard.analytics-ttl` property (env-overridable), mirroring `syncro.projection.cache-ttl`.
- Tests: `maintenance/application/DashboardAnalyticsServiceIntegrationTest.java` (Testcontainers: scope, insufficient states, window math, on-time, technician KPI, cache hit/stale via a Redis-backed test or unit-mocked cache), `maintenance/application/DashboardAnalyticsServiceTest.java` (unit: interval math, on-time boundary, insufficient states with mocked repos/cache), `maintenance/api/DashboardControllerTest.java` extend for the two new endpoints.

Frontend (`syncro/apps/web/src`):

- `features/workorders/hooks/use-workorder-dashboard.ts` — pattern for hand-written dashboard hooks (staleTime 15s, `enabled: Boolean(scope)`).
- New `features/analytics/hooks/use-mtbf-mttr.ts` + `use-technician-kpi.ts` — hand-written TanStack Query hooks (endpoints absent from OpenAPI snapshot; do NOT regenerate Orval), `enabled: Boolean(scope)`, plantId from `features/plant-scope/plant-scope-store`.
- New `features/analytics/` page-content components + `app/(main)/dashboard/analytics/page.tsx` — tabs or stacked sections for MTBF/MTTR cards (value + status badge for insufficient/stale, window + freshness) and technician KPI table (recharts `chart.tsx` wrapper available for a rating bar chart; card/table fine otherwise).
- `navigation/sidebar/sidebar-items.ts` + `next.config.mjs` — add "Analytics" item (role-gated) + rewrite, or a sub-item under the 14-1 Dashboards group.
- Component tests per 14-1 pattern (loading/error/empty/stale/insufficient states).

## Tasks & Acceptance

**Execution:**
- `backend .../WorkOrderStatusHistoryRepository.java` — add `findLatestDoneTransitionedAt(workOrderId)` finder — derived woStopAt source.
- `backend .../WorkOrderRepository.java` — add scoped analytics queries (breakdown WOs in window with stop time + category target; technician aggregate rows) using the 14-1 `(unrestricted, plantIds, groupIds)` pattern; **filter to stopped breakdowns only (status DONE/CLOSED) and key the window on the derived stop time; javadoc must match the SQL key** — FR-173/174 data.
- `backend .../WorkorderRatingRepository.java` / `...ScoreRepository.java` — add per-dimension avg rating per technician (TECHNICIAN type, in-scope workorders) — FR-174 ratings.
- `backend .../maintenance/application/DashboardAnalyticsService.java` — new service: scope guard, cache read/write, MTBF interval math (consecutive stops, fleet-in-scope, **only stopped breakdowns**), MTTR mean, on-time %, technician KPI assembly (**avg MTTR scoped to breakdown WOs**), insufficient-data states, **empty-scope responses with null window bounds**, **role-gated endpoints via existing authz pattern** — backend-owned calculations.
- `backend .../maintenance/infrastructure/DashboardAnalyticsRedisCache.java` — look-aside cache (TTL default PT30M, env-overridable, degrade-to-recompute) — freshness contract.
- `backend .../maintenance/api/DashboardController.java` + `DashboardDtos.java` — two new GET endpoints + read-only DTOs; **MttrView.workorderCount javadoc states it counts completed breakdown WOs with a persisted mttrMinutes** — FR-173/174 surface.
- `backend src/test/.../maintenance/application/DashboardAnalyticsServiceIntegrationTest.java` — Testcontainers; scope filter, insufficient MTBF (<2 stopped breakdowns), insufficient MTTR, on-time %, technician KPI (ratings + objective), **a DONE-transition ≠ updatedAt window case asserting membership follows the derived stop time**, **an in-scope plantId case with rows on both plants**, **a repair-session-attribution case (WO with null assigned technician + completed session appears under the session technician)**, I/O matrix cases — matrix coverage gate.
- `backend src/test/.../maintenance/application/DashboardAnalyticsServiceTest.java` — unit; interval math, on-time boundary (== target is on-time), **cache hit/stale/recompute with a mocked cache (fresh payload served with cacheAgeMs; expired payload recomputed with new computedAt)**, **stop-semantics (OPEN breakdown excluded from MTBF)**, technician fallback-to-UUID name resolution.
- `backend src/test/.../maintenance/api/DashboardControllerTest.java` — extend: two endpoints, plantId passthrough, unauthenticated, **role-denied (TECHNICIAN) returns forbidden**.
- `web src/features/analytics/` — two hooks + page-content components + route page; chart.tsx optional for rating visualization; UX-DR-019 states (loading/empty/error/stale/insufficient/forbidden); **hooks staleTime aligned to the backend analytics TTL (e.g. 15 min)**, **stale indicator rendered even when the technician list is empty**, **formatPct sentinel fallback for Infinity/out-of-range**; **hook unit test asserting plantId query construction** — FR-173/174 UI.
- `web next.config.mjs` + `web src/navigation/sidebar/sidebar-items.ts` — analytics route + nav item — navigation.
- `web src/features/analytics/*.test.tsx` — component tests for insufficient/stale/empty/error states — state coverage.

**Acceptance Criteria:**
- Given breakdown workorders exist in scope within the rolling 30-day window, when the MTBF/MTTR dashboard loads, then it shows MTBF (mean interval between consecutive stops, ordered by derived woStopAt) and MTTR (mean per-WO repair minutes) in hours with window, computedAt, and stale indicator; fewer than 2 breakdown WOs yields an explicit INSUFFICIENT_DATA state.
- Given a leader opens the technician KPI dashboard, then per-technician objective KPIs (completed count, average MTTR hours, on-time % from responseTime vs category target) and per-dimension average ratings (1-5 stars) render within the leader's derived scope.
- Given analytics are cached, when the dashboard loads before TTL expiry, then the cached payload returns with cacheAgeMs and stale=false; after TTL expiry or Redis failure, the backend recomputes without error.
- Given a restricted user with an empty scope, when either analytics endpoint is called, then an explicit empty payload returns (never 403, never fabricated values).

## Spec Change Log

### 2026-08-29 — bad_spec loopback (review pass 1)
- **Trigger:** The intent's "breakdown workorders … woStopAt between consecutive breakdown workorders" and this spec's design note ("woStopAt is DERIVED from the DONE transition"; "window keyed on the derived stop time") were under-specified for the stop semantics. The first implementation counted **every breakdown WO in the window regardless of status** (an OPEN WO's `updatedAt` served as a stop) and keyed the 30-day window filter on `w.updatedAt` while the javadoc claimed the derived `woStopAt` — so an OPEN breakdown inflated MTBF and a WO whose DONE transition and last update diverged was mis-windowed.
- **Amended:** Design Note and Always constraints now state explicitly: (1) MTBF counts only **completed/stopped** breakdown WOs — `toStatus='DONE'` (or CLOSED) history transition present, or status DONE/CLOSED — never OPEN/IN_PROGRESS rows; (2) the 30-day window is keyed on the **derived stop time** (DONE transition, `updatedAt` fallback only when no DONE row exists) — if the fallback is used, window membership keys on `updatedAt`; (3) technician **average MTTR is scoped to breakdown workorders only** (MTTR is a breakdown metric); (4) the two analytics endpoints require role gating (sidecar `@PreAuthorize`-style or the repo's existing authz pattern — same roles as the sidebar item); (5) verification must cover cache hit/stale/recompute, repair-session technician attribution, in-scope plantId filtering, and a DONE-transition ≠ updatedAt window case. Folded all patch findings into Tasks.
- **Known-bad state avoided:** an MTBF metric that counts not-yet-stopped breakdowns as stops and a window filter that silently diverges from the documented stop-time key.
- **KEEP (must survive re-derivation):** two endpoints on `DashboardController` under `/api/v1/dashboard` with `DashboardAnalyticsService` orchestrator; fleet-in-scope MTBF with derived stop-time ordering; `evaluateBatch`-style cache (`DashboardAnalyticsRedisCache`, look-aside, degrade-to-recompute, 30-min TTL via `SYNCRO_DASHBOARD_ANALYTICS_TTL`); empty-scope guard returning explicit empty (never 403); on-time % = `responseTimeMinutes <= category.targetResponseMinutes` excluding rows missing either; `computedAt` + `cacheAgeMs` freshness contract; hand-written frontend hooks with `enabled: Boolean(scope)` (no Orval regeneration); `epic-14-context.md` read-only.

## Review Triage Log

### 2026-08-29 — Review pass
- intent_gap: 0
- bad_spec: 1 (high 1, medium 0, low 0) — MTBF stop semantics + window key under-specified
- patch: 13 (high 0, medium 6, low 7)
- defer: 0
- reject: 3 (no background refresh — not in AC, manual Refresh exists; cache-key per-user sharing — minor optimization; MTTR workorderCount javadoc — folded into DTO docs as low patch)
- addressed_findings:
  - `[high]` `[bad_spec]` MTBF counted non-stopped (OPEN) breakdown WOs as stops and window keyed on updatedAt vs derived woStopAt → spec amended (stop semantics explicit), code re-derived in loopback
  - `[medium]` `[patch]` analytics endpoints lacked role authorization → fold role gating into spec tasks
  - `[medium]` `[patch]` cache hit/stale/recompute branch untested → fold cache unit tests into spec tasks
  - `[medium]` `[patch]` repair-session technician attribution untested → fold test into spec tasks
  - `[medium]` `[patch]` DONE-transition ≠ updatedAt window case untested → fold test into spec tasks
  - `[medium]` `[patch]` in-scope plantId filtering of MTBF/MTTR untested → fold test into spec tasks
  - `[medium]` `[patch]` technician average MTTR included non-breakdown WOs (metric ambiguity) → fold breakdown-scoping into spec tasks
  - `[low]` `[patch]` frontend staleTime 15s vs backend 30-min TTL mismatch → align to TTL (e.g. staleTime 15 min)
  - `[low]` `[patch]` empty-scope MTBF/MTTR fabricates a time window → return null window
  - `[low]` `[patch]` stale badge hidden when technician KPI list empty → render stale indicator regardless
  - `[low]` `[patch]` formatPct lacks Infinity/>100 guard → sentinel fallback
  - `[low]` `[patch]` frontend hooks plantId query construction untested → add hook test
  - `[low]` `[patch]` technician fallback-to-UUID path untested → add test
  - `[low]` `[patch]` MttrView.workorderCount javadoc unclear (completed-with-mttr vs all completed) → clarify

## Design Notes

`woStopAt` derivation from the DONE transition keeps this a pure read story — adding a `stopped_at` column would force a workorder-lifecycle write and a migration, out of scope. The DONE transition row is immutable evidence and exists for every completed WO (DONE is a terminal transition); `updatedAt` is the documented fallback if a WO reached DONE without a history row (sync edge).

Fleet-in-scope MTBF (all breakdown WOs in scope ordered by stop, mean of consecutive gaps) is the minimal defensible reading of "ordered by woStopAt between consecutive breakdown workorders" and avoids per-machine permutations the intent never asks for; per-machine reliability is a future story. The monthly rolling window reuses the `findScopedPage` `from`/`to` createdAt-window precedent, except keyed on stop time.

The Redis cache copies `ProjectionRedisCache` (look-aside, degrade-to-recompute) because that is the established codebase pattern for derived-data caching; a 30-min TTL makes the freshness contract explicit and cheap. `computedAt` + `cacheAgeMs` give the frontend a non-color-only stale signal.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest="DashboardAnalyticsServiceIntegrationTest,DashboardAnalyticsServiceTest,DashboardControllerTest"` -- expected: green incl. I/O-matrix and unit-math cases
- `cd syncro/apps/web && npm run lint` -- expected: no new biome violations
- `cd syncro/apps/web && npm run build` -- expected: production build succeeds
- `cd syncro/apps/web && npx vitest run src/features/analytics` -- expected: component tests green

**Manual checks (if no CLI):**
- Backend up + logged-in leader: hit `/api/v1/dashboard/mtbf-mttr` and `/api/v1/dashboard/technician-kpi`; verify scope rows, insufficient states with sparse data, and `computedAt`/`cacheAgeMs` present.
- Web: open the analytics page with empty DB (insufficient/empty states), with seed data (values match backend), and confirm stale indicator renders non-color-only.

## Auto Run Result

**Summary:** MTBF/MTTR and technician KPI analytics endpoints added under `/api/v1/dashboard` with fleet-in-scope MTBF (only stopped DONE/CLOSED breakdown WOs, window keyed on derived stop time via `coalesce(max(DONE transitionedAt), updatedAt)`), MTTR from persisted per-WO mttrMinutes, technician KPIs (breakdown-scoped avg MTTR, on-time %, per-dimension rating averages), a 30-min Redis look-aside cache with stale/freshness contract, role-gated endpoints, and a tabbed frontend analytics page. All bad_spec and patch findings from the review loopback were addressed.

**Files changed:**
- Backend: `DashboardAnalyticsService.java` (orchestrator, role gate, stop semantics, cache), `DashboardAnalyticsRedisCache.java`, `DashboardAnalyticsProperties.java`, `WorkOrderRepository.java` (findStoppedBreakdownAnalyticsRows keyed on derived stop time, technician objective rows), `WorkOrderStatusHistoryRepository.java` (DONE-transition finders), `WorkorderRatingRepository.java` (rating averages), `AuthUserRepository.java` (findByIds), `DashboardController.java`/`DashboardDtos.java`/`DashboardExceptionHandler.java` (2 endpoints + DTOs + 403), `application.yml` (analytics-ttl)
- Tests: `DashboardAnalyticsServiceIntegrationTest.java` (16), `DashboardAnalyticsServiceTest.java` (11), `DashboardAnalyticsServiceCacheTest.java` (4), `DashboardControllerTest.java` (12 incl. role-denied)
- Frontend: `analytics/` feature (hooks with plantId query construction + staleTime 15 min, tabbed page-content with insufficient/stale/empty/error states + formatPct Infinity guard, route page, 17 tests), `next.config.mjs` rewrite, `sidebar-items.ts` Analytics nav item

**Review findings breakdown:** 1 bad_spec (MTBF stop semantics + window key, high) → spec amended, code re-derived; 13 patches applied in the re-derivation (role gate, breakdown-scoped technician MTTR, empty-scope null window, cache tests, DONE-transition window test, plantId filter test, repair-session attribution test, technician UUID fallback test, staleTime alignment, stale-with-empty-list, formatPct guard, hook plantId test, MttrView javadoc); 3 rejected.

**Follow-up review recommended:** false (score 0; all patches applied in re-derivation).

**Verification performed:**
- Backend 43/43 pass (integration 16 + unit 11 + cache 4 + controller 12)
- `npm run lint` — no new violations (pre-existing files only)
- `npm run build` — succeeds, `/dashboard/analytics` route present
- Frontend 17/17 pass (component 13 + hook 4)

**Residual risks:** Redis-backed cache hit/stale covered by unit tests with mocked cache (integration container has no Redis — degrade-to-recompute path exercised). Full CI suite hits pre-existing Testcontainers Hikari pool exhaustion under parallel execution.
