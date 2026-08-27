---
title: 'Sprint Change Proposal — UI Integration & Polish'
created: '2026-08-27'
status: 'executed'
executedAt: '2026-08-27'
commit: '86b5b97'
---

> **Status: EXECUTED.** Proposals A–F landed in commit `86b5b97`. Follow-up from the 2026-08-27 live demo (user feedback) was folded in the same commit: workorder table now uses a month quick picker (no 7d/30d/90d/This-month, no calendar date clicking), gains a Category filter + Actions column (request part / report dialogs), Work Order Categories master-data tab, Preventive Categories tab, and Plants consolidated into Organization (Setup removed). Proposal G (Sparepart Requests list page) remains deferred.

# Sprint Change Proposal

**Date:** 2026-08-27
**Author:** Yusuf
**Trigger:** UI gaps identified during live demo: UUID leaks, missing nav integration, no machine/entity pickers, features isolated from dashboard shell.

---

## 1. Issue Summary

| # | Issue | Severity | Location |
|---|-------|----------|----------|
| 1 | **Routes outside dashboard shell** — `/preventive`, `/workorders/kanban`, `/workorders/ratings` live outside `(main)/dashboard` layout, so they render with **no sidebar, no header, no navigation** | High | `src/app/preventive/page.tsx`, `src/app/workorders/kanban/page.tsx`, `src/app/workorders/ratings/page.tsx` |
| 2 | **No sidebar nav items** — Preventive and Workorders not in `sidebar-items.ts` | High | `src/navigation/sidebar/sidebar-items.ts` |
| 3 | **UUID typed manually** — preventive create form has raw `<input placeholder="Machine id (UUID)">`, request-part dialog has `<Input placeholder="Machine UUID">` | High | `preventive-programs-panel.tsx`, `request-part-dialog.tsx` |
| 4 | **UUID displayed** — schedule list shows `machineId.slice(0,8)`, report shows `workOrderId` raw, workorder card shows `item.id` raw, rateable card shows `assignedTechnicianId` raw | Medium | `preventive-schedule-list.tsx`, `preventive-report.tsx`, `workorder-card.tsx`, `rateable-workorder-card.tsx` |
| 5 | **Native `<select>` instead of shadcn Select** — category/scheduleType dropdowns use native HTML select | Low | `preventive-programs-panel.tsx` |
| 6 | **No machine picker** — machine selection is a raw UUID textbox everywhere; no Select/Combobox with search+label | High | `preventive-programs-panel.tsx`, `request-part-dialog.tsx` |
| 7 | **Sparepart-requests has no page/list view** — only a dialog embedded in workorder card, no route, no list | Medium | `src/features/sparepart-requests/` |
| 8 | **No `RoleGuard` on preventive/workorder pages** — consistent with other features | Low | `src/app/preventive/page.tsx` |

---

## 2. Impact Analysis

### Epic Impact

| Epic | Status | Impact |
|------|--------|--------|
| Epic 10 (Workorder) | in-progress/done | Workorder kanban/ratings pages need move + nav + picker polish |
| Epic 11 (Preventive) | in-progress/done | Preventive page needs move + nav + picker polish |
| Epic 12 (Sparepart Request) | in-progress/done | Request dialog needs machine picker; no list view exists |

### Artifact Conflicts

- UX Design Specification requires: collapsible sidebar, validated form patterns, machine/entity labels not UUIDs, admin-dashboard shell.
- Existing `sidebar-items.ts` defines single source of truth for nav; no Preventive or Workorders entries exist.

### Technical Impact

**No backend changes needed.** All issues are frontend-only:
- Route restructuring (move files into `(main)/dashboard`)
- Nav config updates (`sidebar-items.ts`)
- Replace UUID textboxes with shadcn `Select`/`Combobox` + `useListMachines` pattern
- Replace native `<select>` with shadcn `Select`
- Add `RoleGuard` wrapper

---

## 3. Recommended Approach

**Direct Adjustment** — modify existing features within the current sprint. No rollback needed.

**Scope:** Minor-Moderate. All changes are frontend-only; no schema, API, or backend logic changes.

**Effort estimate:** 2-3 stories' worth of UI polish. Can be done as a single "deferred work bundle" or as a new story.

**Risk:** Low. The backend is unchanged; tests remain green. Frontend changes are layout/component swaps.

---

## 4. Detailed Change Proposals

### Proposal A: Move Routes Into Dashboard Shell

**Files to move:**
- `src/app/preventive/page.tsx` → `src/app/(main)/dashboard/preventive/page.tsx`
- `src/app/workorders/kanban/page.tsx` → `src/app/(main)/dashboard/workorders/kanban/page.tsx`
- `src/app/workorders/ratings/page.tsx` → `src/app/(main)/dashboard/workorders/ratings/page.tsx`

**Rationale:** All dashboard features live under `(main)/dashboard/layout.tsx` which renders the sidebar. These three orphan pages currently have no navigation.

**Action:** Create new route files, update imports, remove old files. Verify links work.

### Proposal B: Add Nav Items to Sidebar

**File:** `src/navigation/sidebar/sidebar-items.ts`

**Add under a new "Maintenance" group (or inside Master Data):**

```ts
{
  id: 20,
  label: "Maintenance",
  items: [
    { title: "Work Orders", url: "/dashboard/workorders/kanban", icon: Wrench },
    { title: "Preventive", url: "/dashboard/preventive", icon: CalendarCheck },
    { title: "Sparepart Requests", url: "/dashboard/sparepart-requests", icon: Package },
  ],
}
```

**Rationale:** UX spec says "Daily operational work appears before configuration." Workorders + Preventive are daily operational, not config.

### Proposal C: Replace UUID Textboxes with Machine Picker

**Pattern to reuse:** `src/features/master-data/installations/installation-management.tsx` lines 653-668 — `Select` with `SelectItem` keyed by `id` and labeled by `code · name · plantCode`.

**Files to fix:**
1. `preventive-programs-panel.tsx` — replace `<input placeholder="Machine id (UUID)">` with `Select` + `useListMachines` loading machine options
2. `request-part-dialog.tsx` — replace `<Input placeholder="Machine UUID">` with `Select` + machine list

**Implementation:** Create a reusable `MachineSelect` component (or use the existing `Select` + `useListMachines` inline). The `useListMachines` hook already exists in the project.

### Proposal D: Fix UUID Display Leaks

| File | Current | Fix |
|------|---------|-----|
| `preventive-schedule-list.tsx` | `machineId.slice(0, 8)` | Fetch machine code + display `code` (or remove if not needed) |
| `preventive-report.tsx` | `workOrderId` raw | Already a human-readable WO-format, ok to keep |
| `workorder-card.tsx` | `item.id` (raw UUID) | Show `item.id` as-is (workorder ids are WO-YYMM-XXXXX, not UUIDs — check if this is actually a UUID) |
| `rateable-workorder-card.tsx` | `assignedTechnicianId` raw UUID | Resolve technician name or show `id.slice(0,8)` as fallback |
| `preventive-report.tsx` | `workOrderId` font-mono | Verify it's a readable WO-format, not UUID |

**Note:** The workorder id (`item.id`) in the kanban card may already be the WO-YYMM-XXXXX format (human-readable), not a UUID. Verify before fixing.

### Proposal E: Replace Native Selects with shadcn Select

**Files:** `preventive-programs-panel.tsx` — category and scheduleType dropdowns use native `<select>`. Replace with shadcn `Select` + `SelectItem` components.

### Proposal F: Add RoleGuard to Preventive Page

**File:** `src/app/preventive/page.tsx` → wrap in `<RoleGuard>`. The existing `RoleGuard` component at `src/components/syncro/role-guard.tsx` is used by master-data pages.

### Proposal G: Create Sparepart Requests List Page (Future)

**Note:** This is a larger scope. Create a `src/app/(main)/dashboard/sparepart-requests/page.tsx` + list component + nav item. Can be deferred to a separate story.

---

## 5. Implementation Handoff

**Scope classification:** Moderate

**Order:** A → B → C → D → E → F (G deferred)

**Handoff to:** Developer agent (frontend)

**Success criteria:**
- [ ] `/preventive` and `/workorders/kanban` render inside the dashboard shell with sidebar navigation
- [ ] Sidebar shows "Maintenance" group with Work Orders, Preventive, Sparepart Requests links
- [ ] Preventive create form uses a machine `Select` (shadcn) instead of raw UUID textbox
- [ ] Request-part dialog uses a machine `Select` instead of UUID textbox
- [ ] Category/scheduleType use shadcn `Select` instead of native `<select>`
- [ ] UUID display leaks fixed (machineId slice, raw technicianId, etc.)
- [ ] `RoleGuard` wraps preventive page
- [ ] All existing tests still pass
- [ ] TypeScript clean, Biome clean

---

## 6. REVISED: Consolidated Menu + Tabs Architecture

**Prinsip (dari user):** 1 menu nav = 1 halaman dengan tabs untuk sub-bagian yang berkaitan. Jangan pecah fitur yang berkaitan ke nav terpisah. Referensi: `E:\01 DEV\SYNCRO` (pola `page.tsx` → `Tabs` → `TabsList`/`TabsTrigger`/`TabsContent`, contoh `master-data/organization/page.tsx`).

### 6a. Nav Restructure (`sidebar-items.ts`)

Usulan grouping baru — konsolidasi menu yang berkaitan ke 1 item dengan tabs:

```ts
{
  id: 10, label: "Operations",
  items: [
    { title: "Operations Overview", url: "/operations-overview" },
    { title: "Telemetry", url: "/telemetry" },
    { title: "Alerts", url: "/alerts" },
  ],
},
{
  id: 20, label: "Maintenance",
  items: [
    {
      title: "Work Orders", url: "/workorders",   // 1 menu
      // tabs di dalam: Kanban | Ratings
    },
    {
      title: "Preventive", url: "/preventive",     // 1 menu
      // tabs di dalam: Programs | Schedules | Reports
    },
    {
      title: "Sparepart Requests", url: "/sparepart-requests", // 1 menu
      // tabs di dalam: Requests (list) — dialog create tetap di workorder card
    },
  ],
},
{
  id: 30, label: "Configuration",
  items: [
    { title: "Master Data", url: "/master-data" },
    { title: "WAHA Templates", url: "/waha-templates" },
    { title: "Audit Log", url: "/audit-log" },
    { title: "System Health", url: "/system-health" },
    { title: "Settings", url: "/settings" },
  ],
}
```

**Rationale:** "Work Orders", "Preventive", "Sparepart Requests" adalah 3 domain operasional maintenance. Masing-masing jadi 1 menu. Sub-bagian berkaitan (kanban vs ratings; programs vs schedules vs reports) jadi **tabs di dalam halaman**, bukan item nav terpisah.

### 6b. Route + Tabs per Menu

| Menu | Route | Tabs (TabsTrigger) | Konten |
|------|-------|---------------------|--------|
| Work Orders | `/workorders` | Kanban \| Ratings | `KanbanBoard` \| `RatingsPageContent` |
| Preventive | `/preventive` | Programs \| Schedules | `PreventiveProgramsPanel` \| `PreventiveScheduleList` |
| Sparepart Requests | `/sparepart-requests` | Requests | list page (baru) |

Struktur:
```tsx
// src/app/(main)/dashboard/workorders/page.tsx
<Tabs defaultValue="kanban">
  <TabsList>
    <TabsTrigger value="kanban">Kanban</TabsTrigger>
    <TabsTrigger value="ratings">Ratings</TabsTrigger>
  </TabsList>
  <TabsContent value="kanban"><KanbanBoard /></TabsContent>
  <TabsContent value="ratings"><RatingsPageContent /></TabsContent>
</Tabs>
```

### 6c. Refactor Files

- Pindah ke `(main)/dashboard/`:
  - `preventive/page.tsx` → `dashboard/preventive/page.tsx` (jadi Tabs wrapper)
  - `workorders/kanban/page.tsx` + `workorders/ratings/page.tsx` → `dashboard/workorders/page.tsx` (Tabs wrapper)
  - Buat `dashboard/sparepart-requests/page.tsx` (list)
- Hapus route lama yang orphan.

### 6d. Naming/Konsistensi

- Icon per menu (Wrench untuk Work Orders, CalendarCheck untuk Preventive, Package untuk Sparepart Requests).
- Sub-bagian yang berkaitan lain (jika ada di masa depan) selalu jadi tab, bukan nav item.