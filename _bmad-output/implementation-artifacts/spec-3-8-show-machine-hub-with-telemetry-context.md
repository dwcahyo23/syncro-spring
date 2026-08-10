---
id: SPEC-3-8
type: feature
created: 2026-08-10
status: draft
review_loop_iteration: 0
baseline_revision: $(git rev-parse HEAD)
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
  - '_bmad-output/implementation-artifacts/spec-3-7-show-latest-telemetry-dashboard.md'
  - '_bmad-output/planning-artifacts/page-specifications.md'
  - '_bmad-output/planning-artifacts/ux-design-specification.md'
warnings: []
companions:
  - '_bmad-output/implementation-artifacts/test-design-3-8-show-machine-hub-with-telemetry-context.md'
sources:
  - '_bmad-output/planning-artifacts/epics.md'
---

# Show Machine Hub with Telemetry Context

## Intent

**Problem:** Users need a single operational context to see everything about one machine — master data, live telemetry, sparepart lifetime, alerts, responsibility chain, and audit history — so that they can diagnose issues and take action without switching screens. This is especially critical for field technicians receiving WAHA notifications who land on the alert detail and need to understand machine state quickly.

**Approach:** Frontend builds machine hub page at `/master-data/machines/[machineCode]` with progressive tab loading (Overview, Telemetry, Spareparts, Alerts, Audit Log). Backend exposes combined machine detail endpoint with optional telemetry enrichment from Redis. All views filtered by plant scope per Story 1.7, with freshness indicator shown in header using `StatusBadge` component from Story 3.7.

## Boundaries & Constraints

**Always:**
- Route: `/master-data/machines/[machineCode]` (using machineCode from URL params)
- Header shows: machine identity, manual status (ACTIVE/INACTIVE), telemetry freshness (ONLINE/STALE/OFFLINE) with StatusBadge
- Tab navigation: Overview | Telemetry | Spareparts | Alerts | Audit Log
- Plant scope enforced via user's plant assignments (Story 1.7)
- Progressive loading: header loads first, then tabs load as requested
- No real-time WebSocket — polling every 30s max, with manual refresh button
- Mobile-responsive design — simplified layout on small screens
- Empty states shown when no data exists (e.g., "No alerts yet", "No spareparts installed")
- Forbidden state: 403 response if user has no plant assignment for this machine
- Telemetry freshness calculation uses server-provided timestamps only (never client clock)
- Inactive machines show: "Telemetry rejected — machine is INACTIVE" banner at top

**Block If:**
- Machine master data not found → 404 response with explanation
- User lacks permission for machine's plant → redirect to Operations Overview with message

## Capabilities

### CAP-1: View Complete Machine Context

**intent:** System provides comprehensive machine view with all operational aspects visible in unified interface.

**success:** User lands on machine hub URL, sees complete metadata + telemetry freshness immediately, can navigate between tabs without losing context or plant scope.

### CAP-2: Display Telemetry Freshness Indicator

**intent:** System clearly communicates if telemetry data is current, stale, or offline using visual indicators.

**success:** 
- StatusBadge component shows correct state:
  - `ONLINE` (green): last received ≤5 minutes ago
  - `OFFLINE` (gray): no recent data but machine ACTIVE  
  - `STALE` (yellow): last received >15 minutes ago or machine INACTIVE
- Text label + icon always present (never color alone)
- Tooltip shows exact "X minutes ago" timestamp

### CAP-3: Tab-Based Navigation with Progressive Loading

**intent:** User can access different aspects of machine data through organized tabs that load progressively.

**success:**
- Header renders first (<200ms)
- Each tab content loads on demand when clicked
- Skeleton loading state shown while fetching tab data
- Previous tab content preserved when switching
- Breadcrumb trail maintained: Operations → Master Data → Machines → [Machine Name]

### CAP-4: Filter and Search within Tabs

**intent:** Each tab supports filtering/searching to find relevant information efficiently.

**success:**
- Alerts tab: filter by severity, date range, open/closed status
- Spareparts tab: filter by lifetime %, sort by remaining days
- Audit Log tab: filter by entity type, actor, date range
- Telemetry tab: time range selector (last 1h / 6h / 24h) for historical view

### CAP-5: Handle Inactive Machine State

**intent:** User understands why telemetry is unavailable when viewing inactive machine.

**success:**
- INACTIVE machine hub displays: "This machine is currently INACTIVE. Telemetry messages are being rejected."
- Reason link to Story 3.3 acceptance criteria explaining rejection logic
- Still shows all master data and historical information
- Can navigate back to active machines list

## Non-goals

- Heavy historical charting with complex interactions (reserved for Epic 6 deep-dive analytics)
- Real-time WebSocket streaming (Phase 1 uses polling with 30s maximum interval)
- Full-page telemetry history export/download (only summary/stats in Telemetry tab)
- Multi-machine comparison view (single machine focus only)
- Offline PWA mode for hub access (requires separate story)

## Success Signal

User lands on `/master-data/machines/JBF19`, immediately sees machine name, manual status, and telemetry freshness badge. Clicks "Telemetry" tab, sees running state, runtime hours, production count, and knows within seconds if data is fresh. Can switch to "Alerts" tab to see any open notifications for this machine.

## Assumptions

- Machine master data endpoints (Story 2.1–2.9) return complete machine metadata including plant assignment
- Redis latest telemetry state available per machine ID (Story 3.4–3.6)
- Audit log queryable by machine ID with filters (Story 2.9)
- Alert queries support machine ID filter with pagination (Story 4.3–4.7)
- Sparepart installation history available via machine-level API (Story 2.6–2.7)
- Plant scope enforcement middleware applies to all machine-related endpoints (Story 1.7)
- StatusBadge, Skeleton, EmptyState, ErrorBoundary components reusable from Story 3.7 ecosystem
- TanStack Query handles cache management and revalidation automatically

## Open Questions

1. **Stale threshold configurability**: Should the 5-minute online threshold be configurable per plant? Default: 5 min, max: 15 min.
   
2. **Loading performance target**: Acceptable time to first meaningful paint for machine hub? Target: <300ms for header, <800ms for first tab content.

3. **Polling interval optimization**: Should polling interval adapt based on machine criticality (e.g., high-alert machines poll more frequently)?

4. **Breadcrumb depth**: Is 4-level breadcrumb sufficient or should we add drill-down capabilities?

5. **Mobile touch targets**: Minimum button/interactive element size for touch devices? Target: 44x44px minimum.
