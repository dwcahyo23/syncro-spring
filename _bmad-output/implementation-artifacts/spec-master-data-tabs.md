---
title: 'Master Data Tabs Consolidation'
type: 'refactor'
created: '2026-08-27'
baseline_commit: c517149391bf9b64b3fa123e617e084303245e95
status: 'done'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-org-maintenance-model.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Master Data has 12 separate sidebar sub-items and routes (plants, departments, users, sections, teams, machine-groups, machines, installations, spareparts, responsibilities, setup) even though many are related views of the same domain — forcing users to jump between nav items for connected data and fragmenting the menu. The user wants related items consolidated into one menu with tabs (e.g. machines+groups; organization maintenance: sections+teams+responsibility+users+departments), mirroring the reference project's grouped menus.

**Approach:** Consolidate the 12 master-data routes into 4 tabbed pages — **Machines** (Machine Groups | Machines | Installations), **Organization** (Departments | Users | Sections | Teams | Responsibility), **Spareparts** (Spareparts | Category, already done), **Plants** (Plants | Setup) — each a `RoleGuard`-wrapped `Tabs` page. Update `sidebar-items.ts` subItems to 4 entries with the tab pages. Keep existing feature components unchanged; only the route + sidebar grouping changes. Old individual routes redirect to the new tab pages.

## Boundaries & Constraints

**Always:**
- **4 consolidated pages** (all under `src/app/(main)/dashboard/master-data/`):
  - `machines/page.tsx` — Tabs: `groups` (MachineGroupManagement) | `machines` (MachineManagement) | `installations` (InstallationManagement). Default `groups`.
  - `organization/page.tsx` — Tabs: `departments` (DepartmentManagement) | `users` (UserManagement) | `sections` (SectionManagement) | `teams` (TeamManagement) | `responsibility` (ResponsibilityManagement). Default `departments`.
  - `spareparts/page.tsx` — EXISTING (Spareparts | Category) — keep as-is.
  - `plants/page.tsx` — Tabs: `plants` (PlantManagement) | `setup` (SetupCompletenessPage). Default `plants`.
- **Sidebar** (`src/navigation/sidebar/sidebar-items.ts`): Master Data subItems become exactly 4: **Machines** → `/master-data/machines`, **Organization** → `/master-data/organization`, **Spareparts** → `/master-data/spareparts`, **Plants** → `/master-data/plants`. Remove the 12 individual subItems (departments, users, sections, teams, machine-groups, machines, installations, responsibilities, setup).
- **Old routes → redirect** (for backward compat, same pattern as the workorders/preventive redirects): `machine-groups`, `installations`, `departments`, `users`, `sections`, `teams`, `responsibilities`, `setup` each become a thin `page.tsx` that `redirect()`s to the consolidated page + `#tab`? No — redirect to the page with a query param `?tab=` that the Tabs page reads via `useSearchParams` to preselect. Simpler: redirect to the consolidated page and let the page default; a `?tab=` param on the target is a nice-to-have. Keep it minimal: redirect to the page, default tab shows (document the mapping).
- **RoleGuard** per consolidated page: Machines + Organization + Plants use `allowedRoles={["SUPER_ADMIN","MANAGER_MAINTENANCE","AUDITOR"]}` (matches existing); Spareparts unchanged.
- **Machine detail sub-route** `machines/[machineCode]/page.tsx` must STAY (it is a detail route, not a menu item) — do not move it.
- **Tab deep-linking:** each `Tabs` page reads `useSearchParams().get("tab")` to set the initial tab (so old-route redirects can land on the right tab); falling back to the default. `Tabs` `value`/`onValueChange` drive a `router.replace` to `?tab=` (optional; minimal: only read on mount for deep-link, don't rewrite URL on every tab change to avoid churn — acceptable).
- **No feature-component changes.** MachineGroupManagement, MachineManagement, etc. are mounted as-is. No state/data/API changes. This is pure navigation restructuring.
- **Loading/empty/error states** already live in each component — unchanged.

**Ask First:** none — grouping confirmed by user (machines+groups; organization maintenance: sections+teams+responsibility+users+departments; user added departments+users).

**Never:**
- Never modify the feature components' internals (machines, sections, teams, etc.) in this story — only their mounting page + sidebar grouping.
- Never move `machines/[machineCode]/page.tsx` (detail route stays).
- Never create a new tab framework — reuse shadcn `Tabs` (existing pattern from spareparts page).
- Never change backend/API/rego.
- Never remove the standalone routes by deleting files if other code links to them — use redirect stubs (same pattern as workorders).
- Never add query-string rewriting that breaks the tabs (keep `useSearchParams` read-only for deep-link).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| NAV_MACHINES | click Master Data → Machines | Tabs page with groups/machines/installations, default groups | — |
| NAV_ORG | click Master Data → Organization | Tabs page with departments/users/sections/teams/responsibility, default departments | — |
| NAV_PLANTS | click Master Data → Plants | Tabs page with plants/setup, default plants | — |
| OLD_ROUTE_REDIRECT | visit /master-data/sections | redirect → /master-data/organization (default departments) | — |
| DEEP_LINK_TAB | /master-data/machines?tab=machines | opens machines tab preselected | — |
| DETAIL_ROUTE | /master-data/machines/M-001 | still works (detail route untouched) | — |
| UNAUTHORIZED | AUDITOR on machines page | RoleGuard allows (AUDITOR in allowedRoles) | — |

</frozen-after-approval>

## Code Map

**Frontend routes (consolidated pages):**
- `src/app/(main)/dashboard/master-data/machines/page.tsx` -- MODIFY -- Tabs: groups | machines | installations (mounts MachineGroupManagement, MachineManagement, InstallationManagement) + RoleGuard + `useSearchParams` tab preselect.
- `src/app/(main)/dashboard/master-data/organization/page.tsx` -- NEW -- Tabs: departments | users | sections | teams | responsibility (mounts DepartmentManagement, UserManagement, SectionManagement, TeamManagement, ResponsibilityManagement) + RoleGuard + tab preselect.
- `src/app/(main)/dashboard/master-data/plants/page.tsx` -- MODIFY -- Tabs: plants | setup (mounts PlantManagement, SetupCompletenessPage) + RoleGuard + tab preselect.
- `src/app/(main)/dashboard/master-data/spareparts/page.tsx` -- KEEP -- already tabbed (Spareparts | Category).

**Redirect stubs (backward compat):**
- `src/app/(main)/dashboard/master-data/machine-groups/page.tsx` -- MODIFY -- `redirect("/master-data/machines")`.
- `src/app/(main)/dashboard/master-data/installations/page.tsx` -- MODIFY -- `redirect("/master-data/machines?tab=installations")`.
- `src/app/(main)/dashboard/master-data/departments/page.tsx` -- MODIFY -- `redirect("/master-data/organization?tab=departments")`.
- `src/app/(main)/dashboard/master-data/users/page.tsx` -- MODIFY -- `redirect("/master-data/organization?tab=users")`.
- `src/app/(main)/dashboard/master-data/sections/page.tsx` -- MODIFY -- `redirect("/master-data/organization?tab=sections")`.
- `src/app/(main)/dashboard/master-data/teams/page.tsx` -- MODIFY -- `redirect("/master-data/organization?tab=teams")`.
- `src/app/(main)/dashboard/master-data/responsibilities/page.tsx` -- MODIFY -- `redirect("/master-data/organization?tab=responsibility")`.
- `src/app/(main)/dashboard/master-data/setup/page.tsx` -- MODIFY -- `redirect("/master-data/plants?tab=setup")`.

**Sidebar:**
- `src/navigation/sidebar/sidebar-items.ts` -- MODIFY -- Master Data subItems → 4 (Machines, Organization, Spareparts, Plants). Remove 12 individual entries. Keep icons.

**Shared helper (optional):**
- `src/features/master-data/use-master-data-tab.ts` -- NEW -- tiny hook: reads `useSearchParams().get("tab")`, returns `[value, setValue]` where setValue does NOT rewrite URL (read-only deep-link). Optional — inline is fine.

**Tests:**
- `syncro/apps/web/src/features/master-data/*.test.tsx` -- EXISTING feature tests must still pass (components unchanged).
- No new unit tests strictly required (pure navigation); a smoke check that consolidated pages render is acceptable if a test harness exists for route pages — otherwise rely on tsc + biome + manual browser.

## Tasks & Acceptance

**Execution:**
- [x] `machines/page.tsx` -- Tabs (groups/machines/installations) + RoleGuard + tab preselect.
- [x] `organization/page.tsx` -- NEW Tabs (departments/users/sections/teams/responsibility) + RoleGuard + tab preselect.
- [x] `plants/page.tsx` -- Tabs (plants/setup) + RoleGuard + tab preselect.
- [x] 8 redirect stubs for old routes.
- [x] `sidebar-items.ts` -- Master Data subItems → 4.
- [x] Verify feature tests + tsc + biome.

**Acceptance Criteria:**
- Given Master Data in the sidebar, when the user opens it, then they see exactly 4 sub-items (Machines, Organization, Spareparts, Plants), each a tabbed page. [nav]
- Given the Machines tab page, when opened, then Machine Groups, Machines, and Installations are tabs (default Machine Groups). [machines]
- Given the Organization tab page, when opened, then Departments, Users, Sections, Teams, and Responsibility are tabs (default Departments). [organization]
- Given an old master-data URL (e.g. /master-data/sections), when visited, then it redirects to the consolidated page with the matching tab preselected. [redirect]
- Given the machine detail route, when visited, then it still works unchanged. [detail]
- Given an AUDITOR, when opening any consolidated page, then RoleGuard permits (read-only). [role]

## Design Notes

- **Read-only deep-link.** Each Tabs page reads `useSearchParams().get("tab")` once on mount to preselect (so old-route redirects land on the right tab), but does NOT rewrite the URL on tab switch — avoids churn and keeps Tabs state local. This is the minimal-viable deep-link.
- **Redirect mapping table** (old route → new tab):
  - machine-groups → /master-data/machines (groups default)
  - installations → /master-data/machines?tab=installations
  - departments → /master-data/organization?tab=departments
  - users → /master-data/organization?tab=users
  - sections → /master-data/organization?tab=sections
  - teams → /master-data/organization?tab=teams
  - responsibilities → /master-data/organization?tab=responsibility
  - setup → /master-data/plants?tab=setup
- **Why redirect stubs, not deletion:** other code (tests, bookmarks, deep links) may reference the old paths; redirect preserves them without breaking, matching the workorders/preventive pattern already used.
- **`machines/[machineCode]` untouched** — it is a detail route (machine hub) reached from the Machines tab, not a menu item; moving it would break the machine hub.

## Verification

**Commands:**
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/app/'(main)'/dashboard/master-data src/navigation/sidebar` -- expected clean.
- `cd syncro/apps/web && npx jest src/features/master-data --passWithNoTests` -- expected pass (feature tests unaffected).
- Manual: open /master-data/machines, /master-data/organization, /master-data/plants in browser; verify tabs + redirects.

## Spec Change Log

<!-- Empty until review loop. -->
