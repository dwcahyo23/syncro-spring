---
title: 'Workorder Table List — Month Filter & Pagination'
type: 'feature'
created: '2026-08-27'
baseline_commit: db4e145b9ee3a22ae44f489bb30f5cbe9f5d75a2
status: 'done'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-2-create-and-assign-workorders.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-7-todos-and-kanban.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The workorders UI is kanban-only with no server-side list, so there is no way to scan hundreds of workorders per month, filter by month/status/machine, or paginate. Operators need a dense table for volume review (reference `E:\01 DEV\SYNCRO` work-orders-page pattern).

**Approach:** Add a server-paginated `GET /api/v1/workorders` list endpoint (month/date-range + status + machine + search filters, `page`/`size`, sorted by `createdAt desc`) reusing the existing scope filter + category join; make the Work Orders page a **table default tab** with TanStack Table server-mode, a month/date-range picker, and keep kanban as a secondary tab.

## Boundaries & Constraints

**Always:**
- **List endpoint** `GET /api/v1/workorders?page=0&size=20&from=2026-08-01&to=2026-08-31&status=&machineId=&search=` — server-paginated, scope-filtered (same `findKanbanRows` pattern: unrestricted for SUPER_ADMIN else plant/group filter), ordered `createdAt desc`. Returns `{ items, total, page, size }` (JSON envelope — frontend `syncroFetch` unwraps `data`).
  - `from`/`to` are ISO date (`yyyy-MM-dd`), inclusive; both optional; `from` only / `to` only allowed. Timestamps use server clock, `created_at` in UTC; boundary = start-of-day/end-of-day UTC.
  - `status` optional single status enum; `machineId` optional; `search` matches `w.id` OR machine code/name OR category label (case-insensitive, `%term%` escaped).
  - `page` (0-based) + `size` (1..200, default 20). Return `total` = matching count (second query).
- **New repository method** `findScopedPage(...)` on `WorkOrderRepository` — single JPQL with `Pageable`-style `setMaxResults/setFirstResult` + a count query, reusing the `WorkOrderKanbanRow(w, c, t)`-style join but WITHOUT todos (avoid the cartesian blowup on hundreds of rows). Row shape = `WorkOrderListRow(w, c, machineCode, machineName, assignedTechnicianName?)`.
- **Row fields:** `id` (WO-format), `status`, `categoryCode`/`categoryLabel`, `machineCode`, `machineName`, `plantCode`, `assignedTechnicianId`/`assignedTechnicianName` (resolved via `AuthUserRepository` lookup for the assigned id), `description` (trimmed), `createdAt`, `updatedAt`, `doneReason`. No raw UUIDs leaked in the UI — frontend renders `id` (WO-YYMM-XXXXX), machine `code · name`, plant `code`.
- **Controller:** add `GET /api/v1/workorders` to `WorkOrderController` (distinct from `/kanban`, `/ratings`, `/{id}...`). Any authenticated user may read (workorder read posture).
- **Rego:** the list read flows through generic `read_allowed` (GET /api/v1/workorders) — no new mutation path. `.env.example` already covers `/api/v1/workorders/**`? Verify; add `/api/v1/workorders` exact if missing.
- **Frontend — Work Orders page** (`src/app/(main)/dashboard/workorders/page.tsx`): restructure to 3 tabs — **Table (default)** | Kanban | Ratings.
  - **Table tab** (`workorder-table.tsx`): TanStack Table server-mode (`useReactTable` + `manualPagination`), `pageCount = ceil(total/size)`, prev/next + page indicator, `size=20`.
    - Filters: **month/date-range picker** (shadcn — a month input or date-range; reference uses 30d/90d presets + range). Include a "This month" default and quick presets (7d/30d/90d/This month). Reset page on filter change.
    - Status select (shadcn), machine select (reuse `useListMachines` pattern, label `code · name · plantCode`), search box (300ms debounce).
    - Columns: WO No, Status (badge), Machine, Problem (description, line-clamp), Plant, Technician (name), Created (format).
    - Loading skeleton, empty state ("No workorders match this filter"), error state + retry.
  - **Kanban tab** = existing `KanbanBoard`; **Ratings tab** = existing `RatingsPageContent`.
- **Hooks:** `useWorkorders` in `src/features/workorders/hooks/use-workorders.ts` — query key `["/api/v1/workorders", params]`, staleTime 15s.
- **Types:** add `WorkOrderListRow`, `WorkOrderPage` to `src/features/workorders/types.ts`.

**Ask First:** none — month-range filter is a shadcn date-range picker (reference pattern); no decision needed.

**Never:**
- Never change existing kanban/ratings/todos endpoints or behavior.
- Never add todos to the list row query (N+1/cartesian) — list is row-only.
- Never compute month boundaries from client clock — send `from`/`to` and let backend filter `created_at` (server clock).
- Never add a new table library — TanStack Table is already installed (v8).
- Never introduce client-side pagination over a full fetch — server-side only.
- Never leak raw UUIDs: `machineId`/`assignedTechnicianId` stay out of rendered cells (may exist in row data for keys/actions but not displayed).
- Never touch V58 or add a migration — this is read-only + UI.
- Never implement CSV/export, column reorder persistence, or row selection in v1 — out of scope.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| LIST_OK | page=0,size=20, no filters | 200 {items,total,page,size}, total = all scoped WOs | — |
| LIST_MONTH_FILTER | from=2026-08-01,to=2026-08-31 | only WOs with created_at in Aug 2026 (UTC) | — |
| LIST_STATUS_FILTER | status=OPEN | only OPEN WOs | — |
| LIST_SEARCH | search=WO-2608 | matches id/machine/category containing term | — |
| LIST_BAD_PAGE | page=-1 or size=0 | 400 VALIDATION_ERROR fieldErrors | — |
| LIST_BAD_DATE | from=not-a-date | 400 VALIDATION_ERROR fieldErrors.from | — |
| LIST_FORBIDDEN | AUDITOR (any authed) | 200 (read posture) — not forbidden | — |
| LIST_OUT_OF_SCOPE | SECTION_LEADER, WO outside scope | excluded from results (scope filter) | — |
| LIST_EMPTY | no matching WOs | 200 {items:[], total:0} | — |

</frozen-after-approval>

## Code Map

**Persistence:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- MODIFY -- add `findScopedPage(unrestricted, plantIds, groupIds, from, to, status, machineId, search, limit, offset)` + `countScoped(...)` mirroring `findKanbanRows` scope filter (lines 54-68); new row record `WorkOrderListRow` (w + category + machineCode/name + plantCode) without todos.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderListRow.java` -- NEW -- flat row (entity, category, machineCode, machineName, plantCode).

**Application:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderListService.java` -- NEW -- `Page<WorkOrderListView>`: derive scope (`OperationalScopeService`), call repository, resolve `assignedTechnicianName` via `AuthUserRepository.findAllById`, map to view. `Page` record (items, total, page, size).
- `syncro/auth/infrastructure/AuthUserRepository.java` -- READ -- findAllById(Collection<UUID>) exists (JPA default).

**API:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- add `@GetMapping` `/api/v1/workorders` (before `/{id}` mappings to avoid path conflict) delegating to `WorkOrderListService`.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- add `WorkOrderListRowView`, `WorkOrderPageView`, and validation annotations on query params (via `@RequestParam` `@Min/@Pattern` or service-side validation).

**Enforcement:**
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- MODIFY -- verify `GET /api/v1/workorders` read-allowed parity (add test if missing).
- `syncro/.env.example` -- MODIFY -- ensure `/api/v1/workorders` in enforced-paths (verify; `/api/v1/workorders/**` may already cover).

**Frontend:**
- `src/app/(main)/dashboard/workorders/page.tsx` -- MODIFY -- 3 tabs (Table default | Kanban | Ratings).
- `src/features/workorders/hooks/use-workorders.ts` -- NEW -- list query + filters.
- `src/features/workorders/components/workorder-table.tsx` -- NEW -- TanStack server table + month/date-range filter + status/machine/search + pagination + states.
- `src/features/workorders/types.ts` -- MODIFY -- WorkOrderListRow, WorkOrderPage.
- `src/components/ui/date-range-picker.tsx` -- CHECK -- exists? if not, use shadcn Calendar/Popover pair or a MonthPicker; prefer existing.
- Reuse `useListMachines` for the machine filter (label `code · name · plantCode`).

**Tests:**
- `com/syncro/maintenance/application/WorkOrderListServiceTest.java` -- NEW -- pagination, month filter, status filter, search, scope filter, page math.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- add list endpoint shape tests (200, page/size, bad params 400).
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- read-allowed parity for GET /api/v1/workorders if absent.

## Tasks & Acceptance

**Execution:**
- [x] `WorkOrderRepository` -- findScopedPage + countScoped + WorkOrderListRow (no todos).
- [x] `WorkOrderListService` -- scope derive + technician name resolve + page mapping.
- [x] `WorkOrderController` + DTOs -- GET /api/v1/workorders + param validation.
- [x] Rego parity + `.env.example` -- read-allowed GET /workorders.
- [x] Frontend: workorder-table.tsx (TanStack server table + month filter + pagination) + tabs + hooks + types.
- [x] Tests (service + controller + rego).

**Acceptance Criteria:**
- Given a user with workorder read access, when they request the list with page/size, then a server-paginated result `{items,total,page,size}` is returned scoped to their plant/groups. [list]
- Given a month range (`from`/`to`), when filtered, then only workorders created within that window (server clock UTC) are returned. [month filter]
- Given status/machine/search filters, when applied, then results narrow accordingly and page resets. [filters]
- Given the Work Orders page, when opened, then the table tab is default with a month filter + server pagination; kanban and ratings remain accessible as tabs. [UI]
- Given an empty or error result, when the table loads, then empty and error states render with retry. [states]
- Given OPA enforcement, then the list read is any-authenticated with parity test. [FR-160]

## Design Notes

- **Row query without todos.** `findKanbanRows` joins todos (fine for a board); the list reuses the same scope join but drops todos to avoid a cartesian product on hundreds of rows. Row = `(w, category, machineCode, machineName, plantCode)`; technician name resolved in a second `findAllById` (≤ pageSize lookups, one query).
- **Month filter via date-range picker.** Backend takes `from`/`to` ISO dates and filters `created_at >= from@00:00 UTC AND < to+1day`. The frontend month picker sends these; the "This month" default computes from the server's `today` if exposed, else the client's current month (accepted — the filter is a query convenience, not a business clock).
- **Server pagination contract.** Frontend `useReactTable` with `manualPagination: true`, `pageCount = ceil(total/size)`, `state.pagination.pageIndex`, and `onPaginationChange` refetch. Page resets to 0 on any filter change (reference pattern).
- **Path ordering.** `GET /api/v1/workorders` must be declared before any `/{id}` mapping in the controller or Spring may route `GET /api/v1/workorders` into a `/{id}` handler — confirm the controller's existing GET order and place the new mapping first.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderListServiceTest,WorkOrderControllerTest"` -- expected BUILD SUCCESS.
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test"` -- expected no regressions.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS (new read parity).
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/workorders src/app/workorders src/app/'(main)'/dashboard/workorders` -- expected clean.

## Spec Change Log

<!-- Empty until review loop. -->
