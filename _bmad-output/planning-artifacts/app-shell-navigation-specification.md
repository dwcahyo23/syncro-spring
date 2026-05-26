---
title: Syncro App Shell & Navigation Specification
status: final
created: 2026-05-26
author: Freya (WDS Designer)
inputDocuments:
  - _bmad-output/planning-artifacts/ux-design-specification.md
  - _bmad-output/planning-artifacts/frontend-hardening-specification.md
  - _bmad-output/planning-artifacts/page-specifications.md
  - _bmad-output/planning-artifacts/architecture.md
currentImplementation:
  - syncro/apps/web/src/app/(main)/dashboard/layout.tsx
  - syncro/apps/web/src/app/(main)/dashboard/_components/sidebar/
  - syncro/apps/web/src/navigation/sidebar/sidebar-items.ts
  - syncro/apps/web/src/middleware.ts
  - syncro/apps/web/src/lib/auth/auth-session.ts
  - syncro/apps/web/next.config.mjs
---

# Syncro App Shell & Navigation Specification

This document specifies the complete behavior of the Syncro app shell: sidebar navigation, header bar, user session indicator, plant scope selector, role-based visibility, responsive behavior, and shell-level states. It bridges the existing boilerplate implementation with Syncro's operational requirements.

---

## 1. Routing Architecture

### 1.1 URL Strategy (Current — Preserve)

Public-facing URLs use clean paths without `/dashboard` prefix. Internally, all authenticated pages live under the `(main)/dashboard/` route group which provides the shell layout.

| Public URL | Internal Route | Purpose |
|------------|---------------|---------|
| `/operations-overview` | `/dashboard/operations-overview` | Landing page |
| `/telemetry` | `/dashboard/telemetry` | Live telemetry |
| `/alerts` | `/dashboard/alerts` | Alert management |
| `/master-data/*` | `/dashboard/master-data/*` | CRUD master data |
| `/waha-templates` | `/dashboard/waha-templates` | Notification templates |
| `/audit-log` | `/dashboard/audit-log` | Audit trail |
| `/system-health` | `/dashboard/system-health` | Platform health |
| `/settings` | `/dashboard/settings` | User/app settings |

Mechanism: `next.config.mjs` rewrites map public URLs to internal routes. `/dashboard` itself redirects to `/operations-overview`.

### 1.2 Route Protection

Middleware checks `syncro_auth_token` cookie presence. Missing token redirects to `/auth/v2/login?next=<pathname>`. This is presence-only check — token validity is verified by backend on API calls.

### 1.3 Design Decision: Why Rewrites

- Clean URLs for users and bookmarks (no `/dashboard` prefix)
- Single layout wrapper at `dashboard/layout.tsx` for all authenticated pages
- Sidebar active-state detection works against public URLs (`usePathname()` returns rewritten path)
- Middleware matcher covers both public and internal paths

---

## 2. Shell Layout Structure

### 2.1 Composition

```
┌──────────────────────────────────────────────────────────┐
│ SidebarProvider                                           │
│ ┌────────────┬───────────────────────────────────────┐   │
│ │            │ SidebarInset                           │   │
│ │  Sidebar   │ ┌─────────────────────────────────┐   │   │
│ │            │ │ Header (h-12, sticky optional)  │   │   │
│ │ ┌────────┐ │ │ [Trigger][Search] ... [Controls]│   │   │
│ │ │Header  │ │ └─────────────────────────────────┘   │   │
│ │ │(Brand) │ │ ┌─────────────────────────────────┐   │   │
│ │ ├────────┤ │ │ Main Content (p-4 md:p-6)       │   │   │
│ │ │Content │ │ │ {children}                       │   │   │
│ │ │(Nav)   │ │ │                                  │   │   │
│ │ ├────────┤ │ └─────────────────────────────────┘   │   │
│ │ │Footer  │ │                                       │   │
│ │ │(Scope) │ │                                       │   │
│ │ └────────┘ │                                       │   │
│ └────────────┴───────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────┘
```

### 2.2 Sidebar Zones

| Zone | Current Content | Syncro Adaptation |
|------|----------------|-------------------|
| Header | Brand link (Gauge icon + "Syncro") → `/operations-overview` | Keep as-is |
| Content | NavMain with Quick Create + nav items | Adapt: remove Quick Create, add role filtering, add user menu |
| Footer | Plant scope placeholder text | Replace with PlantScopeSelector component |

### 2.3 Header Bar Zones

| Zone | Position | Content |
|------|----------|---------|
| Left | Start | SidebarTrigger, Separator, SearchDialog |
| Right | End | LayoutControls, ThemeSwitcher, PlantScopeSelector (move here from placeholder) |

---

## 3. Navigation Items

### 3.1 Complete Navigation Map

| # | Title | Icon | URL | Sub-items | Roles | Badge Source |
|---|-------|------|-----|-----------|-------|-------------|
| 1 | Operations Overview | Activity | `/operations-overview` | — | ALL | — |
| 2 | Telemetry | Radio | `/telemetry` | — | ALL | Stale count |
| 3 | Alerts | Bell | `/alerts` | — | ALL | Open alert count |
| 4 | Master Data | Database | `/master-data` | Plants, Machine Groups, Machines, Spareparts, Installations, Responsibility | SUPER_ADMIN, MANAGE | — |
| 5 | WAHA Templates | MessageSquare | `/waha-templates` | — | SUPER_ADMIN | — |
| 6 | Audit Log | FileText | `/audit-log` | — | SUPER_ADMIN, MANAGE | — |
| 7 | System Health | HeartPulse | `/system-health` | — | SUPER_ADMIN | — |
| 8 | Settings | Settings | `/settings` | — | ALL | — |

### 3.2 Role Visibility Rules

| Role | Visible Items |
|------|--------------|
| SUPER_ADMIN | All 8 items |
| MANAGE | Operations Overview, Telemetry, Alerts, Master Data, Audit Log, Settings |
| VIEWER | Operations Overview, Telemetry, Alerts, Settings |

Behavior:
- Items not in user's role set are **not rendered** (hidden, not disabled)
- If user navigates directly to a hidden route URL, middleware allows (token exists) but page shows unauthorized state
- Backend enforces actual permission; frontend visibility is UX guidance only

### 3.3 Role Filtering Implementation Contract

The `NavMainItem.roles` field defines which roles can see the item. Rules:
- `roles` undefined or empty array → visible to ALL authenticated users
- `roles: ["SUPER_ADMIN"]` → visible only to SUPER_ADMIN
- `roles: ["SUPER_ADMIN", "MANAGE"]` → visible to both

The `NavMain` component must read current user's `applicationRole` from auth context and filter items before rendering.

### 3.4 Updated Navigation Config

```typescript
export const sidebarItems: NavGroup[] = [
  {
    id: 1,
    items: [
      { title: "Operations Overview", url: "/operations-overview", icon: Activity },
      { title: "Telemetry", url: "/telemetry", icon: Radio },
      { title: "Alerts", url: "/alerts", icon: Bell },
      {
        title: "Master Data",
        url: "/master-data",
        icon: Database,
        roles: ["SUPER_ADMIN", "MANAGE"],
        subItems: [
          { title: "Plants", url: "/master-data/plants" },
          { title: "Machine Groups", url: "/master-data/machine-groups" },
          { title: "Machines", url: "/master-data/machines" },
          { title: "Spareparts", url: "/master-data/spareparts" },
          { title: "Installations", url: "/master-data/installations" },
          { title: "Responsibility", url: "/master-data/responsibility" },
        ],
      },
      { title: "WAHA Templates", url: "/waha-templates", icon: MessageSquare, roles: ["SUPER_ADMIN"] },
      { title: "Audit Log", url: "/audit-log", icon: FileText, roles: ["SUPER_ADMIN", "MANAGE"] },
      { title: "System Health", url: "/system-health", icon: HeartPulse, roles: ["SUPER_ADMIN"] },
      { title: "Settings", url: "/settings", icon: Settings },
    ],
  },
];
```

---

## 4. User Session Indicator

### 4.1 Purpose

Show who is logged in, their role, and provide logout action. Replaces the unused `NavUser` boilerplate component with Syncro-specific behavior.

### 4.2 Placement

Sidebar footer, above PlantScopeSelector. When sidebar is collapsed to icon mode, show avatar only with dropdown on click.

### 4.3 Display Content

| Field | Source | Example |
|-------|--------|---------|
| Display name | `AuthUser.loginIdentifier` | "admin" |
| Role badge | `AuthUser.applicationRole` | "SUPER_ADMIN" |
| Avatar | Initials fallback (no avatar URL in Phase 1) | "AD" |

### 4.4 Dropdown Menu Items

| Item | Icon | Action |
|------|------|--------|
| Role indicator | Shield | Display only (non-interactive), shows formatted role |
| Settings | Settings | Navigate to `/settings` |
| Log out | LogOut | Clear auth cookies, redirect to `/auth/v2/login` |

Items NOT included in Phase 1 (remove from boilerplate NavUser):
- Account (no profile page yet)
- Billing (not applicable)
- Notifications (not applicable)

### 4.5 Logout Behavior

1. Call `POST /api/v1/auth/logout` (best-effort, don't block on failure)
2. Delete `syncro_auth_token` and `syncro_auth_user` cookies
3. Redirect to `/auth/v2/login`
4. Clear any client-side auth state

### 4.6 Expanded vs Collapsed States

| Sidebar State | User Indicator Display |
|---------------|----------------------|
| Expanded | Avatar + name + role badge + ellipsis trigger |
| Collapsed (icon) | Avatar only, click opens dropdown |
| Mobile (sheet) | Same as expanded |

---

## 5. Plant Scope Selector

### 5.1 Purpose

Filter dashboard data by plant. Phase 1 has single-plant assumption but the selector prepares multi-plant UX.

### 5.2 Placement

Header bar, right zone (replaces current placeholder text). NOT in sidebar footer — header gives persistent visibility without consuming sidebar space.

### 5.3 Phase 1 Behavior (Single Plant / No Plant Data)

| State | Display | Interaction |
|-------|---------|-------------|
| No plants exist | "All Plants" chip, disabled | Tooltip: "Add plants in Master Data" |
| Single plant | Plant name chip, non-interactive | No dropdown needed |
| Multiple plants | Plant name chip + chevron | Dropdown with plant list + "All Plants" option |

### 5.4 Selector Component Spec

```
┌─────────────────────────────┐
│ 🏭 [Plant Name] ▾           │  ← Trigger (compact chip style)
└─────────────────────────────┘

┌─────────────────────────────┐
│ ○ All Plants                │  ← Shows combined data
│ ● GM1 - Forming             │  ← Active selection
│ ○ GM2 - Assembly            │
└─────────────────────────────┘
```

### 5.5 Scope Propagation

- Selected plant stored in client state (Zustand or URL param — TBD per story)
- API calls include plant filter when scope is not "All Plants"
- Scope persists across navigation within session
- Scope resets to "All Plants" on login

### 5.6 Role Interaction

- SUPER_ADMIN: sees all plants
- MANAGE: sees assigned plants only (future — Phase 1 shows all)
- VIEWER: sees assigned plants only (future — Phase 1 shows all)

---

## 6. Quick Create & Inbox (Boilerplate Removal)

### 6.1 Decision: Remove

The boilerplate `NavMain` renders a "Quick Create" button and "Inbox" icon at the top of navigation. These are generic boilerplate features with no Syncro domain mapping.

### 6.2 Rationale

- Quick Create: Syncro's create actions are context-specific (create plant, create machine, etc.) — a generic quick-create doesn't map well
- Inbox: No messaging/notification inbox in Syncro frontend; WAHA notifications go to WhatsApp, not in-app

### 6.3 Action

Remove the Quick Create + Inbox `SidebarMenuItem` block from `NavMain`. The search command palette (`Cmd/Ctrl+J`) already provides quick navigation.

---

## 7. Search Command Palette

### 7.1 Current Behavior (Preserve)

- Trigger: `Cmd/Ctrl + J` or click search in header
- Builds searchable list from `sidebarItems` (titles + URLs)
- Navigates on selection via `router.push(item.url)`

### 7.2 Syncro Adaptation

- Filter search results by role (same rules as sidebar visibility)
- Include sub-items in search (already works — `sidebarItems` includes sub-items)
- Future: add entity search (machines, plants) when APIs exist

---

## 8. Shell States

### 8.1 Initial Load (Shell Skeleton)

When dashboard layout mounts but content is loading:

```
┌────────────────────────────────────────┐
│ Sidebar: Brand + nav items (static)    │
│ Header: All controls rendered          │
│ Content: Page-level loading state      │
└────────────────────────────────────────┘
```

Shell itself (sidebar + header) renders immediately from server component. Individual page content handles its own loading/suspense states.

### 8.2 Session Expired

When API call returns 401 (token expired/invalid):

| Trigger | Behavior |
|---------|----------|
| Any API 401 response | Show toast: "Session expired. Redirecting to login..." |
| After 2 second delay | Clear cookies, redirect to `/auth/v2/login?next=<current-path>` |
| User clicks toast action | Immediate redirect (skip delay) |

Implementation: centralized in API client interceptor, not per-component.

### 8.3 Unauthorized Route Access

When authenticated user navigates to a route their role cannot access:

| Trigger | Behavior |
|---------|----------|
| Direct URL navigation to restricted route | Render unauthorized page within shell |
| Display | "You don't have permission to access this page." + link to Operations Overview |
| Sidebar | Still visible, restricted item not shown in nav |

Note: This is a soft guard. Backend API will also reject unauthorized data requests.

### 8.4 Not Found (Within Shell)

When navigating to non-existent route under dashboard:

| Trigger | Behavior |
|---------|----------|
| `/some-nonexistent-path` | Caught by `dashboard/[...not-found]/page.tsx` |
| Display | "Page not found" within shell layout |
| Navigation | Sidebar remains functional |

### 8.5 Network Error (Shell-Level)

When the app cannot reach backend at all:

| Trigger | Behavior |
|---------|----------|
| Fetch fails (network error, not HTTP error) | Toast: "Unable to connect to server. Check your connection." |
| Retry | Automatic retry on next navigation or manual refresh |
| Shell | Remains functional with cached/static content |

---

## 9. Responsive Behavior

### 9.1 Breakpoints

| Breakpoint | Sidebar Behavior | Header Behavior |
|------------|-----------------|-----------------|
| Desktop (≥1024px) | Persistent, collapsible to icon mode | Full controls visible |
| Tablet (768–1023px) | Collapsed to icon mode by default | Plant selector may truncate |
| Mobile (<768px) | Hidden, opens as sheet overlay | Hamburger trigger, minimal controls |

### 9.2 Sidebar Collapse Modes (Preserve from Boilerplate)

| Mode | Behavior |
|------|----------|
| `icon` | Collapses to icon-only rail, hover/click expands items |
| `offcanvas` | Fully hidden, trigger opens as overlay |
| `none` | Always expanded (not recommended for mobile) |

User preference stored in cookie `sidebar_state` (open/closed) and Zustand store for collapse mode.

### 9.3 Mobile Sheet Behavior

- Trigger: hamburger icon (SidebarTrigger) in header
- Opens sidebar as full-height sheet from left
- Closes on: navigation, outside click, swipe left, escape key
- Shows full expanded nav including user indicator and plant selector

### 9.4 Touch Targets

All interactive sidebar items must meet 44x44px minimum touch target on mobile. Current `SidebarMenuButton` height is adequate. Sub-items in collapsed dropdown must also meet this.

---

## 10. Accessibility

### 10.1 Keyboard Navigation

| Key | Context | Action |
|-----|---------|--------|
| `Cmd/Ctrl + B` | Anywhere | Toggle sidebar open/closed |
| `Cmd/Ctrl + J` | Anywhere | Open search command palette |
| `Tab` | Sidebar | Move focus through nav items |
| `Enter/Space` | Focused nav item | Navigate or expand submenu |
| `ArrowRight` | Collapsed item with sub-items | Open submenu |
| `Escape` | Open submenu/dropdown | Close and return focus |

### 10.2 ARIA

| Element | ARIA Attribute | Value |
|---------|---------------|-------|
| Sidebar | `role` | `navigation` (via shadcn Sidebar) |
| Nav group | `role` | `group` with `aria-labelledby` |
| Active item | `aria-current` | `page` |
| Expandable item | `aria-expanded` | `true/false` |
| Disabled item | `aria-disabled` | `true` |
| User menu trigger | `aria-haspopup` | `true` |
| Plant selector | `aria-label` | "Select plant scope" |

### 10.3 Focus Management

- After navigation, focus moves to main content area (not trapped in sidebar)
- After closing mobile sheet, focus returns to trigger button
- Dropdown menus trap focus while open

---

## 11. Theme Integration

### 11.1 Current Theme System (Preserve)

- 4 presets: default, brutalist, soft-pop, tangerine
- oklch() CSS variables
- Dark/light/system mode via `next-themes`
- Preferences persisted in cookies

### 11.2 Sidebar Theming

Sidebar uses `--sidebar-*` CSS variables from shadcn. These automatically adapt to theme preset and mode. No Syncro-specific sidebar color overrides needed in Phase 1.

### 11.3 Active State Styling

| State | Visual Treatment |
|-------|-----------------|
| Active (current page) | `bg-sidebar-accent text-sidebar-accent-foreground` |
| Hover | Subtle background shift |
| Focused | Visible focus ring (2px, theme accent color) |
| Disabled/Coming Soon | Muted text + "Soon" badge |

---

## 12. Implementation Findings & Decisions

### 12.1 Finding: URL Rewrite Architecture

The project uses Next.js rewrites to present clean public URLs while keeping all authenticated routes under a single `dashboard/layout.tsx`. This is intentional and well-structured:

- `usePathname()` in client components returns the **public** URL (e.g., `/operations-overview`), which matches `sidebarItems[].url` — active state detection works correctly
- Middleware matcher covers both public paths and `/dashboard/:path*` for completeness
- `/dashboard` itself redirects to `/operations-overview` so there's no "empty shell" state

**Decision:** Preserve this architecture. Do not add routes outside the dashboard layout group.

### 12.2 Finding: Role Field Exists But Is Not Enforced

`sidebar-items.ts` already has `roles: ["SUPER_ADMIN"]` on System Health, but `NavMain` never reads it. The `AuthUser` type in `auth-session.ts` includes `applicationRole`.

**Decision:** Story 1.6 must wire these together. The filtering logic belongs in `NavMain` component, reading role from an auth context/hook.

### 12.3 Finding: NavUser Component Exists But Is Unused

`nav-user.tsx` exists with boilerplate menu items (Account, Billing, Notifications) that don't apply to Syncro.

**Decision:** Adapt NavUser for Syncro (role display, logout only). Wire it into `AppSidebar` footer above plant scope.

### 12.4 Finding: Quick Create + Inbox Are Boilerplate Artifacts

These render at the top of `NavMain` and have no Syncro domain mapping.

**Decision:** Remove in Story 1.6 or dedicated shell cleanup story.

### 12.5 Finding: Plant Scope Selector Has Two Placeholders

One in sidebar footer (`AppSidebar`), one in header bar (`dashboard/layout.tsx`). Both are text placeholders.

**Decision:** Implement in header bar only. Remove sidebar footer placeholder. Plant selector is a later story but the spec is defined here for when it's needed.

### 12.6 Finding: `comingSoon` Field Pattern

Nav items support `comingSoon: boolean` which renders a "Soon" badge and disables the link. This is useful during incremental rollout.

**Decision:** Keep this pattern. Use it for items that are visible to the user's role but not yet implemented (e.g., WAHA Templates before that story ships).

---

## 13. Component Dependency Map

```
dashboard/layout.tsx (Server Component)
├── AppSidebar (Client Component)
│   ├── SidebarHeader → Brand link
│   ├── SidebarContent → NavMain
│   │   └── NavMain
│   │       ├── reads sidebarItems config
│   │       ├── reads user role from auth context ← NEW
│   │       ├── filters items by role ← NEW
│   │       ├── NavItemExpanded (desktop expanded)
│   │       └── NavItemCollapsed (desktop collapsed)
│   └── SidebarFooter
│       ├── NavUser (adapted) ← NEW
│       └── (plant scope removed from here)
├── Header
│   ├── SidebarTrigger
│   ├── SearchDialog
│   ├── LayoutControls
│   ├── ThemeSwitcher
│   └── PlantScopeSelector ← NEW (replaces placeholder)
└── Main Content → {children}
```

---

## 14. Auth Context Contract

For role-based nav filtering and user indicator, components need access to current user. Required contract:

```typescript
// Hook or context that provides current authenticated user
function useAuthUser(): AuthUser | null;

// Where AuthUser is already defined in auth-session.ts:
type AuthUser = {
  id: string;
  loginIdentifier: string;
  applicationRole: "SUPER_ADMIN" | "MANAGE" | "VIEWER";
};
```

Source: read from `syncro_auth_user` cookie (set at login) or from a React context populated by the shell layout. Cookie approach is simpler for Phase 1 since it doesn't require a provider.

---

## 15. Story 1.6 Scope Alignment

This spec directly feeds Story 1.6 (role-based access). Based on this spec, Story 1.6 frontend work should include:

1. Create `useAuthUser()` hook that reads user from cookie/context
2. Add role filtering to `NavMain` using `roles` field
3. Adapt `NavUser` component for Syncro (role badge, logout)
4. Wire `NavUser` into `AppSidebar` footer
5. Remove Quick Create + Inbox from `NavMain`
6. Add unauthorized page state for direct URL access to restricted routes
7. Add session-expired handling in API client
8. Remove plant scope placeholder from sidebar footer (header placeholder stays until plant story)

Items NOT in Story 1.6 scope:
- PlantScopeSelector implementation (needs plant CRUD API first)
- Nav badges (needs alert/telemetry APIs)
- Entity search in command palette (needs data APIs)

---

## Appendix A: File Impact Summary

| File | Change Type | Description |
|------|-------------|-------------|
| `sidebar-items.ts` | Modify | Add `roles` to Master Data, WAHA Templates, Audit Log |
| `nav-main.tsx` | Modify | Add role filtering, remove Quick Create + Inbox |
| `nav-user.tsx` | Modify | Adapt for Syncro (role badge, logout, remove billing/notifications) |
| `app-sidebar.tsx` | Modify | Wire NavUser into footer, remove plant scope placeholder |
| `dashboard/layout.tsx` | Modify | Keep plant selector placeholder in header (future story replaces) |
| `middleware.ts` | No change | Role check stays backend-side, middleware only checks token presence |
| `auth-session.ts` | No change | `AuthUser` type already has `applicationRole` |
| New: `useAuthUser` hook | Create | Read current user for client components |
| New: unauthorized page | Create | Shell-wrapped "no permission" state |

---

## Appendix B: State Matrix

| Scenario | Sidebar | Header | Content | Toast |
|----------|---------|--------|---------|-------|
| Normal authenticated | Full nav (role-filtered) | All controls | Page content | — |
| Session expired (401) | Frozen | Frozen | Frozen | "Session expired" + redirect |
| Unauthorized route | Nav visible (item hidden) | All controls | Unauthorized message | — |
| Not found route | Full nav | All controls | Not found message | — |
| Network error | Full nav (cached) | All controls | Last content or error | "Unable to connect" |
| Mobile sidebar open | Sheet overlay | Hidden behind sheet | Dimmed | — |
| Loading (initial) | Static (server-rendered) | Static | Suspense/skeleton | — |
