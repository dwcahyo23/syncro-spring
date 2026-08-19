---
story: 4.3
title: Show Alert List and Alert Detail
status: in-progress
created_at: 2026-08-19
final_revision: ~
---

# Spec: Story 4.3 — Show Alert List and Alert Detail

## Goal

Expose a read-only alert list and alert detail API, wire the frontend `/alerts` page, update the Machine Hub Alerts tab, and introduce the `LifetimeProgress` component — so users can understand what fired and why.

## Acceptance Criteria (from epics.md)

- UI shows alert status, machine identity, sparepart, threshold percentage, current count, consumed percentage, created timestamp, and status reason
- Alert detail explains why alert fired
- `LifetimeProgress` shows baseline, current count, expected count, consumed percentage, and threshold
- UI uses non-color-only `StatusBadge` pattern
- Page supports loading, empty, error, stale, read-only, and forbidden states
- Frontend does not recalculate alert status independently

---

## Backend

### New files

#### `SparepartAlertDtos.java`
Package: `com.syncro.alert.api`

Records:
```
AlertListResponse(List<AlertView> items, long totalElements, int page, int size, String sort)

AlertView(
  UUID id,
  UUID machineId, String machineCode, String machineName,
  UUID plantId, String plantCode, String plantName,
  UUID machineGroupId, String machineGroupName,
  UUID installationId,
  UUID sparepartId, String sparepartCode, String sparepartName, String functionName,
  int thresholdPercentage,
  long baselineCounter,
  long currentCounterSnapshot,
  long consumedProductionCountSnapshot,
  BigDecimal consumedPercentageSnapshot,
  SparepartAlertStatus status,
  String statusReason,
  String traceId,
  Instant createdAt,
  Instant updatedAt
)
```

All fields `@Schema(nullable = false)` except `statusReason`, `machineName` nullable true.

#### `SparepartAlertQueryService.java`
Package: `com.syncro.alert.application`

Methods:
- `list(AuthenticatedUser user, UUID machineId, UUID plantId, SparepartAlertStatus status, int page, int size, String sort) → AlertListView`
- `get(AuthenticatedUser user, UUID alertId) → AlertDetailView`

Plant-scope enforcement: SUPER_ADMIN sees all; MANAGE/VIEWER scoped to assigned plants (same pattern as `MachineSparepartInstallationService`).

Filter: `machineId` optional, `plantId` optional, `status` optional.

Internal records:
```
AlertListView(List<AlertDetailView> items, long totalElements, int page, int size, String sort)
AlertDetailView(alert fields + enriched machine/installation/sparepart info)
```

#### `SparepartAlertController.java`
Package: `com.syncro.alert.api`

```
GET /api/v1/alerts
  params: machineId (UUID, optional), plantId (UUID, optional), status (SparepartAlertStatus, optional)
          page (default 0), size (default 50), sort (default "createdAt,desc")
  roles: VIEWER, MANAGE, SUPER_ADMIN
  responses: 200 AlertListResponse, 401, 403

GET /api/v1/alerts/{alertId}
  roles: VIEWER, MANAGE, SUPER_ADMIN
  responses: 200 AlertView, 401, 403, 404
```

### Modified files

#### `SparepartAlertRepository.java`
Add JPQL queries with `join fetch` to enrich alert with machine (plant, group) and installation (sparepart, taxonomy):

```java
// List scoped (plant-scoped)
@Query("""
    select alert from SparepartAlertEntity alert
    join fetch alert.installation installation
    join fetch installation.machine machine
    join fetch machine.plant plant
    join fetch machine.machineGroup machineGroup
    join fetch installation.sparepart sparepart
    where plant.id in :plantIds
      and (:machineId is null or machine.id = :machineId)
      and (:plantId is null or plant.id = :plantId)
      and (:status is null or alert.status = :status)
    """)
Page<SparepartAlertEntity> findAllScoped(...)

// List unscoped (SUPER_ADMIN)
// Get by id scoped
// Get by id unscoped
```

**Important:** `SparepartAlertEntity` does NOT currently have JPA relationships — it stores raw FKs only. We need to either:
- Option A: Add `@ManyToOne` joins to `SparepartAlertEntity` for `installation` (and machine/sparepart via installation)
- Option B: Use a JPQL query with explicit JOIN on id equality

**Decision: Option A** — add `@ManyToOne(fetch = LAZY)` for `installation` and `machine` on `SparepartAlertEntity`. This matches the pattern used by `MachineSparepartInstallationEntity`.

#### `SparepartAlertEntity.java`
Add:
```java
@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "machine_sparepart_installation_id", insertable = false, updatable = false)
private MachineSparepartInstallationEntity installation;

@ManyToOne(fetch = FetchType.LAZY)
@JoinColumn(name = "machine_id", insertable = false, updatable = false)
private MachineEntity machine;
```
Both `insertable=false, updatable=false` — the raw UUID columns remain the single source of truth for writes.

Add getters `getInstallation()`, `getMachine()`.

No migration needed — schema unchanged.

---

## Frontend

### New files

#### `features/alerts/alert-status-badge.tsx`
`AlertStatusBadge` component — non-color-only badge for `OPEN | ACKNOWLEDGED | RESOLVED`.

Config:
- `OPEN` → `CircleAlert` icon, amber styling + `aria-label`
- `ACKNOWLEDGED` → `CircleDashed` icon, blue styling
- `RESOLVED` → `CircleCheck` icon, muted/emerald styling

Pattern: same as existing `StatusBadge` in `components/syncro/status-badge.tsx` (Badge + Tooltip + Icon + className).

#### `features/alerts/lifetime-progress.tsx`
`LifetimeProgress` component — shows lifetime evidence for an alert.

Props:
```ts
interface LifetimeProgressProps {
  baselineCounter: number;
  currentCounterSnapshot: number;
  expectedProductionCount: number; // from installation, fetched separately or passed from alert list
  consumedProductionCountSnapshot: number;
  consumedPercentageSnapshot: number; // BigDecimal serialized as string from backend
  thresholdPercentage: number;
}
```

UI: Progress bar (shadcn `Progress`) + data grid showing all 5 values. Threshold shown as a marker annotation. No recalculation — renders backend values only.

**Note on `expectedProductionCount`:** `SparepartAlertEntity` does NOT store `expectedProductionCount` — it's on the installation. The `AlertDetailView` in `SparepartAlertQueryService` will JOIN to `installation.expectedProductionCount` and include it in `AlertView`. Add `expectedProductionCount: long` to `AlertView` DTO.

#### `features/alerts/alert-list-page-content.tsx`
`AlertListPageContent` — client component.

Features:
- Calls `useListAlerts` (orval-generated)
- Filter bar: status filter (OPEN / ACKNOWLEDGED / RESOLVED / All), optional plant filter via `PlantScopeSelector`
- Table columns: status badge, machine code+name, sparepart name, threshold%, consumed%, created at
- Row click → navigate to `/alerts/{id}`
- Loading: `Skeleton`
- Empty: `EmptyState`
- Error: error card with retry
- Read-only: all VIEWER, no action buttons here

#### `features/alerts/alert-detail-page-content.tsx`
`AlertDetailPageContent` — client component.

Features:
- Calls `useGetAlert(alertId)`
- Shows: machine identity (code, plant, group), sparepart (code, name, function), alert metadata (trace ID, created at, updated at)
- `AlertStatusBadge` for status
- `LifetimeProgress` component with all 6 values
- Status reason if present
- Why-it-fired explanation: "This alert fired because consumed percentage ({x}%) reached the configured threshold ({y}%)."
- Loading / error / 404 / 403 states
- Back link to `/alerts`
- No action buttons in 4.3 (acknowledge/resolve in 4.4/4.5)

### Modified files

#### `app/(main)/dashboard/alerts/page.tsx`
Replace `PlantScopedModulePlaceholder` with `AlertListPageContent`.

Add `app/(main)/dashboard/alerts/[alertId]/page.tsx` (new route) that renders `AlertDetailPageContent`.

#### `features/machine-hub/alerts-tab.tsx`
Replace placeholder `EmptyState` with a real alert list scoped to `machineId`.
- Calls `useListAlerts({ machineId })`
- Shows compact table (status, sparepart, threshold%, consumed%, created)
- Row click → navigate to `/alerts/{alertId}`
- Supports loading, empty, error states

---

## API Client Regeneration

After backend is implemented and running:
```
cd syncro/apps/web && npm run generate:api
```
This regenerates `src/lib/api/generated/syncro.ts` with `useListAlerts` and `useGetAlert` hooks.

---

## Test Plan

### Backend unit tests (`SparepartAlertQueryServiceTest.java`)
1. SUPER_ADMIN sees all alerts unscoped
2. MANAGE user sees only alerts in assigned plants
3. Filter by status returns only matching alerts
4. Filter by machineId scopes correctly
5. Get alert by ID returns enriched view
6. Get alert not in user's plant scope → throws `RESOURCE_NOT_FOUND` (or 403)

### Frontend component tests
- `AlertStatusBadge` renders icon + label for each status
- `LifetimeProgress` renders all values without recalculation

---

## Files to Create/Modify

### Backend
| File | Action |
|------|--------|
| `alert/api/SparepartAlertDtos.java` | Create |
| `alert/api/SparepartAlertController.java` | Create |
| `alert/application/SparepartAlertQueryService.java` | Create |
| `alert/infrastructure/SparepartAlertRepository.java` | Modify — add JPQL queries |
| `alert/infrastructure/SparepartAlertEntity.java` | Modify — add @ManyToOne for installation + machine |

### Frontend
| File | Action |
|------|--------|
| `features/alerts/alert-status-badge.tsx` | Create |
| `features/alerts/lifetime-progress.tsx` | Create |
| `features/alerts/alert-list-page-content.tsx` | Create |
| `features/alerts/alert-detail-page-content.tsx` | Create |
| `app/(main)/dashboard/alerts/page.tsx` | Modify |
| `app/(main)/dashboard/alerts/[alertId]/page.tsx` | Create |
| `features/machine-hub/alerts-tab.tsx` | Modify |

### Tests
| File | Action |
|------|--------|
| `alert/application/SparepartAlertQueryServiceTest.java` | Create |

---

## Deferred

- Alert pagination controls in UI (list uses server-side default size=50, no UI pagination in 4.3)
- Alert filtering by date range (deferred to later)
- `expectedProductionCount` field on `SparepartAlertEntity` directly (reads from installation JOIN instead)
