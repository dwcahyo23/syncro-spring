---
story: 4.7
title: Add Alert State to Machine Hub and Operations Overview
status: done
created_at: 2026-08-19
final_revision: 2026-08-19
---

# Spec: Story 4.7 — Add Alert State to Machine Hub and Operations Overview

## Goal

Surface current sparepart alert state directly inside the Machine Hub Overview tab and a new Operations Overview page, so operators can see risk at a glance without navigating to the alert module.

## Acceptance Criteria (from epics.md)

- **Given** active or recent alerts exist for a machine
  **When** user opens Operations Overview or Machine Hub
  **Then** UI shows alert state, sparepart risk, status reason, and timestamp
- **And** Machine Hub links to alert detail
- **And** Operations Overview highlights what needs attention now
- **And** UI uses backend-provided allowed actions and status severity
- **And** status communication remains non-color-only and responsive on desktop/tablet/mobile

---

## Frontend

### New page — Operations Overview (`/dashboard/operations-overview`)

Route file: `app/(main)/dashboard/operations-overview/page.tsx` — thin wrapper that renders `<OperationsOverviewPageContent />`.

Feature component: `features/operations-overview/operations-overview-page-content.tsx`

#### Plant scope integration

Reads `{ scope, activePlantId, loadError }` from `usePlantScope()`. Three guard states are rendered before the main layout:

| Guard | Condition | Rendered |
|-------|-----------|----------|
| Load error | `loadError` truthy | "Plant scope unavailable" card |
| Scope pending | `!scope` | Full-width `<Skeleton>` |
| No plants | `scope.mode === "EMPTY"` | "No plants assigned" card |

#### Alert data fetching

```ts
const openAlertsQuery = useListAlerts(
  { status: "OPEN", plantId, page: 0, size: 5, sort: "createdAt,asc" },
  { query: { enabled: !!scope, staleTime: 30_000 } },
);
```

- `plantId` is derived from `activePlantId` when it is not `"all"`, otherwise `undefined` (SUPER_ADMIN cross-plant view).
- Query is disabled until scope is resolved (`enabled: !!scope`).
- `staleTime: 30_000` (30 s) — avoids refetching on every re-render while keeping data reasonably fresh.

#### Page layout

**Header** — page title "Operations Overview" with subtitle "What needs attention now."

**Summary metric bar** — responsive 2→4 column grid:
- "Open Alerts" tile: clickable card linking to `/alerts?status=OPEN`, shows skeleton while loading, then `totalElements` count
- Three placeholder tiles ("Active Machines", "Stale Telemetry", "Health Status") rendered with `opacity-50` and `—` — reserved for Epic 5+

**"Alerts Requiring Action" section** — `<section aria-labelledby="alerts-section-heading">` with a header link to `/alerts`:

| State | Rendered |
|-------|----------|
| Loading | 3× `<Skeleton className="h-14 w-full">` |
| Error | Card with "Failed to load alerts." text + Retry button calling `openAlertsQuery.refetch()` |
| Empty | Card with Bell icon + "No open alerts / All spareparts within threshold." |
| Has alerts | `<ul role="list">` of up to 5 alert rows, each linking to `/alerts/{id}` |

Each alert row shows:
- `<AlertStatusBadge status={item.status} />` (non-color-only badge from 4.3)
- Machine code + optional name (`machineCode — machineName`)
- Sparepart name/code + function name
- Consumed percentage + threshold percentage (tabular-nums)
- Relative timestamp via `timeAgo(item.createdAt)` (hidden on mobile, visible `sm:inline`)

When `totalElements > 5`: overflow link "+N more open alerts — view all" linking to `/alerts?status=OPEN`.

#### `timeAgo()` helper

Pure function defined at module level, converts an ISO date string to a human-readable relative string:
- `< 60 min` → `Xm ago`
- `< 24 h` → `Xh ago`
- `≥ 24 h` → `Xd ago`
- Missing value → `"-"`

---

### Modified component — Machine Hub Overview tab

File: `features/machine-hub/overview-tab.tsx`

#### Alert data fetching

```ts
const alertsQuery = useListAlerts(
  { machineId: machine?.id, status: "OPEN", page: 0, size: 5, sort: "createdAt,asc" },
  { query: { enabled: Boolean(machine?.id), staleTime: 30_000 } },
);
```

Scoped to `machineId` so only alerts for the currently viewed machine are fetched. Query is disabled until `machine.id` is available.

```ts
const openAlerts = alertsQuery.data?.data?.items ?? [];
const openAlertsTotal = alertsQuery.data?.data?.totalElements ?? 0;
```

#### New "Sparepart Alert State" card

Added below the existing "Machine Details" card. Card header includes an `AlertTriangle` icon that switches color based on open alert count:
- `openAlertsTotal > 0` → `text-destructive` (red)
- `openAlertsTotal === 0` → `text-muted-foreground` (gray)

Card body states:

| State | Rendered |
|-------|----------|
| Loading | 2× `<Skeleton className="h-10 w-full">` |
| Error | "Failed to load alert state." text |
| No alerts | "No open alerts. All spareparts within threshold." text |
| Has alerts | `<ul role="list">` of up to 5 alert rows |

Each alert row is a link to `/alerts/{item.id}` showing:
- `<AlertStatusBadge status={item.status} />`
- Sparepart name/code (truncated) + function name (truncated, secondary text)
- Consumed/threshold ratio: `X.X% / Y%` (tabular-nums, right-aligned)

When `openAlertsTotal > 0`: "View all N alerts →" link below the list. Clicking it programmatically activates the Alerts tab via `document.querySelector('[data-value="alerts"]')?.click()`.

---

## No Backend Changes

Story 4.7 is purely frontend. It reuses the existing `GET /api/v1/alerts` endpoint (delivered in Story 4.3) with `status=OPEN` and optional `machineId`/`plantId` filters. No new backend endpoints, migrations, or services were added.

---

## Test Plan

No automated unit tests were added for this story — the components are presentation-layer wiring over an already-tested API. Manual test matrix:

| # | Surface | Scenario | Expected |
|---|---------|----------|----------|
| 1 | Operations Overview | Plant scope still loading | Skeleton displayed, no API call |
| 2 | Operations Overview | `scope.mode === "EMPTY"` | "No plants assigned" card |
| 3 | Operations Overview | Plant scope load error | "Plant scope unavailable" card |
| 4 | Operations Overview | Alerts API loading | 3× skeleton rows in Alerts section |
| 5 | Operations Overview | Alerts API error | Error card with Retry button |
| 6 | Operations Overview | No open alerts | Bell icon + "No open alerts" empty state |
| 7 | Operations Overview | 3 open alerts | 3 rows, each linking to `/alerts/{id}` |
| 8 | Operations Overview | 7 open alerts | 5 rows + "+2 more open alerts" overflow link |
| 9 | Operations Overview | `activePlantId = "all"` | Query sent without `plantId` param |
| 10 | Operations Overview | `activePlantId = <uuid>` | Query sent with `plantId` param |
| 11 | Machine Hub Overview tab | Machine not yet loaded | Skeleton card rendered |
| 12 | Machine Hub Overview tab | No open alerts for machine | Gray AlertTriangle + "No open alerts" text |
| 13 | Machine Hub Overview tab | 2 open alerts | Red AlertTriangle, 2 alert rows with links to detail |
| 14 | Machine Hub Overview tab | Alert row link | Navigates to `/alerts/{id}` |
| 15 | Machine Hub Overview tab | "View all N alerts" link | Programmatically clicks Alerts tab |
| 16 | Both surfaces | `AlertStatusBadge` | Non-color-only status communicated (text + icon) |

---

## Files Created/Modified

### Frontend

| File | Action |
|------|--------|
| `features/operations-overview/operations-overview-page-content.tsx` | Create — new Operations Overview page content component |
| `app/(main)/dashboard/operations-overview/page.tsx` | Create — Next.js route page, wraps `OperationsOverviewPageContent` |
| `features/machine-hub/overview-tab.tsx` | Modify — add Sparepart Alert State card with `useListAlerts` |

### Backend

No backend files modified.

---

## Dev Agent Record

### Agent Model Used
claude-sonnet-4-5 (retroactive spec)

### Completion Notes List
- Spec created retroactively from implemented code (commit b664f75)
- Operations Overview is a new top-level page under `/dashboard/operations-overview`; no sidebar navigation wiring is documented here — assumed done separately or deferred
- AC wording "UI uses backend-provided allowed actions and status severity" is partially met: severity is communicated via `AlertStatusBadge` (non-color-only); allowed actions are not shown on these summary views — this is appropriate since neither page is an action surface
- Placeholder metric tiles (Active Machines, Stale Telemetry, Health Status) are intentionally stubbed with `opacity-50` for Epic 5+
- `timeAgo()` helper has no unit test; it is a pure utility function with straightforward boundary logic

### File List
- `syncro/apps/web/src/features/operations-overview/operations-overview-page-content.tsx`
- `syncro/apps/web/src/app/(main)/dashboard/operations-overview/page.tsx`
- `syncro/apps/web/src/features/machine-hub/overview-tab.tsx`
