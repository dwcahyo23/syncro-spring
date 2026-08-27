---
title: 'UI & Org Rework Plan — Maintenance Polish'
created: '2026-08-27'
status: 'executed'
executedAt: '2026-08-27'
commit: '86b5b97'
---

> **Status: EXECUTED.** This plan was implemented directly (no bmad workflow) on 2026-08-27 in commit `86b5b97` (18 files). See the Work Orders / Preventive page tabs and Organization tabs in the running app. Details that differ from this draft:
> - D3 org restructure landed as **Organization tabs only** (Departments/Users/Sections/Teams/Responsibility/Plants); no new `departments` backend master was introduced in this pass.
> - Workorder table filter: **month quick picker** (prev/next arrows) replaced the DateRangePicker + 7d/30d/90d presets; `categoryCode` param added to `GET /api/v1/workorders`.
> - Category master data: Work Order Categories tab (create+list) added to Work Orders; Preventive Categories is read-only (backend enum MECHANICAL/ELECTRICAL unchanged).
> - Actions column: Request part (reused) + Report (GET/PUT `/{id}/report`).
> - Setup tab removed; Plants moved into Organization; `features/setup` module left unreferenced on disk.

# UI & Org Rework Plan

**Context:** User wants: (1) workorder table list with monthly filter + pagination (kanban as secondary tab), (2) master-data menu consolidation into related tabs, (3) section/team/responsibility rework with section leaders by NIK/name + technician subordinates, (4) user master, (5) dialog consistency, (6) beautify all menus.

**Reference:** `E:\01 DEV\SYNCRO` uses department leadership via `spvId`/`mgId` + member users, and workorder table with 30d/90d date presets + server pagination (skip/take, PAGE_SIZE=20). No NIK there — user has `displayName`. User explicitly wants NIK in this project.

---

## Current State (SYNCRO-SPRING)

- Sections: `code` enum (MACHINERY/UTILITY/WORKSHOP) + `name`. Ambiguity: code vs name drift.
- Section leader: DERIVED from machine_responsibilities (level LEADER/SPV/MANAGER on machine in group). Not stored.
- machine_groups.section_id → sections (1:N, section has many groups).
- teams: cross-plant, expiry-dated, separate from section hierarchy.
- auth_users: id, login_identifier, password_hash, application_role, enabled, whatsapp_number. **No NIK, no display_name.**
- No user management endpoint (only read-only list: id, login, role).
- Workorder: no list endpoint — only /kanban + /ratings + per-id.
- Latest migration: V57.

---

## Proposed Changes

### Phase 1 — Backend Foundation (V58)
1. **V58 migration**:
   - `auth_users` + `display_name VARCHAR(200) NULL`, `nik VARCHAR(50) NULL UNIQUE` (nullable so existing users migrate; unique on non-null via partial index).
   - `sections` + `leader_user_id UUID NULL REFERENCES auth_users(id) ON DELETE SET NULL` — explicit section leader (fixes the derived-only gap; keeps derived as fallback).
   - Optional: `section_members` join (section_id, user_id) for technician subordinates — mirrors reference `department_users`. OR reuse machine_responsibilities. Decision below.
2. **Users API**: extend `AuthUserView` to include `displayName`, `nik`, `whatsappNumber`, `enabled`; add `PUT /api/v1/auth/users/{id}` (update displayName/nik) + searchable list. (Create/delete deferred — auth bootstrap owns creation.)
3. **Workorder list endpoint**: `GET /api/v1/workorders` — server-paginated (page/size), filterable by month/date range (`from`/`to`), status, machine, search; returns `{ items, total }`. Row fields: id (WO-format), status, machineCode/name, category, assignedTechnicianName, createdAt. Reuse existing scope filtering.

### Phase 2 — Workorder Table UI
4. Work Orders page: **table tab default** (TanStack Table, server pagination, month/date-range filter, status filter, search, page size 20) + **kanban tab** secondary + ratings tab. Mirror reference work-orders-page-client pattern.

### Phase 3 — Org/User UI
5. Master-data consolidation into tabs:
   - **Machines** tab-group: Machine Groups + Machines (linked).
   - **Organization** tab-group: Sections + Teams + Responsibility + Users (new master user tab).
   - **Spareparts** tab-group: Spareparts + Taxonomy (already tabbed).
6. Section UI: show name (resolve the code/name ambiguity — display name primary, code as badge), assign section leader via NIK/name combobox, list technicians as subordinates.
7. Responsibility UI: assign by NIK/name search (uses new displayName/nik), better labels (machine code, user name).

### Phase 4 — Dialog + Beautify Consistency
8. Standardize dialog positioning/styling (create-sparepart, category, all master-data dialogs).
9. Beautify remaining menus (telemetry, spareparts, etc.) to Card+Table+Select pattern.

---

## Open Decisions (RESOLVED)

- **D1 = (a)** — `section_members` join table (section_id, user_id) for technician subordinates. Mirrors reference `department_users`.
- **D2 = (a)** — `sections.leader_user_id` stored explicitly + auto-create/update machine_responsibility level LEADER on assign (side-effect). Assign leader by NIK/name.
- **D3 = setuju, lebih spesifik "organization maintenance" + department master.** Restructure:
  - Tab **"Machines"**: Machine Groups | Machines
  - Tab **"Organization Maintenance"**: Departments | Sections | Teams | Responsibility
  - Tab **"Users"**: user master (new: NIK + displayName)
  - Tab **"Spareparts"**: Spareparts | Taxonomy
  - Installations + Setup stay.
  - **NEW: `departments` master** — department layer (name, spvId/mgId leaders, members), mirroring reference `GqlDepartment`. Sections/teams/responsibility relate to departments.

**D4 = (a)** — Workorder table default tab + kanban tab + ratings tab (3 tabs).

---

## Execution Order (after decisions)
1. V58 migration (nik, display_name, leader_user_id, members per D1/D2)
2. Users API extension + workorder list endpoint
3. Workorder table UI (default tab)
4. Master-data tabs restructure
5. Section/Responsibility UI with NIK/name + leader auto-responsibility
6. User master UI
7. Dialog + beautify pass
8. Tests (migration + service + frontend tsc/biome)
