---
title: Syncro Page Specifications
status: final
created: 2026-05-25
author: Freya (WDS Designer)
inputDocuments:
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/frontend-hardening-specification.md
  - _bmad-output/planning-artifacts/architecture.md
---

# Syncro Page Specifications

Detailed interaction, content, and state specifications for primary operational screens. Each spec defines what the screen shows, how it behaves, what states it handles, and what microcopy it uses.

---

## 1. Operations Overview

**Route:** `/operations-overview`
**Purpose:** Answer "What needs attention now?" in one glance.
**Users:** All roles (content filtered by plant scope).
**Default landing page after login.**

### 1.1 Layout

```
┌─────────────────────────────────────────────────────┐
│ Header: "Operations Overview" + PlantScopeSelector  │
├─────────────────────────────────────────────────────┤
│ ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌───────────┐ │
│ │ Open    │ │ Machines│ │ Stale   │ │ Health    │ │
│ │ Alerts  │ │ Active  │ │Telemetry│ │ Status    │ │
│ │ [count] │ │ [count] │ │ [count] │ │ [status]  │ │
│ └─────────┘ └─────────┘ └─────────┘ └───────────┘ │
├─────────────────────────────────────────────────────┤
│ SECTION: Alerts Requiring Action                    │
│ ┌─────────────────────────────────────────────────┐ │
│ │ AlertRow: machine | sparepart | threshold | age │ │
│ │ AlertRow: machine | sparepart | threshold | age │ │
│ └─────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│ SECTION: Live Telemetry (recent activity)           │
│ ┌────────────┐ ┌────────────┐ ┌────────────┐      │
│ │TelemetryCard│TelemetryCard│TelemetryCard│      │
│ └────────────┘ └────────────┘ └────────────┘      │
├─────────────────────────────────────────────────────┤
│ SECTION: Health Summary (SUPER_ADMIN only)          │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│ │HealthCard│ │HealthCard│ │HealthCard│            │
│ └──────────┘ └──────────┘ └──────────┘            │
└─────────────────────────────────────────────────────┘
```

### 1.2 Content Sections

#### Summary Metrics Bar

| Metric | Source | Click Action |
|--------|--------|--------------|
| Open Alerts | `GET /api/v1/alerts?status=OPEN&plantScope=active` count | Navigate to Alerts list |
| Active Machines | `GET /api/v1/machines?status=ACTIVE&plantScope=active` count | Navigate to Machines list |
| Stale Telemetry | Machines where `lastSeen` > 5 minutes ago | Navigate to Telemetry with stale filter |
| Health Status | Aggregate from `/api/v1/health/summary` | Navigate to System Health |

#### Alerts Requiring Action

- Show top 5 OPEN alerts sorted by creation time (oldest first — longest unattended).
- Each row: machine code/name, sparepart name, consumed %, threshold %, time since created.
- Row click → Alert Detail.
- "View all alerts" link at bottom → Alerts list.
- Empty state: "No open alerts. All spareparts within threshold."

#### Live Telemetry

- Show up to 6 most recently active machines as `TelemetryCard` components.
- Sorted by `lastSeen` descending (most recent first).
- Card click → Machine Detail.
- Empty state: "No telemetry received yet. Verify machine setup and MQTT connectivity."

#### Health Summary (SUPER_ADMIN only)

- Show `HealthCard` for each dependency with non-healthy status.
- If all healthy: single green card "All systems operational" with last check timestamp.
- Card click → System Health page.
- Hidden for MANAGE and VIEWER roles.

### 1.3 Responsive Behavior

| Viewport | Layout |
|----------|--------|
| Desktop (≥1024px) | 4-col metric bar, alert table, 3-col telemetry grid, 3-col health grid |
| Tablet (768-1023px) | 2-col metric bar (2 rows), alert table, 2-col telemetry grid |
| Mobile (<768px) | Stacked metric cards, alert cards (not table), stacked telemetry cards, health hidden (link to System Health) |

### 1.4 States

| State | Behavior |
|-------|----------|
| Loading | Skeleton for metric bar, skeleton rows for alerts, skeleton cards for telemetry |
| Empty (no data) | Show setup prompt: "Get started by configuring your first machine in Master Data." |
| Error (API failure) | Show error card with retry button and timestamp of last successful load |
| No plant assignment | "No plants assigned to your account. Contact your administrator." |
| Partial data | Show available sections; failed sections show individual error state |

### 1.5 Microcopy

- Page title: "Operations Overview"
- Metric labels: "Open Alerts", "Active Machines", "Stale Telemetry", "System Health"
- Stale definition tooltip: "Machines with no telemetry received in the last 5 minutes."
- Empty alerts: "No open alerts. All spareparts within threshold."
- Empty telemetry: "No telemetry received yet. Verify machine setup and MQTT connectivity."
- Setup prompt: "Get started by configuring your first machine in Master Data."

### 1.6 Data Refresh

- Auto-refresh every 30 seconds for telemetry cards and alert count.
- Health summary refreshes every 60 seconds.
- Manual refresh button in header.
- No WebSocket in Phase 1 — polling only.

---

## 2. Machine Detail (Hub)

**Route:** `/master-data/machines/[machineId]`
**Purpose:** Show everything about one machine in a single operational context.
**Users:** All roles (filtered by plant scope).

### 2.1 Layout

```
┌─────────────────────────────────────────────────────┐
│ Header: Machine Code + Name + StatusBadge           │
│ Breadcrumb: Master Data > Machines > BF-08410       │
├─────────────────────────────────────────────────────┤
│ TABS: [Overview] [Telemetry] [Spareparts] [Alerts]  │
│       [Responsibility] [History]                     │
├─────────────────────────────────────────────────────┤
│ TAB CONTENT (varies by selected tab)                │
└─────────────────────────────────────────────────────┘
```

### 2.2 Header

| Element | Content |
|---------|---------|
| Machine code | `BF-08410` (prominent, monospace) |
| Machine name | `JBF19` (secondary) |
| Status badge | `ACTIVE` or `INACTIVE` (StatusBadge) |
| Telemetry freshness | `Live` / `Stale (5m ago)` / `No data` (StatusBadge) |
| Plant + Group | `GM1 / Forming` (breadcrumb context) |
| Edit button | Visible for SUPER_ADMIN and MANAGE (opens edit form) |

### 2.3 Tab: Overview

Shows summary of all machine aspects:

```
┌─────────────────────────┬───────────────────────────┐
│ Machine Identity         │ Latest Telemetry          │
│ Code: BF-08410          │ ┌─────────────────────┐   │
│ Name: JBF19             │ │ TelemetryCard       │   │
│ Plant: GM1              │ │ (inline variant)    │   │
│ Group: Forming          │ └─────────────────────┘   │
│ Brand: —                │                           │
│ Installed: —            │                           │
│ Status: ACTIVE          │                           │
├─────────────────────────┼───────────────────────────┤
│ Sparepart Risk          │ Active Alerts             │
│ ┌─────────────────────┐ │ ┌─────────────────────┐   │
│ │ LifetimeProgress    │ │ │ Alert summary card  │   │
│ │ (highest risk first)│ │ │ (if any OPEN)       │   │
│ └─────────────────────┘ │ └─────────────────────┘   │
├─────────────────────────┼───────────────────────────┤
│ Responsibility Chain    │ Setup Completeness        │
│ TECHNICIAN: [name]      │ ┌─────────────────────┐   │
│ STAFF: [name]           │ │SetupChecklist       │   │
│ LEADER: [name]          │ │(if incomplete)      │   │
│                         │ └─────────────────────┘   │
└─────────────────────────┴───────────────────────────┘
```

### 2.4 Tab: Telemetry

| Element | Content |
|---------|---------|
| Latest values | `running`, `runtimeHours`, `counting`, `lastSeen`, configured optional fields |
| Freshness indicator | StatusBadge: Live / Stale / No data |
| Latency | LatencyIndicator (if available) |
| History | Last 24h summary (count of messages received, min/max/avg counting delta) |
| Stale warning | Banner: "Telemetry stale — last data received {duration} ago." |

**No heavy charts on mobile.** Desktop may show a simple sparkline for counting trend (last 1h).

### 2.5 Tab: Spareparts

| Element | Content |
|---------|---------|
| Installed spareparts list | Table: sparepart name, baseline, current, consumed, expected, threshold %, consumed % |
| LifetimeProgress | Per installed sparepart — visual progress + numeric evidence |
| Risk sorting | Highest consumed % first |
| Actions | Install new sparepart (MANAGE+), edit threshold, reset baseline |

### 2.6 Tab: Alerts

| Element | Content |
|---------|---------|
| Active alerts | OPEN and ACKNOWLEDGED alerts for this machine |
| Alert history | Resolved alerts (paginated, newest first) |
| Each row | Sparepart, status, created at, acknowledged by/at, resolved by/at |
| Row click | → Alert Detail |

### 2.7 Tab: Responsibility

| Element | Content |
|---------|---------|
| Responsibility chain | Table: level (TECHNICIAN/STAFF/LEADER/SPV/MANAGER), assigned user, phone |
| Edit action | MANAGE+ can reassign |
| Escalation order | Visual indicator of notification order |

### 2.8 Tab: History

| Element | Content |
|---------|---------|
| Audit events | AuditEventRow list for this machine: status changes, sparepart installs, responsibility changes, threshold edits |
| Filters | Action type, date range |

### 2.9 Responsive Behavior

| Viewport | Layout |
|----------|--------|
| Desktop | 2-column overview grid, full tabs |
| Tablet | Single column overview, full tabs |
| Mobile | Stacked sections, tabs become accordion or scrollable tab bar |

### 2.10 States

| State | Behavior |
|-------|----------|
| Loading | Skeleton header + skeleton tab content |
| Machine not found | "Machine not found or you don't have access." with back link |
| No telemetry | Telemetry section shows: "No telemetry received. Verify MQTT connectivity." |
| No spareparts installed | "No spareparts installed on this machine." with install action |
| No responsibility | "No responsibility chain assigned." with assign action |
| Inactive machine | Header shows INACTIVE badge prominently; telemetry tab shows: "Telemetry rejected — machine is inactive." |

---

## 3. Alert Detail

**Route:** `/alerts/[alertId]`
**Purpose:** Show why an alert fired, who was notified, and enable safe acknowledgement/resolution.
**Users:** All roles (actions restricted by permission).
**Mobile-first design — this is where field technicians land from WAHA links.**

### 3.1 Layout

```
┌─────────────────────────────────────────────────────┐
│ Header: "Alert" + StatusBadge (OPEN/ACK/RESOLVED)   │
├─────────────────────────────────────────────────────┤
│ SECTION: What Happened                              │
│ Machine: BF-08410 / JBF19 (link to Machine Detail)  │
│ Sparepart: Electric PLC Wecon LX5                   │
│ Threshold: 90% reached                              │
│ Created: 2026-05-25 09:12:00 UTC                    │
├─────────────────────────────────────────────────────┤
│ SECTION: Threshold Evidence                         │
│ ┌─────────────────────────────────────────────────┐ │
│ │ LifetimeProgress (full evidence display)        │ │
│ │ Baseline: 10,000 | Current: 19,200             │ │
│ │ Consumed: 9,200 | Expected: 10,000             │ │
│ │ Consumed: 92% | Threshold: 90%                 │ │
│ └─────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│ SECTION: Escalation Timeline                        │
│ ┌─────────────────────────────────────────────────┐ │
│ │ EscalationTimeline                              │ │
│ │ ● TECHNICIAN: Ahmad — Sent 09:12               │ │
│ │ ● STAFF: Budi — Sent 09:27                     │ │
│ │ ○ LEADER: Citra — Pending (09:42)              │ │
│ └─────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│ SECTION: Action Panel                               │
│ ┌─────────────────────────────────────────────────┐ │
│ │ AlertActionPanel                                │ │
│ │ [Acknowledge] — consequence text visible        │ │
│ └─────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────┘
```

### 3.2 Section: What Happened

| Field | Content | Interaction |
|-------|---------|-------------|
| Machine | Code + name | Link to Machine Detail |
| Plant / Group | GM1 / Forming | Context only |
| Sparepart | Name from installation | — |
| Threshold | "{consumed}% reached (threshold: {threshold}%)" | — |
| Created | Absolute timestamp UTC | — |
| Alert ID | Small, copyable | For support reference |

### 3.3 Section: Threshold Evidence

Full `LifetimeProgress` component showing:
- Baseline counter value
- Current counter value
- Consumed production count (current - baseline, with wrap handling)
- Expected production count
- Consumed percentage
- Threshold percentage
- Visual progress bar

**Trust principle:** User must be able to verify the math. Show all numbers, not just the percentage.

### 3.4 Section: Escalation Timeline

`EscalationTimeline` component showing:
- Each responsibility level in escalation order
- Recipient name and phone (masked: `+62***890`)
- Status per level: Sent (with timestamp), Queued, Failed (with error), Pending (with scheduled time), Stopped, Rate-limited
- If acknowledged: "Escalation stopped" marker after the acknowledging level

**Mobile:** Show latest event first, expandable for full timeline.

### 3.5 Section: Action Panel

`AlertActionPanel` behavior by alert status:

| Alert Status | Available Actions | Consequence Text |
|--------------|-------------------|------------------|
| OPEN | Acknowledge (permitted users), Resolve (SUPER_ADMIN only) | "Stops further escalation and records your action." |
| ACKNOWLEDGED | Resolve (permitted users) | "Marks maintenance follow-up complete. History remains available." |
| RESOLVED | None (read-only) | "This alert was resolved on {date} by {actor}." |

**Confirmation flow:**
1. User taps action button.
2. AlertDialog appears with consequence text + confirm/cancel.
3. On confirm: loading state → success toast → status updates inline.
4. On failure: error toast with retry option.

**Permission denied state:** Button disabled with tooltip: "You don't have permission to {action} this alert."

### 3.6 Responsive Behavior

| Viewport | Layout |
|----------|--------|
| Desktop | Single column, comfortable spacing, all sections visible |
| Tablet | Same as desktop |
| Mobile | Stacked sections, **AlertActionPanel becomes sticky bottom bar** with primary action always visible without scrolling |

### 3.7 States

| State | Behavior |
|-------|----------|
| Loading | Skeleton for each section |
| Alert not found | "Alert not found or you don't have access." |
| Already acknowledged | Show who acknowledged and when; action panel shows Resolve only |
| Already resolved | Full read-only view with resolution evidence |
| WAHA delivery failed | EscalationTimeline shows failed step with error detail |
| Rate-limited | EscalationTimeline shows rate-limited step with next send window |
| Permission denied | Action buttons disabled with explanation |

### 3.8 Microcopy

- Acknowledge button: "Acknowledge"
- Acknowledge consequence: "Stops further escalation and records your action. Alert remains visible until resolved."
- Resolve button: "Resolve"
- Resolve consequence: "Marks maintenance follow-up complete. Resolved alerts cannot be acknowledged again."
- SUPER_ADMIN override: "Resolve (Admin Override)"
- Override consequence: "Resolves this alert without prior acknowledgement. This is an administrative action."
- Confirmation dialog title: "Confirm {Action}"
- Confirmation dialog cancel: "Cancel"
- Success toast: "Alert {action}d successfully."
- Error toast: "Failed to {action} alert. Please try again."

---

## 4. System Health

**Route:** `/system-health`
**Purpose:** Help SUPER_ADMIN identify which dependency, worker, or data quality issue is causing operational problems.
**Users:** SUPER_ADMIN only.

### 4.1 Layout

```
┌─────────────────────────────────────────────────────┐
│ Header: "System Health" + LatencyIndicator          │
│ Last checked: 2026-05-25 09:15:00 UTC [Refresh]    │
├─────────────────────────────────────────────────────┤
│ SECTION: Dependencies                               │
│ ┌──────────┐ ┌──────────┐ ┌──────────┐            │
│ │PostgreSQL│ │InfluxDB  │ │  Redis   │            │
│ │HealthCard│ │HealthCard│ │HealthCard│            │
│ └──────────┘ └──────────┘ └──────────┘            │
│ ┌──────────┐ ┌──────────┐                          │
│ │  EMQX    │ │  WAHA    │                          │
│ │HealthCard│ │HealthCard│                          │
│ └──────────┘ └──────────┘                          │
├─────────────────────────────────────────────────────┤
│ SECTION: Workers                                    │
│ ┌──────────────────────┐ ┌──────────────────────┐  │
│ │ Telemetry Ingest     │ │ Notification Worker  │  │
│ │ HealthCard + metrics │ │ HealthCard + metrics │  │
│ └──────────────────────┘ └──────────────────────┘  │
├─────────────────────────────────────────────────────┤
│ SECTION: Data Quality                               │
│ ┌─────────────────────────────────────────────────┐ │
│ │ DataQualityPanel                                │ │
│ │ Quarantine: 3 | Rejection: 0.2% | Anomaly: 0   │ │
│ │ Dead-letter: 0 | Window: last 1h               │ │
│ │ [View Quarantine Log]                           │ │
│ └─────────────────────────────────────────────────┘ │
├─────────────────────────────────────────────────────┤
│ SECTION: Telemetry Freshness                        │
│ Latest received: 2026-05-25 09:14:58 UTC (2s ago)  │
│ Machines with stale telemetry: 0                    │
├─────────────────────────────────────────────────────┤
│ SECTION: Database Replication                       │
│ Primary: Connected | Replica: Connected             │
│ Replication lag: 12ms                               │
├─────────────────────────────────────────────────────┤
│ SECTION: Queue Status                               │
│ Telemetry queue depth: 0                            │
│ Failed writes: 0 | Dead-letter: 0                   │
│ Notification queue: 2 pending                       │
└─────────────────────────────────────────────────────┘
```

### 4.2 Section: Dependencies

One `HealthCard` per external dependency:

| Dependency | Health Check | Healthy Label | Failed Label |
|------------|-------------|---------------|--------------|
| PostgreSQL | Connection + query | "Connected" | "Connection failed: {error}" |
| InfluxDB | HTTP ping + write test | "Connected" | "Unreachable: {error}" |
| Redis | PING command | "Connected" | "Connection refused: {error}" |
| EMQX | MQTT connection status | "Connected" | "Broker unreachable: {error}" |
| WAHA | HTTP health endpoint | "Available" | "Unavailable: {error}" |

Each card shows: name, StatusBadge, last checked timestamp, last error (if any).

### 4.3 Section: Workers

| Worker | Metrics Shown |
|--------|---------------|
| Telemetry Ingest | Status (running/stopped), messages processed (last 1m), processing lag, last message timestamp |
| Notification Worker | Status (running/stopped), jobs processed (last 1h), pending jobs, last send timestamp |

### 4.4 Section: Data Quality

`DataQualityPanel` showing:

| Metric | Description | Alert Threshold |
|--------|-------------|-----------------|
| Quarantine count | Messages rejected in time window | > 10 → warning, > 50 → critical |
| Rejection rate % | Rejected / total received × 100 | > 1% → warning, > 5% → critical |
| Anomaly count | Values outside plausible range | > 0 → warning |
| Dead-letter count | Messages that exhausted retries | > 0 → critical |
| Time window | Period for metrics (default: last 1 hour) | — |

"View Quarantine Log" link → opens `QuarantineLogTable` (inline expandable or separate sub-page).

### 4.5 Section: Telemetry Freshness

| Field | Content |
|-------|---------|
| Latest received | Absolute timestamp + relative ("2s ago") |
| Stale machines | Count of active machines with lastSeen > 5 minutes |
| Stale machine list | Expandable list: machine code, lastSeen, duration stale |

### 4.6 Section: Database Replication

| Field | Content |
|-------|---------|
| Primary status | Connected / Failed |
| Replica status | Connected / Failed / Not configured |
| Replication lag | Milliseconds (normal < 100ms, warning 100-1000ms, critical > 1000ms) |

### 4.7 Section: Queue Status

| Field | Content |
|-------|---------|
| Telemetry queue depth | Current items waiting to be processed |
| Failed telemetry writes | Count in time window |
| Dead-letter (telemetry) | Messages that exhausted retries |
| Notification queue | Pending notification jobs |
| Circuit breaker state | WAHA circuit: closed/open/half-open |

### 4.8 Quarantine Log (Sub-view)

Accessible from DataQualityPanel "View Quarantine Log" link.

`QuarantineLogTable` showing:

| Column | Content |
|--------|---------|
| Received | Timestamp |
| Topic | MQTT topic string |
| Reason | Rejection reason (human-readable) |
| Schema | Schema version (if present) |
| Correlation ID | Copyable, monospace |
| Expand | Full raw payload JSON |

Filters: reason type, date range, machine code (from topic).
Sort: newest first (default).
Pagination: 25 per page.

### 4.9 Responsive Behavior

| Viewport | Layout |
|----------|--------|
| Desktop | 3-col dependency grid, 2-col worker grid, full data quality panel, full tables |
| Tablet | 2-col grids, full panels |
| Mobile | Stacked cards, data quality as summary with link to detail, quarantine log as simplified list |

### 4.10 States

| State | Behavior |
|-------|----------|
| Loading | Skeleton cards for each section |
| All healthy | Green summary banner: "All systems operational" + individual cards still visible |
| Partial failure | Failed cards show critical styling, healthy cards remain green |
| Health check error | Card shows: "Unable to check {dependency}. Last known: {status} at {time}." |
| No telemetry ever | Freshness section: "No telemetry received. Verify MQTT configuration and machine setup." |
| Non-SUPER_ADMIN access | Redirect to Operations Overview (route guard) |

### 4.11 Microcopy

- Page title: "System Health"
- All healthy banner: "All systems operational. Last checked {time}."
- Dependency healthy: "Connected" / "Available"
- Dependency failed: "{Error type}: {brief message}"
- Worker running: "Running — {count} processed in last {window}"
- Worker stopped: "Stopped — last active {time}"
- Stale telemetry: "{count} machines with stale telemetry (>5 min)"
- Replication lag warning: "Replication lag elevated: {ms}ms"
- Circuit breaker open: "WAHA circuit open — notifications paused until recovery"
- Quarantine elevated: "Quarantine rate elevated — review rejected messages"
- Refresh button: "Refresh"
- Last checked: "Last checked: {absolute time}"

### 4.12 Data Refresh

- Auto-refresh every 30 seconds.
- Manual refresh button in header.
- Individual section refresh on error (retry per section).

---

## 5. Cross-Screen Patterns

### 5.1 Navigation Between Screens

| From | To | Trigger |
|------|-----|---------|
| Operations Overview | Alert Detail | Click alert row |
| Operations Overview | Machine Detail | Click telemetry card |
| Operations Overview | System Health | Click health summary |
| Machine Detail | Alert Detail | Click alert in Alerts tab |
| Alert Detail | Machine Detail | Click machine link in header |
| System Health | Machine Detail | Click stale machine in freshness list |
| WAHA notification (external) | Alert Detail | Deep link from WhatsApp message |

### 5.2 Timestamp Display Rules

| Context | Format | Example |
|---------|--------|---------|
| Audit/evidence | Absolute UTC | "2026-05-25 09:12:00 UTC" |
| Operational freshness | Relative | "2s ago", "5m ago", "3h ago" |
| Escalation scheduled | Absolute + relative | "09:42 (in 15m)" |
| Table columns | Absolute, compact | "May 25, 09:12" |
| Tooltips | Full ISO | "2026-05-25T09:12:00.000Z" |

### 5.3 Error Handling Pattern

All API errors follow this UX pattern:

1. **Inline error** for section-level failures (one section fails, others still show).
2. **Full-page error** only when the primary resource fails (e.g., alert not found).
3. **Toast** for action failures (acknowledge failed, resolve failed).
4. **Retry button** on all error states.
5. **Never show raw error codes** to non-SUPER_ADMIN users. Show: "Something went wrong. Please try again."
6. **SUPER_ADMIN** may see technical detail: error code, traceId, timestamp.

### 5.4 Loading Pattern

- Use skeleton components matching the expected content shape.
- Never show empty state while loading (skeleton prevents layout shift).
- Show content progressively as sections load (don't wait for all).
- Minimum skeleton display: 200ms (prevent flash for fast responses).

### 5.5 Permission-Denied Pattern

| Scenario | Behavior |
|----------|----------|
| Route not allowed | Redirect to Operations Overview |
| Action not allowed | Button disabled + tooltip with reason |
| Data not in scope | Empty state: "No data available for your plant assignment." |
| Resource outside scope | "You don't have access to this resource." with back link |
