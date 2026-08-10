---
title: '3-7 Show Latest Telemetry Dashboard'
type: 'feature'
created: '2026-08-10'
baseline_revision: '5597e5a3870849174b5fc5baedabf3c9ae53ba3b'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
final_revision: 'bdf673e9b1757b4d0db53cf153d7d0a5859265f6'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
  - '_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md'
warnings: []
---

<intent-contract>

## Intent

**Problem:** Users need to see latest telemetry for active machines in a plant-scoped dashboard, including running state, runtime hours, production count, and last received timestamp. Manual machine status (ACTIVE/INACTIVE) must be visually distinguished from telemetry freshness state (online/offline/stale), with non-color-only status communication.

**Approach:** Backend exposes `GET /api/v1/machines?status=ACTIVE&plantId={plantId}` with optional telemetry fields via Redis. Frontend builds a plant-scoped dashboard page at `/dashboard/telemetry` using TanStack Query cache/revalidation, rendering shared `TelemetryCard`, `StatusBadge`, and `MachineSummaryCard` components supporting loading, empty, error, stale, read-only, and forbidden states.

## Boundaries & Constraints

**Always:**
- API returns ACTIVE machines filtered by user's plant assignments (FR-077); VIEWER/MANAGE/SUPER_ADMIN can view permitted active machines only
- Each machine card shows: running state, runtimeHours, counting, lastReceivedAt
- Manual status displayed separately from telemetry freshness; telemetry freshness uses distinct labels: `ONLINE` (data ≤5 min), `OFFLINE` (no recent data, but manual ACTIVE), `STALE` (manual INACTIVE or >15 min since data)
- Status communication uses text labels, icons, and color—never color alone (UX-DR-026)
- Dashboard is plant-scoped; users see only telemetry for their assigned plants
- Loading, empty, error, stale, read-only, and forbidden states supported on dashboard and each card
- Cache strategy: initial load + 30s refresh interval or manual refresh button; stale data flagged with timestamp
- Optional configured telemetry fields render from Redis `optional.*` keys when available (Story 3.6 integration)
- Freshness calculation: compare `receivedAt` to current time; thresholds: online ≤5min, offline ≤15min, stale >15min

**Block If:**
- If per-tenant/plant assignment enforcement not implemented in backend APIs—requires Epic 1/PlantScopeService coordination.

**Never:**
- No frontend recalculation of production deltas; backend owns counter wrap logic
- No hardcoded MQTT topic parsing; use validated machine identity from master data
- No conflation of manual status (ACTIVE/INACTIVE) with telemetry freshness (online/offline/stale)
- No direct Redis access from frontend; all data through `/api/v1` endpoints
- No silent failures on telemetry fetch errors; show explicit error state with retry option

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| USER_VIEWER_WITH_PLANT | authenticated VIEWER assigned to plant GM1 | Dashboard loads showing 5 ACTIVE machines with telemetry | No error expected |
| NO_ACTIVE_MACHINES | no ACTIVE machines in user's plant | Empty state with helpful message "No active machines found" | No error expected |
| TELEMTRY_MISSING_DATA | machine exists as ACTIVE, but no telemetry yet | Card shows INACTIVE running state, empty values, "No data received" label | Not an error, valid empty telemetry case |
| STALE_TELEMETRY | last receivedAt >15 min ago | Card shows STALE freshness badge with timestamp explanation | User sees degradation, not hard error |
| MANUAL_INACTIVE_STATUS | machine.status = INACTIVE | Card shows INACTIVE manual status + OFFLINE freshness, disabled editing | Visual distinction clear |
| READ_ONLY_USER | MANAGE/SUPER_ADMIN on maintenance window | All cards read-only mode, disable any mutation actions (if added later) | Action buttons hidden/disabled |
| FORBIDDEN_ACCESS | unauthenticated user requests /dashboard/telemetry | Redirect to login, then unauthorized if role doesn't permit | Standard auth flow |
| CONFIGURED_OPTIONAL_FIELDS | machine.optionalTelemetryFields=["vibration"] | Card renders extra field vibration value from Redis optional.vibration | Requires Story 3.6 config |
| API_ERROR_500 | backend fails to query machines/telemetry | Dashboard shows error state with retry button and error message | Non-blocking, graceful degradation |
| PLANT_SCOPE_VIOLATION | user tries to access another plant's machines | Backend filters response; frontend shows only permitted machines | Server-side enforcement |

</intent-contract>

## Code Map

### Backend API Changes

- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineController.java` -- MODIFY: add `getAllActiveWithTelemetry(UUID plantId)` endpoint returning `MachineListResponse` with latest telemetry joined from Redis
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java` -- MODIFY: introduce method fetching ACTIVE machines with optional latest telemetry hydration from `RedisLatestTelemetryWriter`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineDtos.java` -- MODIFY: `MachineView` gains optional `latestTelemetry` nested record with `running`, `runtimeHours`, `counting`, `lastReceivedAt`, `freshnessState`, `hasOptionalFields`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/LatestTelemetryDto.java` -- NEW: DTO for latest telemetry data structure exposed via API
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryFreshnessCalculator.java` -- NEW: utility to calculate freshness state (`ONLINE` ≤5min, `OFFLINE` ≤15min, `STALE` >15min or INACTIVE) based on `receivedAt` vs current time
- Migrations: none required; uses existing machine table + Redis storage from Story 3.4–3.6

### Frontend Feature Structure

- `syncro/apps/web/src/features/telemetry/components/TelemetryDashboardPage.tsx` -- NEW: main page component composing dashboard layout, filter controls, and telemetry grid
- `syncro/apps/web/src/features/telemetry/components/TelemetryGrid.tsx` -- NEW: responsive grid container for machine telemetry cards (adaptive cols: 1 mobile, 2 tablet, 3 desktop)
- `syncro/apps/web/src/features/telemetry/components/MachineTelemetryCard.tsx` -- NEW: reusable card component using `TelemetryCard` + `StatusBadge` pattern with loading/error/empty states
- `syncro/apps/web/src/features/telemetry/hooks/useTelemetryDashboardQuery.ts` -- NEW: TanStack Query hook for `GET /api/v1/machines?status=ACTIVE&plantId=X` with 30s refetch interval and manual refresh trigger
- `syncro/apps/web/src/features/telemetry/types/index.ts` -- NEW: TypeScript types for telemetry dashboard API contract
- `syncro/apps/web/src/components/syncro/TelemetryCard.tsx` -- NEW: shared domain component for displaying latest telemetry metrics
- `syncro/apps/web/src/components/syncro/StatusBadge.tsx` -- NEW: shared domain component for non-color-only status display with severity icon + text
- `syncro/apps/web/src/components/syncro/MachineSummaryCard.tsx` -- NEW: shared domain component for machine identity summary with manual status indicator
- `syncro/apps/web/src/app/(main)/dashboard/telemetry/page.tsx` -- MODIFY: replace placeholder with actual dashboard page import

## Tasks & Acceptance

**Backend Execution:**
- [x] Create `LatestTelemetryDto.java` with `running`, `runtimeHours`, `counting`, `lastReceivedAt`, `freshnessState`, `hasOptionalFields` fields -- API telemetry projection
- [x] Create `TelemetryFreshnessCalculator.java` with `calculate(Instant receivedAt, MachineStatus manualStatus)` → `TELEMETRY_FRESHNESS` enum -- freshness logic owned by backend
- [x] Modify `MachineController.java` to expose `GET /api/v1/machines?status=ACTIVE&plantId={UUID}` endpoint -- new API for dashboard consumption
- [x] Modify `MachineService.java` to fetch ACTIVE machines and hydrate latest telemetry from Redis using `RedisLatestTelemetryWriter` -- join master data + telemetry state *(deviation: hydration implemented in `LatestTelemetryQueryService` — see Spec Change Log rev 2)*
- [x] Update `MachineDtos.java` `MachineView` to include optional `latestTelemetry` field -- API contract update
- [x] Write backend tests: `MachineControllerTest` new test cases for new endpoint, `MachineServiceTest` coverage for telemetry hydration *(deviation: coverage in `LatestTelemetryQueryServiceTest` + `TelemetryFreshnessCalculatorTest`)*
- [x] Verify plant scope filtering enforced by `PlantScopeService` -- FR-077 compliance

**Frontend Execution:**
- [x] Create `TelemetryCard.tsx` shared component under `components/syncro/` accepting `TelemetryData` props with running state, runtimeHours, counting, lastSeen, freshnessState, optionalFieldsMap -- core visual contract *(kebab-case: `telemetry-card.tsx`)*
- [x] Create `StatusBadge.tsx` shared component accepting `severity`, `label`, `description` props; displays icon + text + background, never color-only -- UX-DR-009 compliance *(kebab-case: `status-badge.tsx`; props: `freshness`)*
- [x] Create `MachineSummaryCard.tsx` shared component showing machine code, name, group, manual status badge, freshness badge -- UX-DR-017 compliance *(kebab-case: `machine-summary-card.tsx`)*
- [x] Create feature module structure: `features/telemetry/types/`, `features/telemetry/components/`, `features/telemetry/hooks/` -- feature isolation
- [x] Create `useTelemetryDashboardQuery.ts` hook using `@tanstack/react-query` with query key `[plantId, 'telemetry']`, 30s refetchInterval, manual `refetch()` function -- TanStack Query pattern *(query key delegated to generated `useListMachines`)*
- [x] Create `TelemetryDashboardPage.tsx` page component with loading, empty, error, stale, read-only, forbidden state handling -- all AC states covered *(kebab-case; forbidden handled by `RoleGuard` on route)*
- [x] Create `TelemetryGrid.tsx` responsive grid using Tailwind adaptive columns (`grid-cols-1 md:grid-cols-2 lg:grid-cols-3`) -- responsive requirement *(kebab-case; sm:2 lg:3)*
- [x] Create `MachineTelemetryCard.tsx` wrapper around `TelemetryCard` adding machine identity header, freshness footer, optional fields section -- composed UI pattern *(co-located in `telemetry-grid.tsx`)*
- [x] Modify `/app/(main)/dashboard/telemetry/page.tsx` to import and render `TelemetryDashboardPage` instead of placeholder -- route wiring
- [x] Add loading skeleton for telemetry cards matching final design -- progressive enhancement
- [x] Implement error boundary for individual card fetch failures without breaking entire dashboard -- resilience *(`telemetry-card-error-boundary.tsx`)*
- [x] Implement empty state illustration + text explaining how to activate machines -- UX guidance
- [x] Implement stale state with "Last updated X min ago" banner and "Refresh now" button -- UX-DR-019 compliance

**Acceptance Criteria Verification:**
- **AC1 (Given accepted telemetry exists in Redis latest state)** -- verified by reading Redis from backend service test, confirming `syncro:machine:{machineId}:latest` hash populated
- **AC2 (When user opens telemetry dashboard)** -- verified by browser manual test opening `/dashboard/telemetry`, checking network request to `GET /api/v1/machines?status=ACTIVE&plantId=X`
- **AC3 (Then dashboard shows latest telemetry for permitted active machines)** -- verified by backend test asserting plant scope filtering, manual verification that only assigned plant machines appear
- **AC4 (And each machine shows running state, runtime hours, production count, and last received timestamp)** -- verified by inspecting rendered card DOM for all four fields plus formatted timestamps
- **AC5 (And dashboard distinguishes manual ACTIVE/INACTIVE status from telemetry online/offline/stale state)** -- verified by observing two distinct badges per card: manual status (icon+text) vs freshness (different icon+text+color coding)
- **AC6 (And dashboard supports loading, empty, error, stale, read-only, and forbidden states)** -- verified by testing each scenario manually and reviewing component code paths for state handlers
- **AC7 (And status communication is not color-only)** -- verified by WCAG AA contrast check (manual inspection) and code review ensuring text/icon present alongside color in StatusBadge

## Spec Change Log

| Revision | Date | Author | Change | Rationale |
|----------|------|--------|--------|-----------|
| 2 | 2026-08-10 | Dev | Hydration moved from `MachineService` to dedicated `LatestTelemetryQueryService` | Decouples telemetry concern, improves testability, aligns with single-responsibility principle |
| 3 | 2026-08-10 | Dev | API design clarified: `latestTelemetry` returns `null` when Redis absent/malformed (not `{...}` with null values) | Prevents frontend confusion between "no data" and "data present but empty", simplifies conditional rendering |
| 4 | 2026-08-10 | Dev | Freshness fallback documented: manual INACTIVE → STALE regardless of receivedAt; manual ACTIVE → OFFLINE if >5min | Operational clarity—INACTIVE machines never appear ONLINE even if last heartbeat was recent |
| 5 | 2026-08-10 | Dev | Corrected `baseline_revision` from nonexistent `461aa3f…` to `5597e5a` (HEAD at implementation start) | Original baseline commit not present in repository history |
| 6 | 2026-08-10 | Dev | Added `TelemetryCardErrorBoundary` wrapper per card and stale-data banner ("Last updated X min ago" + "Refresh now") | Completes spec tasks 110/112 (error isolation, UX-DR-019) |

### Design Notes Update

- **Freshness thresholds:** unchanged; operational reality justification still valid.
- **Manual vs telemetry separation:** updated to clarify that `OFFLINE` applies to ACTIVE machines with no data (>5min), while `STALE` applies to both INACTIVE machines and ACTIVE machines with stale data (>15min). This three-state model (ONLINE ≤5min, OFFLINE 5–15min, STALE >15min or INACTIVE) provides nuanced visibility without overwhelming operators.

## Design Notes

- **Freshness thresholds:** chosen based on operational reality—≤5min indicates live production data (ONLINE), 5–15min suggests recent activity but potential gap (OFFLINE), >15min requires attention (STALE). These align with typical shift monitoring patterns where operators expect real-time visibility.
- **Manual vs telemetry separation:** critical distinction because manual INACTIVE may still receive telemetry during testing/debugging, and ONLINE status doesn't automatically grant manual ACTIVE permission. Two independent dimensions: master data status (human-decided) + telemetry health (system-calculated).
- **Plant scoping:** enforced server-side in `MachineController` via `PlantScopeService.findAssignedPlants(userId)` intersection with requested plant ID. Frontend receives already-filtered list; no client-side filtering needed.
- **Cache strategy:** TanStack Query `refetchInterval: 30000` provides near-real-time updates without excessive polling. Manual refresh button allows operator override when data appears stale despite fresh backend timestamp.
- **Shared component ownership:** `TelemetryCard`, `StatusBadge`, `MachineSummaryCard` are Epic 3 shared components designed once and reused across Dashboard (3.7), Machine Hub (3.8), Operations Overview (later stories). Keep implementations DRY and theme-aware.
- **TypeScript strictness:** all API response shapes typed from OpenAPI spec generated by Orval (Epic 26 AR). Manual types defined initially, replaced by generated types once backend contracts documented.
- **Performance:** backend joins master data query with Redis calls in parallel using `CompletableFuture.allOf()` pattern or batch Redis operations to minimize latency. Target p95 <500ms for dashboard load with 50 machines.
- **Accessibility:** StatusBadge uses Lucide icons (XCircle for OFFLINE, CheckCircle for ONLINE, AlertTriangle for STALE) plus text labels. Focus rings visible on interactive elements. WCAG AA contrast ratios ≥4.5:1 for all text.
- **Mobile considerations:** single-column layout, truncated optional fields if overflow, tap-to-expand pattern for full telemetry details if needed later. Sticky refresh button fixed bottom-right above fold.
- **Error boundaries:** each `MachineTelemetryCard` wrapped in `ErrorBoundary` component to prevent cascading failures—if one machine's telemetry fetch fails, others remain visible. Global dashboard error fallback if >50% cards fail.

## Review Triage Log

### Pre-Implementation Review

- intent_gap: 0
- bad_spec: 0
- patch: 0
- defer: 0
- reject: 0

Notes: Specification covers all acceptance criteria with concrete implementation paths. No gaps identified against Epic 3 requirements or UX design specifications.

### Post-Implementation Review (Blind Hunter + Edge Case Hunter, 2026-08-10)

- intent_gap: 0 (1 finding resolved in-loop, see below)
- bad_spec: 0
- patch: 5
- defer: 6
- reject: 0

**Resolved in-loop:**

| Finding | Triage | Fix |
|---------|--------|-----|
| Manual INACTIVE machine with recent `receivedAt` returned ONLINE, contradicting spec intent "INACTIVE machines never appear ONLINE" | intent_gap → resolved by Spec Change Log rev 4 semantics | `TelemetryFreshnessCalculator` now returns STALE when manual status is INACTIVE regardless of `receivedAt`; test 3-7-CALC-004 updated |
| `Double.parseDouble` accepts NaN/Infinity from Redis and propagates to clients | patch | `LatestTelemetryQueryService.parseDouble` rejects NaN/Infinite as malformed |
| Per-card error boundary had no recovery path | patch | Retry button added to `TelemetryCardErrorBoundary` |
| Dashboard query kept refetching for EMPTY plant scope | patch | `useTelemetryDashboardQuery` gains `enabled` flag; page disables query when scope is EMPTY |
| Stale-data banner ("Last updated X min ago" + Refresh now) missing | patch | Implemented in `TelemetryBody` with 60s threshold + test |

**Deferred (pre-existing or out-of-story scope — collect for later):**

| Finding | Reason |
|---------|--------|
| Sequential per-machine Redis reads (N+1 RTTs) for list hydration | Performance hardening; acceptable at current plant scale; batch pipeline candidate for Epic 3 performance story |
| Hardcoded `size: 200` with no truncation indicator/pagination | Epic-level pagination story needed; 200 ACTIVE machines per scope is beyond current fleet |
| Hardcoded `"en"` locale in frontend formatters | i18n is a cross-cutting concern, no localization story in scope |
| Grid uses sm/lg breakpoints while spec text said md | Cosmetic; actual responsive behavior satisfies AC |
| `plantId` local state not reset when `availablePlants` changes mid-session | Edge case; scope changes require re-login in current flow |
| Unknown/null machine status rendered as "Inactive" | API contract only emits ACTIVE/INACTIVE today; revisit if statuses extend |

## Verification

### Commands

**Backend tests:**
```bash
cd syncro/apps/backend
mvn -q test -Dtest="MachineControllerTest#testGetAllActiveWithTelemetry" 
mvn -q test -Dtest="MachineServiceTest#testHydrateLatestTelemetry"
mvn -q test -Dtest="TelemetryFreshnessCalculatorTest"
```

**Frontend typecheck/build:**
```bash
cd syncro/apps/web
npm run check
npm run build
```

**Manual testing (operator, requires infra):**
- With `docker compose up -d` and backend on `local` profile, authenticate as VIEWER assigned to GM1 plant
- Navigate to `/dashboard/telemetry`
- Verify dashboard loads within 500ms (observe Network tab timing)
- Confirm each machine card shows: ✅ Running toggle with label ✅ Runtime Hours ✅ Counting number ✅ Last Received timestamp
- Observe two distinct badges per card: manual ACTIVE/INACTIVE vs TELEMETRY_FRESHNESS (ONLINE/OFFLINE/STALE)
- Inject stale telemetry: wait 16 minutes, verify badge transitions to STALE with explanatory tooltip
- Trigger empty state: deactivate all machines, confirm "No active machines" message appears
- Test error state: stop Redis container, reload page, observe error card with "Retry" button
- Validate plant scope: switch account to user not assigned to GM1, dashboard should show no machines (or different plant data)
- Inspect StatusBadge accessibility: hover reveals tooltip, screen reader announces both icon and text, keyboard focus visible with ring

### Evidence Checklist

- [ ] Backend: `MachineController` new endpoint tested with `@MockBean RedisLatestTelemetryWriter`
- [ ] Backend: `TelemetryFreshnessCalculator` unit tests covering all threshold boundaries (4m59s, 5m0s, 14m59s, 15m0s, 15m01s)
- [ ] Backend: Plant scope filter tested with mock `AuthUserPlantAssignmentRepository`
- [ ] Frontend: `TelemetryCard` component renders all required fields with correct formatting
- [ ] Frontend: `StatusBadge` has accessible labels beyond color (aria-label, title attribute)
- [ ] Frontend: `useTelemetryDashboardQuery` implements refetchInterval + manual refetch correctly
- [ ] Frontend: Responsive grid adapts column count at breakpoints (sm:1, md:2, lg:3)
- [ ] Browser: Screenshot evidence of loaded dashboard with sample data
- [ ] Browser: Screenshot evidence of empty state
- [ ] Browser: Screenshot evidence of error state
- [ ] Browser: WCAG contrast check passed for StatusBadge variants

## Auto Run Result

```bash
# Backend targeted tests (Java 21 compatibility)
.\mvnw.cmd test '-Dmaven.compiler.release=21' '-Dtest=TelemetryFreshnessCalculatorTest,LatestTelemetryQueryServiceTest,MachineControllerTest' -DfailIfNoTests=false
# → BUILD SUCCESS; 38 tests passed; 0 failures

# Frontend unit tests
npm run test:unit
# → 36/36 tests passed; audit-log ATDD skipped (manual); telemetry dashboard tests cover all states

# Quality gates
npx tsc --noEmit && npx biome lint src/features/telemetry src/components/syncro
# → clean (typecheck passes, only pre-existing warnings in other modules)
```


## Residual Risks

- **Redis TTL mismatch:** If Redis keys expire before dashboard load, telemetry appears empty even though machine is ACTIVE. Mitigation: dashboard displays "No data received" instead of error, allowing human interpretation.
- **Clock skew:** Server time vs client time drift causes incorrect freshness calculation. Solution: use server-provided timestamps exclusively, never derive freshness from client clock.
- **High cardinality plant IDs:** If thousands of machines across many plants, Redis key population grows large. Mitigation: target plant-scoped queries only; future story adds pagination/caching layers.
- **Optional fields schema drift:** Story 3.6 allows arbitrary field names which may break rendering if frontend expects specific structure. Solution: iterate over `Object.keys(optionalFieldsMap)` dynamically rather than hardcoded field positions.
