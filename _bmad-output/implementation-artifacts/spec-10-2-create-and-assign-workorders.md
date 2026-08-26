---
title: 'Create & Assign Workorders'
type: 'feature'
created: '2026-08-26'
status: 'in-review'
review_loop_iteration: 0
followup_review_recommended: false
baseline_revision: b4b63b1
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-1-workorder-schema-and-categories.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders can be stored and categorized (10-1) but cannot yet be created or assigned — the core execute line (FR-110/FR-113) is missing.

**Approach:** POST /api/v1/workorders creates internal workorders (WO-YYMM-XXXXX via WorkOrderIdGenerator, source INTERNAL, status OPEN) with an optional Idempotency-Key header deduping within 5 minutes (V48 adds idempotency_key + assigned_technician_id). Creation is gated per role: STAFF_MAINTENANCE (own plant) / SECTION_LEADER (own machine groups) / MAINTENANCE_LEADER / MANAGER_MAINTENANCE / SUPER_ADMIN; PRODUCTION_LEADER restricted to category code "01" (Breakdown) on their own plant machines. Child workorders require access to the parent's machine scope. POST /api/v1/workorders/{id}/assign transitions OPEN → ASSIGNED, stores the executing technician, rejects SECTION_LEADER self-assignment, and writes status-history + audit rows. Rego mirrors the create/assign role set.

## Boundaries & Constraints

**Always:**
- **Create** `POST /api/v1/workorders` — body {categoryCode, machineId, description, parentId?}, header `Idempotency-Key` (UUID, optional). Persists: id=WO-YYMM-XXXXX (generator), source=INTERNAL, status=OPEN, category FK, machine FK, description, parent FK, idempotency_key, createdBy, timestamps. Writes status-history (null→OPEN, MANUAL, actor=creator) and audit CREATE (AuditEntityType.WORK_ORDER).
- **Create gate** (in-service `requireWorkorderCreateRole` + OPA): SUPER_ADMIN unrestricted; MANAGER_MAINTENANCE/MAINTENANCE_LEADER/STAFF_MAINTENANCE require plant scope on the target machine (plantScopes.requirePlantAccess); SECTION_LEADER requires the machine's group ∈ (scope.machineGroupIds ∪ scope.activeTeamIds) (own group, FR-110/AD-2); PRODUCTION_LEADER requires category code == "01" AND plant scope (production line approximated by plant assignment — AD-15; line-level binding is a later story). AUDITOR/TECHNICIAN/INVENTORY_MAINTENANCE/STOREKEEPER denied (403 FORBIDDEN).
- **Idempotency** — on create with a key, `findByIdempotencyKeyAndCreatedAtAfter(key, now-5min)` returns the existing workorder (200/201, same id) instead of a new one; keys expire after 5 minutes naturally (no purge job). Index on idempotency_key.
- **Child** — parentId present → parent must exist and the creator must have scope on the parent's machine group (same check as the machine); cross-source parents allowed (AD-3).
- **Assign** `POST /api/v1/workorders/{id}/assign` — body {assigneeUserId}. Gate: role in {SUPER_ADMIN, MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER} + scope on the workorder's machine (group-in-scope OR plant scope). Status must be OPEN else `INVALID_STATE_TRANSITION` (409). If assigner is SECTION_LEADER and assignee == assigner → `SELF_ASSIGNMENT_FORBIDDEN` (409). Persists assigned_technician_id, status=ASSIGNED, updatedAt; history row OPEN→ASSIGNED (MANUAL, actor=assigner); audit UPDATE/ASSIGN.
- Assignee must exist in auth_users (404 USER_NOT_FOUND); no role gate on assignee (STAFF_MAINTENANCE may execute if assigned, FR-113).
- **V48** (additive): `work_orders` ADD `idempotency_key VARCHAR(64) NULL` + `assigned_technician_id UUID NULL`; `CREATE INDEX idx_work_orders_idempotency_key`; widen audit_log entity_type CHECK to include `WORK_ORDER`.
- **Rego** — `.env.example` enforced-paths += `/api/v1/workorders/**`; `workorder_mutation_paths` (POST/PUT/PATCH/DELETE on /api/v1/workorders/**, incl. /{id}/assign) allowed for roles {STAFF_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE, PRODUCTION_LEADER} (SUPER_ADMIN bypass). Reads (GET, none in 10.2) remain any-authenticated. Category "01"-only and scope checks stay in-service (rego is coarser; enforcement is at the gate). authz_test.rego parity cases added.
- WorkOrderStatusHistoryEntity gains a usable constructor + getters and a `WorkOrderStatusHistoryRepository` (10-1 left it pass-through).
- Errors: gates → FORBIDDEN (403); category/machine/parent/user missing → MACHINE_NOT_FOUND / CATEGORY_NOT_FOUND / PARENT_NOT_FOUND / USER_NOT_FOUND (404); invalid assign state → INVALID_STATE_TRANSITION (409); self-assign → SELF_ASSIGNMENT_FORBIDDEN (409); PRODUCTION_LEADER non-breakdown → BREAKDOWN_CATEGORY_REQUIRED (403); ID exhausted → WORKORDER_ID_EXHAUSTED (503 — new @ExceptionHandler, resolves DW-135).

**Block If:** nothing.

**Never:**
- Never implement GET workorder list/detail or lifecycle beyond create→assign (10.7 kanban, 10.3 lifecycle).
- Never remove the in-service gates (parity + defense-in-depth).
- Never touch 10-1 schema files (V47) — V48 is additive only.
- Never create workorders with source SYNCED (that is FR-111/import, a later story).
- Never persist WAHA secrets/phone numbers or request bodies in audit/history (structural fields only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| CREATE_OK | POST /api/v1/workorders as SECTION_LEADER, machine in own group, no idem key | 201 WO-2409-00001, source INTERNAL, status OPEN, history+audit written | — |
| CREATE_REPLAY | same Idempotency-Key within 5 min | 200/201 same WO id, no duplicate row | — |
| CREATE_EXPIRED_KEY | same key after 5 min | new workorder (new id) | — |
| CREATE_SECTION_LEADER_OUTSCOPE | machine group not in own scope | 403 FORBIDDEN | — |
| CREATE_PRODUCTION_LEADER_OK | category "01", machine in own plant | 201 | — |
| CREATE_PRODUCTION_LEADER_WRONG_CAT | category "02" | 403 BREAKDOWN_CATEGORY_REQUIRED | — |
| CREATE_TECHNICIAN | any body | 403 FORBIDDEN (OPA + service) | — |
| CREATE_CHILD_OK | parentId of scoped machine workorder | 201, parent_id set | — |
| CREATE_CHILD_OUTSCOPE | parent machine out of scope | 403 FORBIDDEN | — |
| ASSIGN_OK | POST /{id}/assign OPEN, SECTION_LEADER, assignee≠self | 200, status ASSIGNED, history+audit | — |
| ASSIGN_WRONG_STATE | status not OPEN | 409 INVALID_STATE_TRANSITION | — |
| ASSIGN_SELF | SECTION_LEADER assigns self | 409 SELF_ASSIGNMENT_FORBIDDEN | — |
| ASSIGN_NOT_FOUND | unknown workorder id | 404 WORKORDER_NOT_FOUND | — |
| ID_EXHAUSTED | generator throws WorkorderIdExhaustedException | 503 WORKORDER_ID_EXHAUSTED | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V48__workorder_create_assign.sql` -- NEW -- ADD COLUMN idempotency_key + assigned_technician_id on work_orders; index on idempotency_key; audit CHECK widen with WORK_ORDER.

**Domain/service:**
- `com/syncro/maintenance/domain/workorder/WorkOrderStatus.java` -- NEW -- enum DRAFT/OPEN/ASSIGNED/IN_PROGRESS/ON_PROCUREMENT/DONE/CLOSED/CANCELLED.
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- MODIFY -- real getters/setters + constructor (currently pass-through), new fields idempotencyKey/assignedTechnicianId.
- `com/syncro/maintenance/infrastructure/db/WorkOrderStatusHistoryEntity.java` -- MODIFY -- usable ctor + getters.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- NEW -- save/findById, findByIdempotencyKeyAndCreatedAtAfter.
- `com/syncro/maintenance/infrastructure/db/WorkOrderStatusHistoryRepository.java` -- NEW -- save.
- `com/syncro/maintenance/application/WorkOrderService.java` -- NEW -- create + assign + gates + scope checks + history + audit.
- `com/syncro/maintenance/application/WorkOrderMapper.java` -- NEW -- entity ↔ WorkOrderView.
- `com/syncro/maintenance/domain/workorder/WorkOrder.java` -- NEW -- domain record.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- NEW -- POST /api/v1/workorders (reads `Idempotency-Key` header), POST /api/v1/workorders/{id}/assign.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- NEW -- CreateWorkOrderRequest, AssignWorkOrderRequest, WorkOrderView, errors.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- NEW -- 404/409/403/503 mapping incl. WORKORDER_ID_EXHAUSTED.

**Audit:**
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- add WORK_ORDER.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- `workorder_mutation_paths` + rule for the five create/assign roles.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases (STAFF/SECTION_LEADER/PRODUCTION_LEADER create allowed; TECHNICIAN/AUDITOR denied; assign SECTION_LEADER allowed).
- `syncro/.env.example` -- MODIFY -- `/api/v1/workorders/**` in enforced-paths.

**Tests:**
- `com/syncro/maintenance/application/WorkOrderServiceTest.java` -- NEW -- create gates (role matrix), scope checks, PRODUCTION_LEADER breakdown rule, idempotency replay+expiry, child scope, assign happy/invalid-state/self-assign.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- NEW -- 201/403/409/404/503 shapes + idempotency header.
- `com/syncro/db/WorkorderCreateAssignMigrationTest.java` -- NEW -- V48 columns/index/CHECK widen.
- `com/syncro/maintenance/domain/workorder/WorkOrderIdGeneratorTest.java` -- MODIFY -- none (existing).

## Tasks & Acceptance

**Execution:**
- [x] V48 migration + WorkOrderStatus enum + entity upgrades (WorkOrderEntity, WorkOrderStatusHistoryEntity) + 2 repositories.
- [x] WorkOrderService.create: gate + scope + PRODUCTION_LEADER breakdown + idempotency + child scope + status history + audit.
- [x] WorkOrderService.assign: gate + OPEN-only + self-assign guard + persistence + history + audit.
- [x] WorkOrderController + DTOs + exception handler (incl. WORKORDER_ID_EXHAUSTED).
- [x] AuditEntityType.WORK_ORDER.
- [x] Rego workorder_mutation_paths + authz_test.rego + .env.example.
- [x] Tests (service, controller, V48 migration).

**Acceptance Criteria:**
- Given an authorized user POSTs /api/v1/workorders, then a WO-YYMM-XXXXX id with source INTERNAL returns; a replay within 5 minutes (same Idempotency-Key) returns the same workorder, and a child requires access to the parent's machine scope. [FR-110/AD-3]
- Given a PRODUCTION_LEADER POSTs, then only category code "01" (Breakdown) is allowed and only for machines in their plant scope. [FR-110]
- Given a SECTION_LEADER assigns, then the workorder transitions OPEN → ASSIGNED, and self-assignment is rejected with a machine-readable code. [FR-113]
- Given OPA enforcement, then workorder create/assign mutations are evaluated by the rego (five-role set allow; TECHNICIAN/AUDITOR deny). [FR-160]

## Spec Change Log

- (step-04) Idempotency dedupe hardened from check-then-insert to a UNIQUE partial index (`uq_work_orders_idempotency_key`, V48) after review found the race; replay now scoped per `created_by` and returns 200.

## Review Triage Log

### 2026-08-26 — Review pass (step-04)
- intent_gap: 0
- bad_spec: 0
- patch: 9 (high 1, medium 5, low 3)
- defer: 0
- reject: 2 (low 2)
- addressed_findings:
  - `[high]` `[patch]` Concurrent same-key POSTs defeated the dedupe (check-then-insert, no unique constraint) and a later windowed replay would hit IncorrectResultSizeDataAccessException → 500. Fixed: UNIQUE partial index `uq_work_orders_idempotency_key` in V48; create catches the violation, re-queries the winner, returns it as a replay.
  - `[medium]` `[patch]` Idempotency key was global — user B could replay user A's key and receive A's workorder (disclosure). Fixed: lookup scoped by `created_by`.
  - `[medium]` `[patch]` SECTION_LEADER could assign workorders in other leaders' groups via the plant-scope branch of requireAssignScope. Fixed: SECTION_LEADER is group-only on assign (parity with create).
  - `[medium]` `[patch]` Assignee existence checked but not role — a MANAGER/AUDITOR could be assigned as executing technician. Fixed: assignee must be TECHNICIAN or STAFF_MAINTENANCE (FR-113).
  - `[medium]` `[patch]` Rego parity gap: STAFF_MAINTENANCE and PRODUCTION_LEADER were allowed at OPA to POST /{id}/assign while the service denies them. Fixed: paths split into workorder_create_paths / workorder_assign_paths; assign rules limited to the leadership roles; two new rego denial tests.
  - `[medium]` `[patch]` CORS preflight rejected the new header. Fixed: `Idempotency-Key` added to SecurityConfig allowedHeaders.
  - `[low]` `[patch]` Key >64 chars hit the VARCHAR(64) column → unhandled 500. Fixed: `@Size(max=64)` + `@Validated` controller + ConstraintViolationException handler returning 400 IDEMPOTENCY_KEY_TOO_LONG.
  - `[low]` `[patch]` Replay returned 201 Created implying a fresh resource. Fixed: CreateResult(workorder, replay) flag; controller returns 200 on replay.
  - `[low]` `[patch]` Assign accepted SYNCED workorders, corrupting the external lifecycle without a sync_version bump. Fixed: source guard rejects non-INTERNAL.
  - reject: body-drift 409 on idempotent replay (first-write-wins is the standard semantics once the key is creator-scoped); key whitespace variants (trimmed once at the single entry point).

## Design Notes

- **Status on create = OPEN**: AD-4 lists DRAFT → OPEN, but 10.2 exposes only create + assign (the AC assigns directly from OPEN); DRAFT semantics arrive with 10.3. Documented so 10.3 can reconcile.
- **PRODUCTION_LEADER line scope**: production lines have no binding table yet (AD-15 permits plant-derived for now); category "01" is the breakdown constant from FR-110. Line-level refinement is deferred.
- **Idempotency via work_orders column** (not a separate table): a keyed replay within the window returns the existing row; the 5-minute window is enforced by the `createdAtAfter` predicate, so no purge job is needed.
- **Rego coarser than service**: rego can't see the request body (category code), so PRODUCTION_LEADER's "01 only" and all scope checks live in-service; rego handles role-level default-deny. This matches the 9-5 parity pattern (gate is authoritative).
- **Resolves DW-135**: WORKORDER_ID_EXHAUSTED gets a 503 handler here (the endpoint that can trigger it arrives).

## Verification

**Commands:**
- `mvn -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkorderCreateAssignMigrationTest"` -- per-class JVM (DW-127). Expected: BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS (46 + new workorder cases).
- `cd syncro/apps/web && npx tsc --noEmit` -- green (no frontend changes).