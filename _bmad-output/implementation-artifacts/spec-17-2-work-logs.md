---
title: 'Story 17-2: Work Logs per Assignment'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: '40c5a63'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Work logs (blueprint B4, AD-18) are recorded in the dormant `work_logs` table — per-assignment execution sessions with backdate support and a stopped reason — but no service, API, OPA rule, or audit path touches them, and workorder completion/MTTR still relies on the legacy `repair_sessions` model.

**Approach:** Build the application/API layer over the existing `work_logs` schema: a technician or leader creates a work log against an active assignment (start/end, backdate allowed but never before the workorder's `created_at`), edits it, and lists it; each mutation is audit-logged and cumulative completed duration feeds MTTR. Extend the completion gate so a workorder cannot reach CLOSED without a completed work log (or a completed repair session, legacy) unless a documented reason is provided.

## Boundaries & Constraints

**Always:**
- The `work_logs` table, `WorkLogEntity`, `WorkLogRepository`, and `WorkLogStoppedReason` already exist (15-2) — reuse; do not alter the V1 schema.
- New endpoints under `com.syncro.maintenance` following the existing layered pattern and the `WorkOrderController` `/api/v1/workorders/{id}/...` surface.
- A work log is recorded **against an active assignment** on the workorder: `work_assignment_id` is required and must reference an active `work_assignments` row for the same workorder; `technician_id` is derived from that assignment (never taken from the request).
- Backdate rule (AD-18): `start_time` must be >= the workorder's `created_at`; `end_time` (when present) must be > `start_time`. Earlier values are rejected with a machine-readable code.
- Role gate mirrors the workorder session/transition gates: executor (TECHNICIAN/STAFF_MAINTENANCE with an active assignment on the WO, or the legacy `assigned_technician_id`) OR in-scope leader may create/edit; any authenticated user may list. SECTION_LEADER cannot be the executing technician on their own group's workorders (AD-14 — they cannot assign themselves, so they cannot have an active assignment).
- EXTERNAL workorders are owned by the sync module — work-log mutations rejected.
- Every create/edit writes an `audit_log` row (actor, previous/new values, plant id, stashed OPA decision id).
- `AuditEntityType.WORK_LOG` must be added to BOTH the Java enum and the `audit_log.entity_type` CHECK — additive V4 migration (same pattern as V3).
- Completing a work log (setting `end_time` + `stopped_reason`) recomputes cumulative MTTR on the workorder: SUM(repair session durations) + SUM(work log durations). MTTR is persisted on the workorder.
- Completion gate (FR-115, AD-18): a workorder cannot transition to CLOSED/PENDING_REVIEW completion without a completed work log OR a completed repair session (legacy) OR a documented `reason`. This extends the existing `enforceDoneGate` additively — the legacy repair-session path must keep working (existing tests rely on it).
- OPA: add `workorder_worklog_paths` to `authz.rego` + mirror tests, using the same executor+leadership allow set as the session/transition paths (MANAGER_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, STAFF_MAINTENANCE, TECHNICIAN).

**Block If:**
- The audit CHECK extension cannot be done as an additive migration → HALT blocked.

**Never:**
- No schema changes to `work_logs` (V1 shape is authoritative).
- No hard deletion of work logs.
- No bypassing OPA; no new dependencies; no native `<select>` in UI; no ddl-auto changes.
- Do not break the legacy repair-session completion gate or its tests.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create log on active assignment | technician with active assignment, start/end times, activity_note | work_logs row persisted; audit CREATE; MTTR recomputed if completed | no error |
| Backdate before workorder created_at | start_time < workorder.createdAt | rejected | `BACKDATE_BEFORE_WORKORDER` (400) |
| End before start | end_time <= start_time | rejected | `VALIDATION_ERROR` fieldErrors (400) |
| Missing activity_note | blank note | rejected (DB CHECK also backstops) | `VALIDATION_ERROR` (400) |
| Create against no active assignment | assignmentId not active / wrong WO | rejected | `ASSIGNMENT_NOT_FOUND` (404) or `WORKLOG_ASSIGNMENT_INACTIVE` (409) |
| Non-executor role | AUDITOR/INVENTORY/STOREKEEPER creates | rejected | `FORBIDDEN` (403) |
| EXTERNAL workorder | source=EXTERNAL | rejected | `FORBIDDEN` (403) |
| Edit someone else's log | non-leader edits a log not theirs | rejected | `FORBIDDEN` (403) |
| Completion without any work | transition to completion, no completed work log/session, no reason | rejected | `DONE_WITHOUT_SESSION_REASON_REQUIRED` (reuse) |
| Completion with completed work log | completed log exists, transition to completion | allowed | no error |
| Invalid stopped_reason | unknown enum value | rejected | `VALIDATION_ERROR` (400) |
| Concurrent work logs | two open logs same assignment | allowed if intervals don't conflict; DB CHECK enforces end>start | no error |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogEntity.java` -- entity with `stop()` method (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkLogRepository.java` -- `findByWorkOrderIdOrderByStartTimeAsc`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkLogStoppedReason.java` -- enum
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java` -- `enforceDoneGate` (repair-session gate, lines ~427-441), `requireSessionAccess`, `isExecutor`, exception classes; MTTR recompute pattern (`sumCompletedDuration`)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentRepository.java` -- active-assignment finder from 17-1
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add work-log endpoints
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add work-log records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_LOG
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- work_logs + audit CHECK (baseline, do not edit)
- `syncro/apps/backend/src/main/resources/db/migration/V3__work_assignment_audit_type.sql` -- additive CHECK pattern to follow
- `syncro/authz/policy/authz.rego` -- session/transition path sets + role blocks
- `syncro/authz/policy/authz_test.rego` -- mirror tests
- `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` -- record pattern
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserEntity.java` -- `isEnabled()` for assignee validation

### To create/modify
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkLogService.java` -- NEW: create/update/list use cases with gates, backdate validation, MTTR recompute
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add `CreateWorkLogRequest`, `UpdateWorkLogRequest`, `WorkLogView` records
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add `POST /{id}/work-logs`, `PUT /{id}/work-logs/{workLogId}`, `GET /{id}/work-logs`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add `WORK_LOG`
- `syncro/apps/backend/src/main/resources/db/migration/V4__work_log_audit_type.sql` -- NEW additive migration extending audit CHECK
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java` -- extend `enforceDoneGate` to accept a completed work log as satisfying completion (additive; keep repair-session path)
- `syncro/authz/policy/authz.rego` -- add `workorder_worklog_paths` + role allow blocks
- `syncro/authz/policy/authz_test.rego` -- mirror tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogServiceTest.java` -- NEW unit tests for the I/O matrix
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogServiceIntegrationTest.java` -- NEW real-Postgres test (AbstractPostgresIntegrationTest): persisted log + audit + MTTR recompute + backdate rejection + completion gate
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkOrderTransitionServiceTest.java` -- keep green (add a completed-work-log case if trivial)
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion with WORK_LOG + full V1 value list

## Tasks & Acceptance

**Execution:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkLogService.java` -- create/update/list with executor/leader gates, backdate validation, MTTR recompute, audit -- core use cases
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add work-log records -- API contract
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add endpoints -- expose the service
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions -- stable error codes
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_LOG -- audit label
- `syncro/apps/backend/src/main/resources/db/migration/V4__work_log_audit_type.sql` -- extend audit CHECK additively -- schema/entity parity
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java` -- extend enforceDoneGate to accept completed work log -- completion gate
- `syncro/authz/policy/authz.rego` -- add work-log path set + role blocks -- OPA enforcement
- `syncro/authz/policy/authz_test.rego` -- add tests -- OPA parity
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogServiceTest.java` -- unit tests for the I/O matrix -- edge cases
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkLogServiceIntegrationTest.java` -- real-DB tests -- persisted AC verification
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion -- migration test

**Acceptance Criteria:**
- Given a technician with an active assignment on an internal IN_PROGRESS workorder, when they create a work log with start/end times and an activity note, then a `work_logs` row is persisted with `technician_id` from the assignment, an audit CREATE row is written, and cumulative MTTR (repair sessions + work logs) is recomputed and persisted.
- Given a work log with `start_time` before the workorder's `created_at`, when created, then it is rejected with `BACKDATE_BEFORE_WORKORDER`.
- Given a work log with `end_time` not after `start_time`, when created, then it is rejected with `VALIDATION_ERROR`.
- Given a completed work log exists (end_time + stopped_reason) on a workorder, when the workorder transitions to completion, then it is allowed (no documented reason required).
- Given no completed work log AND no completed repair session AND no documented reason, when the workorder transitions to completion, then it is rejected.
- Given a non-executor user, an EXTERNAL workorder, or an assignment that is not active/on the same workorder, when a work log is created, then it is rejected with the appropriate code and no row is written.
- Given the OPA policy, when a MANAGER_MAINTENANCE or TECHNICIAN (with executor semantics) calls the work-log paths, then OPA allows; AUDITOR is denied on mutation paths.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=WorkLogServiceTest,WorkLogServiceIntegrationTest,WorkOrderTransitionServiceTest,WorkOrderServiceTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full backend suite green (V1+V2+V3+V4 apply cleanly)
- `cd syncro && npx opa test authz/policy` -- expected: all OPA tests pass (or docker OPA run)

**Manual checks (if no CLI):**
- Inspect `V4__work_log_audit_type.sql` for additive-only CHECK extension
- Confirm `AuditEntityType.WORK_LOG` matches the SQL CHECK value
- Confirm `WorkLogServiceIntegrationTest` asserts persisted work_logs row, audit_log WORK_LOG row, work_orders.mttr_minutes recomputed, and backdate rejection via JDBC on real Postgres
