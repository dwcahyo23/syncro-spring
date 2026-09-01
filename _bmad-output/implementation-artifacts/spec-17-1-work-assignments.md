---
title: 'Story 17-1: Multi-Technician Work Assignments'
type: 'feature'
created: '2026-09-01'
status: 'done'
review_loop_iteration: 1
baseline_revision: '405cd8fa2770ed88c032a3505f8d7aeab0a268bb'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-17-context.md'
  - '_bmad-output/planning-artifacts/orm-target-blueprint-2026-08-31.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Workorders are assigned to a single technician via `work_orders.assigned_technician_id`; real repair work is multi-technician and reassignment must be traceable, but the `work_assignments` table (blueprint B3, AD-17) is dormant — no service, API, OPA rule, or audit path touches it.

**Approach:** Build the application/API layer over the existing `work_assignments` schema: assign multiple technicians to a workorder, drop (soft-deactivate) an assignment, list assignments, and audit every mutation. First assignment transitions OPEN → IN_PROGRESS (matching the current single-tech `assign()` behavior); subsequent assignments and drops do not re-transition. `work_orders.assigned_technician_id` remains the lead technician display column (FR-113).

## Boundaries & Constraints

**Always:**
- The `work_assignments` table, `WorkAssignmentEntity`, and `WorkAssignmentRepository` already exist (15-2) — reuse them; do not alter the schema (V1 baseline is authoritative).
- New endpoints live under `com.syncro.maintenance` following the existing layered pattern (api/application/domain/infrastructure) and the existing `WorkOrderController` `/api/v1/workorders/{id}/...` surface.
- Role gate mirrors `WorkOrderService.requireAssignRole`/`requireAssignScope` (leadership: SUPER_ADMIN/MANAGER_MAINTENANCE/MAINTENANCE_LEADER/SECTION_LEADER; SECTION_LEADER group-in-scope only; EXTERNAL workorders are owned by the sync module — mutations rejected).
- Executor semantics broaden to "any active `work_assignments` row" for future stories; this story only adds the assignment CRUD + audit.
- First active assignment on an OPEN workorder transitions OPEN → IN_PROGRESS (single transition, one status-history row + audit). Subsequent assignments on an already-started workorder do not transition.
- Every assignment/drop mutation writes an `audit_log` row with actor, previous/new values, plant id, and stashed OPA decision id (existing `AuditLogWriter` pattern).
- `AuditEntityType.WORK_ASSIGNMENT` must be added to BOTH the Java enum and the `audit_log.entity_type` CHECK constraint — the doc comment says any new value must hit both sides in the same change. Use an additive V3 migration for the CHECK (extend, not recreate).
- Assignments target `auth_users` rows with `ApplicationRole.TECHNICIAN` or `STAFF_MAINTENANCE` (same rule as `assign()`). SECTION_LEADER cannot assign themselves (FR-113 parity).
- OPA: add `workorder_assignment_paths` path set to `authz.rego` + tests in `authz_test.rego`, mirroring the existing `workorder_assign_paths` four-role allow set.
- Unique constraint `uq_work_assignments_wo_tech_at` (work_order_id, technician_id, assigned_at) backstops duplicates; a 409 on conflict is the friendly path.

**Block If:**
- The audit CHECK extension cannot be done as an additive migration → HALT blocked (would violate the fresh-V1 baseline rule).

**Never:**
- No schema changes to `work_assignments` itself (V1 already has the right shape).
- No changes to `assigned_technician_id` semantics (lead technician only).
- No hard deletion of assignment rows — drops are `is_active=false` + `dropped_at` (AD-17).
- No bypassing OPA; frontend hide/disable is UX only.
- No new dependencies, no native `<select>` in any UI, no ddl-auto changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| First assignment | OPEN workorder + technician | assignment row created; workorder → IN_PROGRESS; status-history + audit rows written | no error |
| Additional assignment | IN_PROGRESS workorder + another technician | assignment row created; no status transition | no error |
| Drop assignment | active assignment | is_active=false, dropped_at/dropped_by set; lead technician unchanged; audit row | no error |
| Duplicate assign | same technician on same WO within same assigned_at | 409 (unique constraint friendly path) | `ASSIGNMENT_ALREADY_EXISTS` |
| Self-assignment by SECTION_LEADER | leader assigns self | 403/409 rejection | `SELF_ASSIGNMENT_FORBIDDEN` |
| Non-technician assignee | assignee is not TECHNICIAN/STAFF_MAINTENANCE | 403 rejection | `FORBIDDEN` |
| EXTERNAL workorder | source=EXTERNAL | rejection | `FORBIDDEN` |
| Assign to CLOSED/CANCELLED WO | terminal workorder | rejection | `INVALID_STATE_TRANSITION` |
| Drop lead technician | lead has an active assignment | lead no longer derived from active rows | no error (display may fall back) |
| Re-assign same tech, later timestamp | same (WO, tech), different assigned_at | pre-check rejects (unique key includes assigned_at) | `ASSIGNMENT_ALREADY_EXISTS` |
| Concurrent assign | two requests same (WO, tech) | unique constraint backstops; one wins | 409 for loser |

</intent-contract>

## Code Map

### Existing (reuse, do not modify unless listed)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentEntity.java` -- entity with `drop()` method (15-2)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentRepository.java` -- `findByWorkOrderIdOrderByAssignedAtAsc`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkAssignmentParentType.java` -- enum CORRECTIVE_WO
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java` -- existing single-tech `assign()`, role/scope gates, `auditValues`, exception classes
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- existing `POST /{id}/assign`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- DTO records; add new records here
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- error mapping; add new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/domain/workorder/WorkOrderStateMachine.java` -- state transitions (do not change)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- `findByIdForUpdate` for locking
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- `assign()`/`transitionTo()`, `getAssignedTechnicianId()`
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_ASSIGNMENT
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql` -- audit_log CHECK constraint (baseline, do not edit)
- `syncro/apps/backend/src/main/resources/db/migration/V2__auth_user_hardening.sql` -- additive migration pattern to follow
- `syncro/authz/policy/authz.rego` -- `workorder_assign_paths` + role blocks (lines ~123, ~436-452)
- `syncro/authz/policy/authz_test.rego` -- mirror tests
- `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` -- record pattern

### To create/modify
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkAssignmentService.java` -- NEW: assign/drop/list use cases
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkAssignmentService.java` -- NEW: assign/drop/list use cases; use `workOrders.findByIdForUpdate` for concurrency safety on assign/drop; add active-duplicate pre-check `existsByWorkOrderIdAndTechnicianIdAndIsActiveTrue`; null-guard `groupInScope`; verify assignee `isActive`
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentRepository.java` -- add `existsByWorkOrderIdAndTechnicianIdAndIsActiveTrue` finder (active-duplicate pre-check)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add `WorkAssignmentView`, `AssignWorkAssignmentRequest` records (no dead DropWorkAssignmentRequest — drop takes a path variable)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add `POST /{id}/assignments`, `POST /{id}/assignments/{assignmentId}/drop`, `GET /{id}/assignments`; list endpoint OpenAPI schema must be an array (`WorkAssignmentView[].class`)
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add `WORK_ASSIGNMENT`
- `syncro/apps/backend/src/main/resources/db/migration/V3__work_assignment_audit_type.sql` -- NEW additive migration extending audit CHECK
- `syncro/authz/policy/authz.rego` -- add `workorder_assignment_paths` + role allow blocks
- `syncro/authz/policy/authz_test.rego` -- mirror tests incl. drop-path and denied-role coverage for MAINTENANCE_LEADER/SUPER_ADMIN/STAFF_MAINTENANCE/STOREKEEPER/INVENTORY_MAINTENANCE
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkAssignmentServiceTest.java` -- NEW unit tests incl. active-duplicate, null-scope, inactive-assignee, malformed-UUID mapping
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkAssignmentServiceIntegrationTest.java` -- NEW real-Postgres test (AbstractPostgresIntegrationTest): persisted status/history/audit for first-assignment transition; real duplicate-insert → 409; audit_log WORK_ASSIGNMENT INSERT + immutability trigger survives
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/api/WorkOrderControllerTest.java` -- extend with assignment endpoint tests
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkOrderServiceTest.java` -- keep existing assign() behavior green
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion to assert the full V1 value list survives plus WORK_ASSIGNMENT

## Tasks & Acceptance

**Execution:**
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkAssignmentService.java` -- create multi-tech assign/drop/list service with role/scope gates (mirror WorkOrderService), use findByIdForUpdate, add active-duplicate pre-check, null-guard groupInScope, verify assignee isActive -- core use cases
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/infrastructure/db/WorkAssignmentRepository.java` -- add existsByWorkOrderIdAndTechnicianIdAndIsActiveTrue -- active-duplicate pre-check
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderDtos.java` -- add WorkAssignmentView, AssignWorkAssignmentRequest records (no DropWorkAssignmentRequest) -- API contract
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderController.java` -- add endpoints -- expose the service
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- map new exceptions -- stable error codes
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add WORK_ASSIGNMENT -- audit label
- `syncro/apps/backend/src/main/resources/db/migration/V3__work_assignment_audit_type.sql` -- extend audit CHECK additively -- schema/entity parity
- `syncro/authz/policy/authz.rego` -- add assignment path set + role blocks -- OPA enforcement
- `syncro/authz/policy/authz_test.rego` -- add tests incl. drop-path and denied-role coverage -- OPA parity
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkAssignmentServiceTest.java` -- unit tests for the I/O matrix incl. active-duplicate, null-scope, inactive-assignee -- edge cases
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkAssignmentServiceIntegrationTest.java` -- real-Postgres integration test: first-assignment persisted state, duplicate-insert 409, audit_log INSERT -- persisted AC verification
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/api/WorkOrderControllerTest.java` -- endpoint tests -- API contract
- `syncro/apps/backend/src/test/java/com/syncro/maintenance/application/WorkOrderServiceTest.java` -- keep existing assign() tests green -- no regression
- `syncro/apps/backend/src/test/java/com/syncro/db/V1BaseSchemaMigrationTest.java` -- extend CHECK assertion to assert full V1 value list + WORK_ASSIGNMENT -- migration test

**Acceptance Criteria:**
- Given a fresh schema (V1+V2+V3 applied), when a section leader assigns two technicians to an OPEN internal workorder, then two `work_assignments` rows exist with `is_active=true`, `assigned_by`/`assigned_at` set, the workorder is IN_PROGRESS with exactly one status-history row for OPEN→IN_PROGRESS, and audit_log rows exist for both assignments.
- Given an IN_PROGRESS workorder with one active assignment, when a third technician is assigned, then a new assignment row is created with no status transition (status stays IN_PROGRESS, no new OPEN→IN_PROGRESS history row).
- Given an active assignment, when the section leader drops it, then `is_active=false` with `dropped_at`/`dropped_by` set, the row is not deleted, and an audit_log UPDATE row records the change.
- Given a section leader attempts to assign themselves, when submitted, then it is rejected (no assignment row, no transition).
- Given a non-technician or an EXTERNAL/terminal workorder, when assignment is attempted, then it is rejected with the appropriate error code and no row is written.
- Given a duplicate (work_order_id, technician_id, assigned_at) insert, when attempted, then it is rejected (unique constraint) without corrupting state.
- Given the same technician is re-assigned to the same workorder at a different timestamp (same WO, same tech, different assigned_at), when attempted, then the active-duplicate pre-check rejects with `ASSIGNMENT_ALREADY_EXISTS` before hitting the DB.
- Given the OPA policy, when a TECHNICIAN attempts `POST /{id}/assignments`, then OPA denies (four-role allow set only).

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=WorkAssignmentServiceTest,WorkAssignmentServiceIntegrationTest,WorkOrderControllerTest,WorkOrderServiceTest,V1BaseSchemaMigrationTest` -- expected: all pass
- `cd syncro/apps/backend && mvn -q test` -- expected: full backend suite green (migrations V1+V2+V3 apply cleanly)
- `cd syncro && npx opa test authz/policy` -- expected: all OPA tests pass (assuming opa binary/container; else state fallback)

**Manual checks (if no CLI):**
- Inspect `V3__work_assignment_audit_type.sql` for additive-only CHECK extension
- Confirm `AuditEntityType.WORK_ASSIGNMENT` matches the SQL CHECK value
- Confirm `WorkAssignmentServiceIntegrationTest` asserts persisted work_orders.status = IN_PROGRESS, exactly one work_order_status_history row (MANUAL, OPEN→IN_PROGRESS), and audit_log rows with entity_type WORK_ASSIGNMENT (CREATE) and WORK_ORDER (UPDATE) — all via JDBC on real Postgres

## Spec Change Log

### 2026-09-01 — bad_spec loopback from review pass 1

**Triggering findings:**
- (bad_spec, high) First-assignment OPEN → IN_PROGRESS transition is only mock-verified; the spec's ACs reference persisted state (work_orders.status, work_order_status_history rows, audit_log rows) but the Verification section only runs unit tests.
- (bad_spec, high) Audit immutability + WORK_ASSIGNMENT CHECK acceptance is only asserted via constraint-text string match, never a real audit_log INSERT with entity_type='WORK_ASSIGNMENT'.
- (bad_spec, medium) The spec claims `uq_work_assignments_wo_tech_at` "backstops duplicates" but the key includes `assigned_at`, so re-assigning the same technician at a different timestamp yields two active rows; the spec should require an active-duplicate pre-check.

**What was amended (outside `<intent-contract>`):**
- Verification section: added WorkAssignmentServiceIntegrationTest asserting persisted status/history/audit for the first-assignment transition, a real duplicate-insert mapping to 409, and audit_log WORK_ASSIGNMENT INSERT + immutability trigger.
- Code Map: added integration test file; noted findByIdForUpdate for concurrency; noted active-duplicate pre-check (existsByWorkOrderIdAndTechnicianIdAndIsActiveTrue).
- I/O Matrix: added row for re-assigning same technician at different timestamp → pre-check rejects with ASSIGNMENT_ALREADY_EXISTS.
- Tasks & Acceptance: added active-duplicate pre-check task and integration test task.
- Removed unused DropWorkAssignmentRequest DTO from Code Map.

**Known-bad state avoided:** shipping multi-technician assignment whose single-transition, audit, and duplicate guarantees are only proven against mocks; same-tech double-assign; dead API contract surface.

**KEEP instructions (must survive re-derivation):**
- WorkAssignmentService assign/drop/list logic, role/scope gates, SECTION_LEADER self-assign rejection, EXTERNAL/terminal rejection, maybeTransitionToInProgress (OPEN → IN_PROGRESS, one history row, caller persists + audits), soft-drop via assignment.drop(), constraint-name walk mapping uq_work_assignments_wo_tech_at → AssignmentAlreadyExistsException.
- Endpoint shapes: POST /{id}/assignments (201, body = technicianId), POST /{id}/assignments/{assignmentId}/drop, GET /{id}/assignments (any authenticated read). Drop via POST sub-resource, not DELETE.
- WorkAssignmentView record fields (id, workOrderId, technicianId, assignedBy, assignedAt, droppedAt, droppedBy, isActive).
- Error codes: ASSIGNMENT_ALREADY_EXISTS (409), ASSIGNMENT_ALREADY_DROPPED (409), ASSIGNMENT_NOT_FOUND (404), plus FORBIDDEN / INVALID_STATE_TRANSITION reuse.
- AuditEntityType.WORK_ASSIGNMENT + additive V3 migration (drop/re-add ck_audit_log_entity_type preserving all V1 values).
- authz.rego workorder_assignment_paths with four-role allow set + authz_test.rego mirror tests.
- V1BaseSchemaMigrationTest CHECK assertion — extend to assert full V1 value list survives plus WORK_ASSIGNMENT.

## Review Triage Log

### 2026-09-01 — Review pass
- intent_gap: 0
- bad_spec: 3 (high 2, medium 1)
- patch: 9 (high 1, medium 4, low 4)
- defer: 4
- reject: 4
- addressed_findings:
  - `[high]` `[bad_spec]` First-assignment transition unverified against real DB — spec amended to require WorkAssignmentServiceIntegrationTest
  - `[high]` `[bad_spec]` Audit rows/immutability unverified — spec amended to require real audit_log INSERT test
  - `[medium]` `[bad_spec]` Same-tech re-assign bypasses unique key — spec amended to require active-duplicate pre-check
  - `[high]` `[patch]` assign() lacks findByIdForUpdate — KEEP-captured, re-derived with lock
  - `[medium]` `[patch]` drop() lacks lock/conditional update — KEEP-captured, re-derived with lock
  - `[medium]` `[patch]` groupInScope null-guards missing — re-derived with null checks
  - `[medium]` `[patch]` Assignee isActive not verified — re-derived with active check
  - `[low]` `[patch]` Dead DropWorkAssignmentRequest DTO — removed from spec Code Map
  - `[low]` `[patch]` OpenAPI list schema wrong (single vs array) — fixed in re-derivation
  - `[low]` `[patch]` V1BaseSchemaMigrationTest brittle value list — spec amended
  - `[low]` `[patch]` Missing trailing newlines — re-derived
  - `[medium]` `[patch]` OPA test coverage incomplete (missing role cases) — spec amended
  - `[low]` `[patch]` Malformed-UUID test missing — spec amended
  - deferred: epic-17-context "ASSIGNED" wording (context doc), old WO ID format in fixtures (17-3 changes it), list endpoint no plant-scope check (matches existing read endpoints), gate code duplication with WorkOrderService (design choice)
  - rejected: switch default (complete enum switch), assignee plant/group scope check (out of scope per intent), OPEN-only transition check (intent says "first assignment"), OPA enforcement wiring (deployment config, not story artifact)

## Auto Run Result

Status: done (review pass 1 completed with fixes applied)

**Summary of implemented change:** Multi-technician work assignments over the existing `work_assignments` schema (AD-17). New `WorkAssignmentService` with assign/drop/list use cases, role/scope gates mirroring `WorkOrderService`, first-assignment OPEN → IN_PROGRESS transition (one history row + audit), soft-drop semantics, active-duplicate pre-check, pessimistic locking, audit on every mutation. Three new endpoints, DTOs, error mappings, OPA path set + tests, and an additive V3 migration extending the audit CHECK.

**Files changed:**
- `WorkAssignmentService.java` -- assign/drop/list with gates, locking, audit
- `WorkAssignmentRepository.java` -- active-duplicate finder + atomic deactivateIfActive
- `WorkOrderDtos.java` -- WorkAssignmentView + AssignWorkAssignmentRequest
- `WorkOrderController.java` -- 3 endpoints (assign/drop/list)
- `WorkOrderExceptionHandler.java` -- 3 error codes
- `AuditEntityType.java` -- WORK_ASSIGNMENT
- `V3__work_assignment_audit_type.sql` -- additive CHECK extension
- `authz.rego` -- workorder_assignment_paths + 4-role allow set
- `authz_test.rego` -- 234 tests (16 new)
- `WorkAssignmentServiceTest.java` -- 21 unit tests
- `WorkAssignmentServiceIntegrationTest.java` -- 5 real-DB tests
- `WorkOrderControllerTest.java` -- 116 tests (13 new)
- `V1BaseSchemaMigrationTest.java` -- full value-list assertion

**Review findings breakdown:** bad_spec 3 (addressed via spec amendment + code fixes), patch 9 (all applied), defer 4, reject 4, intent_gap 0. Follow-up recommendation: true (patch score 16).

**Verification performed:**
- `mvn test -Dtest=WorkAssignmentServiceTest,WorkAssignmentServiceIntegrationTest,WorkOrderControllerTest,WorkOrderServiceTest,V1BaseSchemaMigrationTest` -- BUILD SUCCESS, 185 tests 0 failures
- OPA `docker run ... openpolicyagent/opa:1.19.1 test /policy` -- PASS 234/234

**Residual risks:**
- Full `mvn test` has pre-existing failures in `SparepartAlertQueryServiceTeamScopeFilterIntegrationTest` (transient Testcontainers flake) and `AuditLogWiringIntegrationTest.plantMutationsAreAudited` (V1 immutability trigger vs ON DELETE SET NULL cascade) — both unrelated to this story.
- OPA enforcement wiring (SYNCRO_AUTHZ_ENFORCED_PATHS) is deployment config; the rego rules + tests are correct but not auto-verified in CI.

## Design Notes
