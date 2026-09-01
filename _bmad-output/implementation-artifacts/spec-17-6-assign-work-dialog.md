---
title: 'Story 17-6: Frontend Assign & Work Dialog'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '795497f'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/sprint-change-proposal-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** The workorder table's Actions menu only offers the single-technician Assign dialog (10-2). The backend now supports multi-technician assignments (17-1) and per-assignment work logs (17-2), but there is no UI to drive them together.

**Approach:** Replace the single-tech `AssignDialog` in `WorkorderActionsCell` with a table-first **Assign & Work dialog**: the leader picks multiple technicians (checkboxes) and opens a work log per selected assignment with backdated start/end times (min = workorder's created_at), an activity note, optional stopped reason and completion note. Submitting posts to the 17-1/17-2 endpoints and refreshes the table/kanban. The dialog renders assignment/work-log state from backend data only.

## Boundaries & Constraints

**Always:**
- Non-native shadcn/Radix selects and pickers only (no native `<select>`, no `<input type="datetime-local">`). Use `Popover` + `Calendar` for the date and `Select` (hour/minute) for time.
- The dialog is opened from the Actions column on OPEN (and IN_PROGRESS for work logs) workorders.
- Multi-technician selection via `Checkbox`; one work-log form per selected technician (derived from the assignment's technician id).
- Submissions: `POST /api/v1/workorders/{id}/assignments` (per technician) then `POST /api/v1/workorders/{id}/work-logs` (per log). Invalidate `["/api/v1/workorders"]` and `["/api/v1/workorders/kanban"]` on success.
- Backdate rule enforced client-side: start/end cannot be before the workorder's `created_at` (passed as a prop). The backend is authoritative; a 400 surfaces the server message.
- Work-log fields: startTime, endTime, stoppedReason (WAITING_SPAREPART/SHIFT_END/COMPLETED/OTHER), activityNote (required), completionNote (optional).
- All dialog state is from backend data — no client-side status invention.
- Follow the existing `WorkorderActionsCell`/`workorder-table` patterns (TanStack Query, syncroFetch, sonner toasts, shadcn components).
- TypeScript strict; no `any`.

**Block If:**
- No existing shadcn `Calendar`/`Popover`/`Select`/`Checkbox` components to compose the datetime picker → HALT blocked (would require a new dependency).

**Never:**
- No native `<select>` or `<input type="datetime-local">`.
- No new dependencies.
- No changes to backend endpoints.
- No client-side permission decisions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Assign 2 technicians + work logs | 2 selected, start/end/notes filled | 2 assignment posts + 2 work-log posts; table refreshes; success toast | no error |
| No technician selected | empty selection | submit disabled | validation hint |
| Backdate before WO created_at | start < created_at | blocked client-side (min on picker) | hint text |
| Missing activity note | blank note on a log | blocked before submit | per-row validation hint |
| Assign only (no logs) | technicians selected, logs optional | assignments posted, logs skipped | no error |
| Server 400/403 | backend rejects | error toast with message; dialog stays open | error surface |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/web/src/features/workorders/components/workorder-actions-cell.tsx` -- replace `AssignDialog` with `AssignWorkDialog`; keep Transition/Report/RequestPart dialogs
- `syncro/apps/web/src/features/workorders/components/workorder-table.tsx` -- passes `createdAt` to the actions cell for the backdate min
- `syncro/apps/web/src/features/workorders/types.ts` -- add WorkAssignmentView + CreateWorkLogRequest contract types
- `syncro/apps/web/src/components/ui/checkbox.tsx` -- multi-select
- `syncro/apps/web/src/components/ui/calendar.tsx` + `popover.tsx` -- date picker
- `syncro/apps/web/src/components/ui/select.tsx` -- hour/minute/stoppedReason
- `syncro/apps/web/src/lib/api/orval-mutator.ts` -- syncroFetch

### To create/modify
- `syncro/apps/web/src/features/workorders/types.ts` -- add `WorkAssignmentView`, `WorkLogView`, `CreateWorkLogRequest` types
- `syncro/apps/web/src/features/workorders/components/workorder-actions-cell.tsx` -- new `AssignWorkDialog` with multi-tech + per-tech work log rows + inline DateTimePicker
- `syncro/apps/web/src/features/workorders/components/workorder-table.tsx` -- pass `createdAt` into the actions cell

## Tasks & Acceptance

**Execution:**
- `types.ts` -- add work-assignment/work-log contract types -- API contract
- `workorder-actions-cell.tsx` -- build AssignWorkDialog (multi-checkbox + per-tech log rows + DateTimePicker) -- core UI
- `workorder-table.tsx` -- pass createdAt to the cell -- backdate min

**Acceptance Criteria:**
- Given an OPEN workorder, when a leader opens Assign & Work from the Actions column, then the dialog lists assignable technicians with checkboxes and a per-selected-technician work-log form.
- Given a technician is selected, when the leader sets a backdated start before the workorder's created_at, then the picker blocks it (min constraint) and shows a hint.
- Given a selected technician with a blank activity note, when the leader submits, then the row shows a validation hint and nothing is posted for that row.
- Given 2 technicians selected with valid logs, when the leader submits, then 2 assignment posts and 2 work-log posts reach the backend and the workorder table + kanban refresh.
- Given a backend 400/403, when the leader submits, then an error toast surfaces the message and the dialog stays open.

## Verification

**Commands:**
- `cd syncro/apps/web && npx tsc --noEmit` -- expected: no type errors
- `cd syncro/apps/web && npx biome check src/features/workorders` -- expected: no lint errors
- `cd syncro/apps/web && npm run test -- --run src/features/workorders` -- expected: existing workorder tests pass (if any) or no test changes needed

**Manual checks (if no CLI):**
- Verify the dialog renders with checkbox list, per-row log forms, and non-native pickers.
