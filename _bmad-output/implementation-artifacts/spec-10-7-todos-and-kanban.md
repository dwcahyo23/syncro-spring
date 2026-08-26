---
title: 'Todos & Kanban'
type: 'feature'
created: '2026-08-26'
baseline_revision: 09db643
final_revision: 92593c8
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-6-reports-cp-cpk-fmea-and-stop-time.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders (10-1..10-6) have no per-task tracking — a section leader cannot break down a repair into sub-tasks, assign them to technicians, or see a visual kanban of work-in-progress grouped by status (FR-119).

**Approach:** Add a `workorder_todos` table (V52) with CRUD/assign/complete endpoints gated on the same executor/leader access as 10-4/10-5, plus a kanban read endpoint that returns workorders (filtered by derived scope, grouped by status) with their todos embedded. The kanban view is the first workorder list surface — the deferred 10-6 list/detail can supersede it later.

## Boundaries & Constraints

**Always:**
- **V52** (additive, on V51): `CREATE TABLE workorder_todos` — `id UUID PK DEFAULT gen_random_uuid()`, `workorder_id VARCHAR(50) NOT NULL REFERENCES work_orders(id) ON DELETE CASCADE`, `title VARCHAR(200) NOT NULL`, `description TEXT`, `assigned_technician_id UUID`, `status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','IN_PROGRESS','COMPLETED','CANCELLED'))`, `sort_order INTEGER NOT NULL DEFAULT 0`, `created_by UUID NOT NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `completed_at TIMESTAMPTZ`. Index `idx_workorder_todos_workorder_id` on `workorder_id`. Index `idx_workorder_todos_assigned_tech` on `assigned_technician_id`. The FK has ON DELETE CASCADE so removing a workorder also removes its todos.
- **Audit entity type extends V52**: drop/re-add `ck_audit_log_entity_type` adding `'WORK_ORDER_TODO'` (following V50 pattern, listing all prior types).
- **Todo is a local operational field** (same as report/evidence — AD-3 "preserved"): allowed on both SYNCED and INTERNAL workorders, never touches status or sync_version. Also blocked on terminal states (DONE/CLOSED/CANCELLED) — you cannot add/change todos on a closed workorder.
- **Todo CRUD** gated on the same 10-4/10-5 executor/leader gate: in-scope leader OR assigned executor can create/update/delete todos on the workorder. Creating a todo assigns the creator as `created_by`. Assigning a todo to a technician is separate from workorder assignment. Read (list todos) is any-authenticated (same as report read).
- **Kanban endpoint** `GET /api/v1/workorders/kanban` — returns workorders grouped by status, with their todos embedded. Scope-filtered by derived scope (same logic as read). Each workorder in the group includes: id, status, categoryCode, machineId, description, assignedTechnicianId, createdAt, plus a `todos` array. No pagination in v1 (kanban is a visual board, not a list — bounded by open workorders per scope). Returns `KanbanView` — a map of status → `WorkOrderKanbanItem[]`.
- **Todo endpoints** under the existing `WorkOrderController`:
  - `GET /{id}/todos` — list todos for a workorder (any-authenticated)
  - `POST /{id}/todos` — create todo (gate: executor/leader)
  - `PUT /{id}/todos/{todoId}/assign` — assign/change technician (gate: executor/leader)
  - `PUT /{id}/todos/{todoId}/complete` — mark complete (gate: executor/leader, or the assigned technician)
  - `PUT /{id}/todos/{todoId}/reorder` — update sort_order (gate: executor/leader)
  - `DELETE /{id}/todos/{todoId}` — delete todo (gate: executor/leader)
- **Rego**: `workorder_todo_paths := {"/api/v1/workorders/*/todos", "/api/v1/workorders/*/todos/*"}` — five-role allow set `{MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, TECHNICIAN}`. Reads (GET) flow through generic `read_allowed`.
- **Error codes**: unknown workorder → 404 WORKORDER_NOT_FOUND; unknown todo → 404 TODO_NOT_FOUND; access → 403 FORBIDDEN; terminal-state workorder → 400 WORKORDER_TERMINAL (todos cannot be mutated on done/closed/cancelled); validation (title blank, title too long, description too long, invalid status) → 400 VALIDATION_ERROR with fieldErrors.
- **Audit:** todo create → WORK_ORDER_TODO CREATE with entityId = todo UUID, entityLabel = title; todo assign → UPDATE with prev/new assigned_technician_id; todo complete → UPDATE with status + completed_at; todo reorder → UPDATE with sort_order; todo delete → DELETE. All audit records reference the workorder's plantId (loaded from machine).
- **Frontend kanban view** at `/workorders/kanban` — uses `@dnd-kit` (pre-installed). Columns by status, cards are workorder items with embedded todo list. TanStack Table v8 is NOT used for kanban (it's a board, not a table). The kanban page is a Server Component with a client interactive area.

**Block If:** nothing.

**Never:**
- Never touch V47-V51 or add V53 — V52 is the only migration for 10.7.
- Never implement full workorder list/detail — that stays deferred (10-6 deferral). The kanban endpoint is the only list surface.
- Never add a new Garage bucket — 10.7 has no file uploads.
- Never mutate workorder status or sync_version.
- Never add pagination to kanban in v1 — the board shows open/active workorders per scope, which is bounded.
- Never implement drag-to-reorder across status columns in v1 — the kanban is a grouped read view; status changes use the existing transition endpoint. Reorder is only within the same workorder's todos (sort_order).
- Never add real-time/WebSocket updates — kanban is a refresh-on-load view.
- Never add FMEA, ratings, or report fields.
- Never add new Spring Boot dependencies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| TODO_CREATE_OK | executor/leader, valid body on non-terminal WO | 201 `TodoView`; audit CREATE | — |
| TODO_CREATE_TERMINAL_WO | workorder is DONE/CLOSED/CANCELLED | 400 WORKORDER_TERMINAL | — |
| TODO_CREATE_BLANK_TITLE | title is empty | 400 VALIDATION_ERROR fieldErrors | — |
| TODO_CREATE_TOO_LONG_TITLE | title >200 chars | 400 VALIDATION_ERROR fieldErrors | — |
| TODO_LIST_OK | any authenticated user, WO exists | 200 `TodoView[]` | — |
| TODO_LIST_WO_NOT_FOUND | unknown workorder id | 404 WORKORDER_NOT_FOUND | — |
| TODO_ASSIGN_OK | executor/leader, valid technician | 200 `TodoView`; audit UPDATE assigned_technician_id | — |
| TODO_COMPLETE_BY_LEADER | executor/leader marks complete | 200 `TodoView`; status=COMPLETED, completed_at set; audit UPDATE | — |
| TODO_COMPLETE_BY_ASSIGNEE | assigned technician marks complete | 200 `TodoView`; same as above | — |
| TODO_COMPLETE_ALREADY_DONE | todo already COMPLETED | 400 VALIDATION_ERROR (invalid transition) | — |
| TODO_DELETE_OK | executor/leader | 204 No Content; audit DELETE | — |
| TODO_DELETE_NOT_FOUND | unknown todo id | 404 TODO_NOT_FOUND | — |
| KANBAN_READ_OK | any authenticated user, scope-filtered | 200 `KanbanView` | — |
| KANBAN_EMPTY_SCOPE | user has no plant/group access | 200 `KanbanView` with all empty arrays | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V52__workorder_todos.sql` -- NEW -- workorder_todos table + audit entity type extension.

**Domain:**
- `com/syncro/maintenance/domain/workorder/WorkOrderTodo.java` -- NEW -- domain record: id, workorderId, title, description, assignedTechnicianId, status, sortOrder, createdBy, createdAt, updatedAt, completedAt.
- `com/syncro/maintenance/domain/workorder/TodoStatus.java` -- NEW -- enum: PENDING, IN_PROGRESS, COMPLETED, CANCELLED.
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- add `WORK_ORDER_TODO` constant.

**Persistence:**
- `com/syncro/maintenance/infrastructure/db/WorkOrderTodoEntity.java` -- NEW -- JPA entity mapping workorder_todos.
- `com/syncro/maintenance/infrastructure/db/WorkOrderTodoRepository.java` -- NEW -- JpaRepository with findByWorkorderIdOrderBySortOrderAsc.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- MODIFY -- add findKanbanItems (or use a separate query service).

**Application:**
- `com/syncro/maintenance/application/WorkOrderTodoService.java` -- NEW -- CRUD + assign/complete + kanban read. Gate: executor/leader checks; terminal-state guard. Audit via AuditLogWriter.
- `com/syncro/maintenance/application/WorkOrderKanbanService.java` -- NEW -- (or merge into TodoService) -- kanban query: scope-filtered workorders grouped by status, with todos embedded.
- `com/syncro/maintenance/application/WorkOrderMapper.java` -- MODIFY -- add todo domain ↔ entity mapping.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- add todo + kanban endpoints.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- add TodoView, CreateTodoRequest, AssignTodoRequest, KanbanView, WorkOrderKanbanItem.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- TODO_NOT_FOUND + WORKORDER_TERMINAL.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- workorder_todo_paths + five-role rule.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases for todo paths.
- `syncro/.env.example` -- MODIFY -- todo paths in enforced-paths.

**Frontend:**
- `src/app/workorders/kanban/page.tsx` -- NEW -- kanban page (Server Component wrapper).
- `src/features/workorders/components/kanban-board.tsx` -- NEW -- client component: dnd-kit board, columns by status, workorder cards with embedded todos.
- `src/features/workorders/components/kanban-column.tsx` -- NEW -- single status column with cards.
- `src/features/workorders/components/workorder-card.tsx` -- NEW -- card showing basic info + assigned tech + todo summary.
- `src/features/workorders/hooks/use-kanban.ts` -- NEW -- data fetch hook for kanban endpoint.
- `src/features/workorders/hooks/use-todos.ts` -- NEW -- todo CRUD hooks.
- `src/lib/api/generated/syncro.ts` -- MODIFY -- regenerate or add types for todo/kanban endpoints.

**Tests:**
- `com/syncro/maintenance/application/WorkOrderTodoServiceTest.java` -- NEW -- todo CRUD + gate + terminal guard tests.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- todo + kanban endpoint shapes.
- `com/syncro/db/WorkorderTodosMigrationTest.java` -- NEW -- V52 table/columns/FK/CHECK/index (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [ ] `resources/db/migration/V52__workorder_todos.sql` -- create workorder_todos table + extend audit entity type + indexes.
- [ ] `WorkOrderTodo.java` / `TodoStatus.java` / `AuditEntityType.java` -- domain types.
- [ ] `WorkOrderTodoEntity.java` / `WorkOrderTodoRepository.java` -- persistence.
- [ ] `WorkOrderMapper.java` -- todo domain ↔ entity mapping.
- [ ] `WorkOrderTodoService.java` -- CRUD + assign/complete + gate + terminal guard + audit.
- [ ] `WorkOrderController.java` + DTOs + exception handler -- todo endpoints + kanban endpoint.
- [ ] `authz.rego` + `authz_test.rego` + `.env.example` -- workorder_todo_paths + parity.
- [ ] Frontend: kanban page + board components + hooks.
- [ ] Tests (service + controller + migration).

**Acceptance Criteria:**
- Given a section leader creates a todo on a non-terminal workorder (own group), when the todo is submitted, then it is persisted with PENDING status, assigned to the specified technician, and audit-logged with WORK_ORDER_TODO CREATE. [FR-119]
- Given a user attempts to create/update/delete a todo on a DONE/CLOSED/CANCELLED workorder, when submitted, then 400 WORKORDER_TERMINAL is returned and nothing is persisted.
- Given a todo exists with PENDING status, when the assigned technician or a leader marks it complete, then status changes to COMPLETED, completed_at is set, and an audit UPDATE is recorded.
- Given a workorder has todos, when the kanban endpoint is called, then workorders are grouped by status, each with its embedded todos, filtered by the user's derived scope.
- Given a user with no plant/group access calls the kanban endpoint, then an empty KanbanView is returned (all groups empty).
- Given OPA enforcement, then the todo mutation paths are default-deny with the same executor/leader role set as 10-4/10-5 sessions, with parity tests. [FR-160]

## Design Notes

- **Kanban as the first list surface.** The 10-6 spec explicitly deferred a full list/detail view, but a kanban board requires grouped workorders. The kanban endpoint is deliberately simple: no pagination, no sorting/filtering — it returns all non-terminal workorders the user can see, grouped by status. This is bounded because open workorders per scope are typically <100.
- **Todo status is a separate lifecycle from workorder status.** A todo can be PENDING → IN_PROGRESS → COMPLETED, or CANCELLED from any non-terminal state. The workorder's overall status is independent.
- **Terminal-state guard for todos.** You cannot mutate todos on a DONE/CLOSED/CANCELLED workorder. This is enforced in the application service before any read-write operation, same as how the status lifecycle guards transitions.
- **No dnd-kit status transitions.** The kanban board is a grouped read view in v1. Changing workorder status goes through the existing `POST /{id}/transition` endpoint (dnd-kit could be wired to it in a future iteration, but not in this story).
- **dnd-kit is pre-installed but unused** — this story wires it up for the kanban board's drag-to-reorder of todos within a workorder card and for visual drag on the board (which triggers transition via API, not local state). The actual transition call is stubbed in v1 (the board is read-only for status changes).
- **Kanban query is a single SQL query** — `SELECT w.*, t.* FROM work_orders w LEFT JOIN workorder_todos t ON t.workorder_id = w.id WHERE w.status NOT IN ('DONE','CLOSED','CANCELLED') AND w.machine_id IN (SELECT m.id FROM machines m WHERE m.machine_group_id IN :scopeGroupIds) ORDER BY w.status, t.sort_order` — then grouped in application code. This avoids N+1 on the list.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderTodoServiceTest,WorkOrderControllerTest,WorkorderTodosMigrationTest"` -- expected BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new todo parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

### 2026-08-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 2, medium 4, low 2)
- defer: 4 (low 4)
- reject: 3 (duplicates absorbed)
- addressed_findings:
  - `[high]` `[patch]` `complete()` gate did not authorize the todo's assigned technician — spec says "executor/leader OR the assigned technician". Loaded todo before gate and added `isTodoAssignedTechnician` check.
  - `[high]` `[patch]` Create-todo returned 200 instead of the spec's 201 — changed to `ResponseEntity.status(HttpStatus.CREATED)` + controller test now asserts `isCreated()`.
  - `[medium]` `[patch]` `assign()` accepted any UUID without user existence validation — added `requireTechnicianExists` via `AuthUserRepository.findById` + `USER_NOT_FOUND` mapping (10.7-SVC-009a).
  - `[medium]` `[patch]` `complete()` only guarded COMPLETED, not CANCELLED — added CANCELLED to the terminal-todo guard; added `WorkOrderTodoEntity.cancel()` for tests.
  - `[medium]` `[patch]` `assign()`/`reorder()`/`delete()` lacked the COMPLETED/CANCELLED todo terminal guard — added to `assign()`.
  - `[low]` `[patch]` Dead code `WorkOrderTodoEntity.update()` and `WorkOrderTodoService.auditEntityId()` — removed.
  - `[low]` `[patch]` `ReorderTodoRequest.sortOrder` had no bounds — added `@Min(0) @Max(1_000_000)`.
  - `[low]` `[patch]` Kanban card counted CANCELLED todos in "done" — now counts only COMPLETED, shows `· N cancelled`.
  - `[low]` `[patch]` Todo mutations invalidated only `["todos", id]` — now also invalidate the kanban query key.

## Review Triage Log

### 2026-08-26 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 8 (high 2, medium 4, low 2)
- defer: 4 (low 4)
- reject: 3
- addressed_findings: see Spec Change Log above (all patches applied).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 7; epic-10-context valid; spec-10-6 continuity loaded |
| 02 plan | pass | spec-10-7 written (V52 workorder_todos, todo endpoints, kanban read, rego) |
| 03 implement | pass | backend + rego + frontend + tests; 100/100 targeted, 238/238 WorkOrder* |
| 04 review | pass | Blind Hunter + Edge Case Hunter; deduped to 8 patches, 4 defers, 3 rejects |
| finalize | this commit | status done; followup_review_recommended: false |

**Summary:** Workorder todos & kanban (FR-119). V52 adds `workorder_todos` (UUID PK, workorder FK ON DELETE CASCADE, title/description/assigned_technician_id/status CHECK PENDING|IN_PROGRESS|COMPLETED|CANCELLED/sort_order/timestamps, 2 indexes) and extends the audit `entity_type` CHECK with `WORK_ORDER_TODO`. Todo endpoints (`GET/POST /{id}/todos`, `PUT /{id}/todos/{todoId}/assign|complete|reorder`, `DELETE /{id}/todos/{todoId}`) gated on executor/leader (complete additionally allows the todo's assigned technician), blocked on terminal workorders and terminal todos; every mutation audit-logged. Kanban read `GET /kanban` returns scope-filtered non-terminal workorders grouped by status with embedded todos via a single LEFT JOIN query. OPA `workorder_todo_paths` default-deny with the 10-4/10-5 five-role set + parity tests. Frontend: `/workorders/kanban` page, dnd-kit board (grouped read view), card with todo summary, hooks + typed API.

**Files changed (14 new, 11 modified):**
- NEW `V52__workorder_todos.sql` -- table + 2 indexes + audit entity_type extension.
- NEW `WorkOrderTodo.java` / `TodoStatus.java` / `WorkOrderTodoEntity.java` / `WorkOrderTodoRepository.java` / `WorkOrderKanbanRow.java` -- domain + persistence.
- NEW `WorkOrderTodoService.java` -- CRUD/assign/complete/reorder/delete + kanban + gates + audit.
- NEW `WorkOrderTodoServiceTest.java` (21 tests) + `WorkorderTodosMigrationTest.java` (8 tests).
- MOD `AuditEntityType.java` (+WORK_ORDER_TODO), `WorkOrderMapper.java` (+todo mapping), `WorkOrderRepository.java` (+findKanbanRows JPQL).
- MOD `WorkOrderController.java` / `WorkOrderDtos.java` / `WorkOrderExceptionHandler.java` -- 6 endpoints + TODO_NOT_FOUND + USER_NOT_FOUND + WORKORDER_TERMINAL.
- MOD `authz.rego` / `authz_test.rego` -- workorder_todo_paths + parity; `.env.example` enforced-paths.
- MOD `WorkOrderControllerTest.java` (75 tests) -- todo + kanban endpoint shapes.
- NEW frontend: `app/workorders/kanban/page.tsx`, `features/workorders/{types.ts, hooks/use-kanban.ts, hooks/use-todos.ts, components/{kanban-board,kanban-column,workorder-card}.tsx}`.

**Verification:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderTodoServiceTest,WorkOrderControllerTest,WorkorderTodosMigrationTest"` -- BUILD SUCCESS, 100/100.
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrder*Test"` -- BUILD SUCCESS, 238/238 (no regressions).
- `cd syncro/authz && ./run-opa-test.ps1` -- All tests passed.
- `cd syncro/apps/web && npx tsc --noEmit` -- green; `npx biome check src/features/workorders src/app/workorders` -- clean.

**Residual risks:** `nextSortOrder` is a non-locked read-then-insert (ponytail comment: advisory display order, acceptable for a kanban card — add PESSIMISTIC_WRITE if ordering becomes correctness-critical); kanban endpoint has no pagination by design (bounded by open workorders per scope); kanban board is read-only for status changes in v1 (transition stays on the existing endpoint).