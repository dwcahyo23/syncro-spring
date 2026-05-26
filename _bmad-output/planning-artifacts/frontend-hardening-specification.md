---
title: Syncro Frontend Hardening Specification
status: final
created: 2026-05-25
boilerplate: arhamkhnz/next-shadcn-admin-dashboard v2.2.0
inputDocuments:
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/architecture.md
---

# Syncro Frontend Hardening Specification

This document bridges the UX Design Specification and the boilerplate implementation. It defines exactly what to keep, strip, adapt, and build when initializing the Syncro frontend from `arhamkhnz/next-shadcn-admin-dashboard`.

## 1. Boilerplate Inventory

### What Exists (Keep)

| Category | Assets | Action |
|----------|--------|--------|
| Shell | Sidebar (collapsible/inset/floating), sticky header, layout provider | Keep as-is |
| Theme | oklch() CSS variables, 4 presets, dark mode, next-themes | Keep system, add Syncro tokens |
| Preferences | Zustand store, cookie persistence, server-side layout read | Keep as-is |
| shadcn/ui | 55 components installed | Keep all |
| Forms | React Hook Form + Zod + shadcn Form | Keep as-is |
| Tables | TanStack Table + shadcn Table | Keep as-is |
| Charts | Recharts via shadcn Chart | Keep available, use sparingly |
| Search | Command palette (cmdk) | Keep, wire to Syncro search |
| Responsive | `use-mobile.ts` hook, sidebar collapse | Keep as-is |
| Tooling | Biome (lint/format), Husky (hooks), TypeScript strict | Keep as-is |
| Fonts | Geist via `geist` package | Keep as default |

### What to Strip (Remove)

| Content | Path | Reason |
|---------|------|--------|
| Demo dashboards | `dashboard/default`, `crm`, `finance`, `analytics`, `productivity`, `ecommerce`, `academy`, `logistics` | Not Syncro domain |
| Mail app | `mail/` | Not needed |
| Demo users page | `dashboard/users/` | Syncro builds own user/role management |
| Demo data files | `src/data/` | Replace with Syncro API calls |
| Legacy dashboards | `dashboard/(legacy)/` | Outdated patterns |
| Coming soon page | `dashboard/coming-soon/` | Not needed |
| External page | `(external)/` | Not needed |
| Support card | `sidebar-support-card.tsx` | Not Syncro branding |

### What to Adapt

| Component | Current | Syncro Adaptation |
|-----------|---------|-------------------|
| Sidebar nav items | Demo modules | Syncro 8-item navigation |
| Account switcher | Multi-account switching | `PlantScopeSelector` for plant filtering |
| Auth screens | Login v1/v2, Register v1/v2 | Pick one login variant, wire to Spring Boot |
| App config | Demo app name/branding | Syncro name, description |
| Search dialog | Generic search | Machine/plant/alert search |

## 2. Syncro Semantic Design Tokens

Add to `src/app/globals.css` after existing theme variables:

```css
/* ============================================
   SYNCRO SEMANTIC STATUS TOKENS
   ============================================
   These tokens provide industrial operational
   status semantics on top of the boilerplate
   theme system. They work across all presets
   and light/dark modes.
   ============================================ */

:root {
  /* Healthy / Live / Success */
  --syncro-status-healthy: oklch(0.65 0.15 145);
  --syncro-status-healthy-bg: oklch(0.95 0.03 145);
  --syncro-status-healthy-border: oklch(0.80 0.08 145);

  /* Warning / Stale / Approaching Threshold */
  --syncro-status-warning: oklch(0.70 0.15 85);
  --syncro-status-warning-bg: oklch(0.95 0.03 85);
  --syncro-status-warning-border: oklch(0.82 0.08 85);

  /* Critical / Failed / Threshold Reached */
  --syncro-status-critical: oklch(0.55 0.20 25);
  --syncro-status-critical-bg: oklch(0.95 0.04 25);
  --syncro-status-critical-border: oklch(0.75 0.10 25);

  /* Informational / Configured / Pending */
  --syncro-status-info: oklch(0.55 0.15 250);
  --syncro-status-info-bg: oklch(0.95 0.03 250);
  --syncro-status-info-border: oklch(0.78 0.08 250);

  /* Inactive / Unknown / Disabled */
  --syncro-status-neutral: oklch(0.55 0.01 0);
  --syncro-status-neutral-bg: oklch(0.93 0.005 0);
  --syncro-status-neutral-border: oklch(0.80 0.005 0);
}

.dark {
  --syncro-status-healthy: oklch(0.75 0.15 145);
  --syncro-status-healthy-bg: oklch(0.25 0.04 145);
  --syncro-status-healthy-border: oklch(0.40 0.08 145);

  --syncro-status-warning: oklch(0.80 0.15 85);
  --syncro-status-warning-bg: oklch(0.25 0.04 85);
  --syncro-status-warning-border: oklch(0.42 0.08 85);

  --syncro-status-critical: oklch(0.70 0.20 25);
  --syncro-status-critical-bg: oklch(0.25 0.05 25);
  --syncro-status-critical-border: oklch(0.40 0.10 25);

  --syncro-status-info: oklch(0.70 0.15 250);
  --syncro-status-info-bg: oklch(0.25 0.04 250);
  --syncro-status-info-border: oklch(0.40 0.08 250);

  --syncro-status-neutral: oklch(0.65 0.01 0);
  --syncro-status-neutral-bg: oklch(0.25 0.005 0);
  --syncro-status-neutral-border: oklch(0.38 0.005 0);
}
```

### Tailwind Utility Classes

Add to `tailwind.config.ts` or use inline with CSS variables:

```typescript
// No tailwind.config.ts extension needed — use CSS variables directly:
// className="bg-[var(--syncro-status-healthy-bg)] text-[var(--syncro-status-healthy)]"
// Or create a Tailwind plugin if team prefers named utilities.
```

### Typography Additions

```css
/* Tabular numbers for telemetry values */
.font-tabular {
  font-variant-numeric: tabular-nums;
}

/* Monospace for technical values (correlationId, messageId) */
.font-mono-tight {
  font-family: var(--font-mono, ui-monospace, monospace);
  font-size: 0.8125rem;
  letter-spacing: -0.01em;
}
```

## 3. Sidebar Navigation Configuration

Replace `src/navigation/sidebar/sidebar-items.ts` with:

```typescript
export const sidebarItems = [
  {
    title: "Operations Overview",
    url: "/operations-overview",
    icon: "activity",
  },
  {
    title: "Telemetry",
    url: "/telemetry",
    icon: "radio",
  },
  {
    title: "Alerts",
    url: "/alerts",
    icon: "bell",
    badge: "dynamic", // show open alert count
  },
  {
    title: "Master Data",
    url: "/master-data",
    icon: "database",
    children: [
      { title: "Plants", url: "/master-data/plants" },
      { title: "Machine Groups", url: "/master-data/machine-groups" },
      { title: "Machines", url: "/master-data/machines" },
      { title: "Spareparts", url: "/master-data/spareparts" },
      { title: "Installations", url: "/master-data/installations" },
      { title: "Responsibility", url: "/master-data/responsibility" },
    ],
  },
  {
    title: "WAHA Templates",
    url: "/waha-templates",
    icon: "message-square",
  },
  {
    title: "Audit Log",
    url: "/audit-log",
    icon: "file-text",
  },
  {
    title: "System Health",
    url: "/system-health",
    icon: "heart-pulse",
    // SUPER_ADMIN only — hide for other roles
    roles: ["SUPER_ADMIN"],
  },
  {
    title: "Settings",
    url: "/settings",
    icon: "settings",
  },
];
```

## 4. Component Build Specifications

### 4.1 StatusBadge (Foundation — Build First)

```typescript
// src/components/syncro/status-badge.tsx

type StatusVariant = "healthy" | "warning" | "critical" | "info" | "neutral";

interface StatusBadgeProps {
  variant: StatusVariant;
  label: string;
  icon?: React.ReactNode;
  timestamp?: string;
  pulse?: boolean; // animated dot for "live" state
}
```

**Built from:** shadcn Badge + Syncro CSS variables + optional icon + label text.

**Accessibility:** Label is always visible text. Color is supplementary, never sole indicator.

**Mapping:**

| Operational State | Variant | Label Examples |
|-------------------|---------|---------------|
| Live, Running, Delivered, Connected | `healthy` | "Live", "Running", "Sent" |
| Stale, Approaching, Queued | `warning` | "Stale", "85%", "Queued" |
| Failed, Threshold Reached, Critical | `critical` | "Failed", "Threshold", "Down" |
| Configured, Pending, Open | `info` | "Open", "Pending", "Configured" |
| Inactive, Unknown, Disabled | `neutral` | "Inactive", "Unknown", "N/A" |

### 4.2 TelemetryCard

```typescript
// src/components/syncro/telemetry-card.tsx

interface TelemetryCardProps {
  machineCode: string;
  machineName?: string;
  running: boolean;
  counting: number;
  runtimeHours: number;
  lastSeen: string; // ISO timestamp
  freshnessStatus: StatusVariant;
  latencyMs?: number;
  onClick?: () => void;
}

// States: live, stale, inactive, loading, error, empty
```

**Built from:** Card + StatusBadge + `.font-tabular` for numbers + timestamp.

### 4.3 LifetimeProgress

```typescript
// src/components/syncro/lifetime-progress.tsx

interface LifetimeProgressProps {
  sparepartName: string;
  baselineCount: number;
  currentCount: number;
  consumedCount: number;
  expectedCount: number;
  thresholdPercent: number;
  consumedPercent: number;
  status: StatusVariant;
}
```

**Built from:** Progress + Card + numeric evidence grid.

**Display:** Progress bar + numeric breakdown (baseline / current / consumed / expected / threshold).

### 4.4 AlertActionPanel

```typescript
// src/components/syncro/alert-action-panel.tsx

interface AlertActionPanelProps {
  alertStatus: "OPEN" | "ACKNOWLEDGED" | "RESOLVED";
  allowedActions: string[];
  onAcknowledge?: () => void;
  onResolve?: () => void;
  consequenceText: string;
  isLoading?: boolean;
  disabledReason?: string;
}
```

**Built from:** Card + Button + AlertDialog (for confirmation) + consequence text.

**Mobile:** Sticky bottom action area.

### 4.5 EscalationTimeline

```typescript
// src/components/syncro/escalation-timeline.tsx

interface EscalationStep {
  level: string; // TECHNICIAN, STAFF, LEADER, SPV, MANAGER
  recipient: string;
  status: "sent" | "queued" | "failed" | "pending" | "stopped" | "rate-limited";
  timestamp?: string;
  deliveryResult?: string;
  nextSendAt?: string;
}

interface EscalationTimelineProps {
  steps: EscalationStep[];
  expandedByDefault?: boolean; // mobile: false, desktop: true
}
```

**Built from:** Custom vertical timeline with status nodes. Use Tailwind `border-l` + positioned dots + StatusBadge per step.

### 4.6 HealthCard

```typescript
// src/components/syncro/health-card.tsx

interface HealthCardProps {
  name: string; // "PostgreSQL", "Redis", "EMQX", etc.
  status: StatusVariant;
  lastChecked: string;
  lastError?: string;
  impactedArea?: string;
  onClick?: () => void;
}
```

**Built from:** Card + StatusBadge + timestamp + error text.

### 4.7 DataQualityPanel

```typescript
// src/components/syncro/data-quality-panel.tsx

interface DataQualityPanelProps {
  quarantineCount: number;
  rejectionRatePercent: number;
  anomalyCount: number;
  deadLetterCount: number;
  timeWindow: string;
  status: StatusVariant; // normal, elevated, critical
  onViewQuarantine?: () => void;
}
```

**Built from:** Card + metric grid (4 values) + StatusBadge + link.

### 4.8 QuarantineLogTable

```typescript
// src/components/syncro/quarantine-log-table.tsx

interface QuarantineEntry {
  receivedAt: string;
  topic: string;
  payloadSnippet: string;
  rejectionReason: string;
  schemaVersion?: string;
  correlationId: string;
  fullPayload: string; // shown on expand
}
```

**Built from:** TanStack Table + Collapsible rows + `.font-mono-tight` for correlationId.

### 4.9 LatencyIndicator

```typescript
// src/components/syncro/latency-indicator.tsx

interface LatencyIndicatorProps {
  latencyMs: number | null;
  status: "normal" | "elevated" | "critical" | "unavailable";
}

// Thresholds: normal <5000ms, elevated 5000-15000ms, critical >15000ms
```

**Built from:** Small inline badge with numeric value + StatusBadge variant.

### 4.10 AuditLogTable

```typescript
// src/components/syncro/audit-log-table.tsx

interface AuditEntry {
  timestamp: string;
  actor: string;
  action: "create" | "update" | "delete";
  entityType: string;
  entityId: string;
  previousValue?: Record<string, unknown>;
  newValue?: Record<string, unknown>;
}

// Filters: entityType, actor, plant, dateRange
```

**Built from:** TanStack Table + Collapsible rows (before/after JSON diff) + filter bar.

### 4.11 PlantScopeSelector

```typescript
// src/components/syncro/plant-scope-selector.tsx

interface PlantScopeSelectorProps {
  assignedPlants: { id: string; code: string; name: string }[];
  activePlantId: string | "all";
  onSelect: (plantId: string | "all") => void;
  isSuperAdmin: boolean;
}
```

**Built from:** Adapt `account-switcher.tsx` pattern — dropdown in sidebar header area.

### 4.12 SetupCompletenessChecklist

```typescript
// src/components/syncro/setup-completeness-checklist.tsx

interface SetupStep {
  label: string; // "Plant", "Machine Group", "Machine", etc.
  status: "complete" | "incomplete" | "blocked";
  nextAction?: string;
  href?: string;
}
```

**Built from:** Ordered list + StatusBadge per item + link to next action.

### 4.13 MachineSummaryCard

```typescript
// src/components/syncro/machine-summary-card.tsx

interface MachineSummaryCardProps {
  plantCode: string;
  machineGroup: string;
  machineCode: string;
  machineName?: string;
  status: "ACTIVE" | "INACTIVE";
  telemetryFreshness: StatusVariant;
  openAlertCount: number;
  onClick?: () => void;
}
```

**Built from:** Card + StatusBadge + compact text layout.

### 4.14 WahaTemplateEditor

```typescript
// src/components/syncro/waha-template-editor.tsx

interface WahaTemplateEditorProps {
  value: string;
  onChange: (value: string) => void;
  availableVariables: string[];
  // Variables: {machineCode}, {machineName}, {plantCode}, {machineGroup},
  //            {sparepartName}, {thresholdPercent}, {currentCount}, {alertTime}
  preview?: string; // rendered preview from backend
  onSave?: () => void;
}
```

**Built from:** Textarea + DropdownMenu (variable picker) + preview Card.

### 4.15 AuditEventRow

```typescript
// src/components/syncro/audit-event-row.tsx

interface AuditEventRowProps {
  actor: string;
  action: string;
  target: string;
  timestamp: string;
  result?: string;
  source?: string;
}
```

**Built from:** Table row or inline flex layout with timestamp.

## 5. Route Structure After Initialization

```text
src/app/
├── (auth)/
│   └── login/
│       └── page.tsx
├── (dashboard)/
│   ├── layout.tsx              # Sidebar + header shell
│   ├── operations-overview/
│   │   └── page.tsx
│   ├── telemetry/
│   │   └── page.tsx
│   ├── alerts/
│   │   ├── page.tsx            # Alert list
│   │   └── [alertId]/
│   │       └── page.tsx        # Alert detail
│   ├── master-data/
│   │   ├── plants/
│   │   ├── machine-groups/
│   │   ├── machines/
│   │   │   ├── page.tsx        # Machine list
│   │   │   └── [machineId]/
│   │   │       └── page.tsx    # Machine detail (hub)
│   │   ├── spareparts/
│   │   ├── installations/
│   │   └── responsibility/
│   ├── waha-templates/
│   │   └── page.tsx
│   ├── audit-log/
│   │   └── page.tsx
│   ├── system-health/
│   │   └── page.tsx
│   └── settings/
│       └── page.tsx
└── not-found.tsx
```

## 6. Density & Responsive Rules

| Context | Desktop | Tablet | Mobile |
|---------|---------|--------|--------|
| Management tables | Dense: `text-sm py-1.5 px-3` | Cards or compact table | Stacked cards |
| Operational cards | `p-4` grid | `p-4` 2-col | `p-4` stacked |
| Alert action | Inline button | Inline button | Sticky bottom `min-h-[44px]` |
| Machine Detail | Tabs + side panels | Tabs stacked | Sections stacked |
| Health dashboard | Grid of HealthCards | 2-col grid | Stacked |
| Telemetry values | `.font-tabular` inline | Same | Same |

## 7. Accessibility Checklist

| Requirement | Implementation |
|-------------|---------------|
| No color-only status | StatusBadge always has text label |
| Focus indicators | Keep boilerplate focus-visible styles |
| Touch targets | Mobile actions `min-h-[44px] min-w-[44px]` |
| Keyboard navigation | shadcn/ui handles via Radix primitives |
| Dialog focus trap | AlertDialog handles automatically |
| Form labels | React Hook Form + shadcn Field component |
| Screen reader | StatusBadge uses `aria-label` with full status text |
| Contrast | oklch tokens tuned for WCAG AA (4.5:1 text, 3:1 UI) |

## 8. Implementation Sequence

| Order | What | When | Depends On |
|-------|------|------|------------|
| 1 | Strip demo content, set up route structure | Story 1.4 | — |
| 2 | Add Syncro semantic tokens to globals.css | Story 1.4 | — |
| 3 | Replace sidebar navigation | Story 1.4 | — |
| 4 | Build `StatusBadge` | Story 1.4 or first UX story | Tokens |
| 5 | Build `PlantScopeSelector` | Story 1.7 | StatusBadge |
| 6 | Build `SetupCompletenessChecklist` | Story 2.8 | StatusBadge |
| 7 | Build `MachineSummaryCard` | Story 3.7 | StatusBadge |
| 8 | Build `TelemetryCard` + `LatencyIndicator` | Story 3.7 | StatusBadge, tokens |
| 9 | Build `LifetimeProgress` | Story 4.3 | Progress, tokens |
| 10 | Build `AlertActionPanel` | Story 4.4 | AlertDialog |
| 11 | Build `EscalationTimeline` | Story 5.6 | StatusBadge |
| 12 | Build `WahaTemplateEditor` | Story 5.1 | Textarea, Dropdown |
| 13 | Build `HealthCard` + `DataQualityPanel` | Story 6.4 | StatusBadge |
| 14 | Build `QuarantineLogTable` | Story 6.7 | Table, Collapsible |
| 15 | Build `AuditLogTable` + `AuditEventRow` | Story 2.9 | Table, Collapsible |

## 9. Zero New Dependencies

All 15 domain components can be built from:

- shadcn/ui primitives (55 components already installed)
- Tailwind CSS utilities
- Syncro CSS variables (added to globals.css)
- React (composition, props, state)
- Existing stack: Zustand, React Hook Form, Zod, TanStack Table

No additional npm packages required for Phase 1 frontend.

## 10. Server State Decision from Epic 2

Starting with Epic 2 data APIs, Syncro uses TanStack Query for client-side server state. Keep Zustand limited to UI/session shell state such as active plant scope, layout preferences, and transient interaction state.

Epic 2 also introduces Orval-generated TypeScript client functions and TanStack Query hooks from the Springdoc OpenAPI contract. Generated files must stay isolated from handwritten feature components and should be wrapped only when a feature needs Syncro-specific composition.

TanStack Query conventions:

- All query and mutation functions come from Orval-generated clients or call the typed API boundary under `src/lib/api`; components must not parse raw `fetch` responses.
- Query keys must be stable arrays and include active plant scope for plant-scoped data, for example `["machines", { plantScope: activePlantId, page, filters }]`.
- Mutations must define explicit invalidation behavior for affected query keys.
- Loading, empty, error, forbidden, stale, and success states remain product behavior and must be rendered by feature components.
- Do not use TanStack Query as authorization source of truth; backend API decisions remain authoritative.

Backend validation contract for Epic 2 forms:

- Every create/update endpoint must expose request DTO validation through the OpenAPI contract generated from Springdoc.
- Frontend forms must mirror backend required/length/format/range constraints for guidance, but backend validation remains authoritative.
- Standard validation and malformed JSON/type mismatch errors must map to reachable field/global error UI, not generic success/empty states.
- Frontend tests or manual evidence must cover blank, too-long, invalid format/range, forbidden, and duplicate responses for each form.

## 11. Epic 2 Form UX Guardrails

Epic 2 introduces the first real CRUD workflows. These forms must make backend validation, plant scope, role permissions, and setup dependencies understandable instead of hiding them behind generic toasts.

### 11.1 Error Mapping

- Backend `fieldErrors` map to the exact field control and move focus to the first invalid field after submit.
- Backend global errors such as duplicate, forbidden, stale scope, malformed JSON, or server failure render in a visible form-level alert above actions.
- Duplicate conflicts use specific copy, for example `Plant code already exists`, `Machine code already exists in this plant`, or `Machine group name already exists in this plant`.
- Validation failure must not clear user-entered values.
- Toasts may reinforce success/failure, but cannot be the only validation feedback.

### 11.2 Empty, Forbidden, and Dependency States

- Empty states must distinguish `no data yet`, `no plant assignment`, `forbidden by role`, `load failed`, and `prerequisite missing`.
- Forms with parent dependencies must route users to prerequisites instead of showing dead disabled forms:
  - Machine Group requires Plant.
  - Machine requires Plant and Machine Group.
  - Sparepart requires taxonomy dimensions.
  - Installation requires Machine and Sparepart.
  - Responsibility requires Machine and User.
- `SUPER_ADMIN` with no plants sees setup CTA copy; non-SUPER_ADMIN with no assignment sees admin-contact copy.
- Changing active plant scope while a form is dirty must show an unsaved-changes guard before discarding form state.

### 11.3 Selects, Dropdowns, and Pickers

- Do not use native browser `<select>` or native-looking controls for product UI unless explicitly required as accessibility fallback.
- Use polished non-native shadcn/Radix primitives for selects, dropdown menus, comboboxes, date pickers, and input pickers.
- For short controlled enums, use shadcn/Radix `Select` or segmented/radio-style controls when all options should stay visible.
- For searchable or growing datasets such as plant, machine group, machine, sparepart, user, taxonomy, or WAHA recipient selection, use Combobox pattern with `Popover` + `Command` or a dedicated picker component.
- Pickers must support keyboard navigation, visible focus, clear current value, loading state, empty state, forbidden/disabled state, and long-name truncation with full value available.
- Picker labels must show domain context, for example plant code + plant name, machine code + machine name, or user login + role/job scope.

### 11.4 Mutation Behavior

- Avoid optimistic UI for Epic 2 CRUD mutations that can fail due to permission, plant scope, duplicate constraint, or validation; prefer submit loading state followed by refetch/invalidation.
- Submit buttons disable during pending mutation and prevent double submit.
- Successful mutation shows durable feedback and refreshes affected TanStack Query keys.
- Destructive actions require confirmation with impact copy; if backend blocks delete due to child records, show exact blocking reason and next action.
- Mutation success should expose audit/evidence feedback where available, for example `Change recorded` with link or expandable audit detail once audit endpoints exist.

### 11.5 Tables and Dense Admin Workflows

- Tables must handle long industrial codes/names without layout break: truncate in cells, preserve full value via tooltip/title/detail drawer, and keep copy action where useful.
- Keyboard-heavy workflows must preserve logical tab order, Enter submit where safe, Escape closes dialogs/popovers, and focus returns to trigger after close.
- Bulk-looking actions are out of scope unless backend supports safe authorization, validation, audit, and rollback semantics.
- Mobile/tablet layouts may use stacked cards, but must preserve field labels, actions, forbidden/read-only state, and validation messages.

### 11.6 Evidence Required per Epic 2 Story

Each Epic 2 CRUD story must capture automated test or browser/manual evidence for:

- create success
- edit success
- blank/too-long/invalid field error
- duplicate conflict
- forbidden/read-only role behavior
- plant-scope restriction where applicable
- missing prerequisite state
- dirty-form navigation or plant-scope change guard
- picker keyboard interaction and empty/loading state
