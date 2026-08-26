---
title: 'Status Lifecycle & ON_PROCUREMENT'
type: 'feature'
created: '2026-08-26'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
baseline_revision: 41277fab
final_revision: ad74eac
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-2-create-and-assign-workorders.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders can be created and assigned (10-2), but status can only move OPEN → ASSIGNED — the rest of the lifecycle (start/resume/complete/close/cancel) and ON_PROCUREMENT derivation are missing, so waiting-for-part time, invalid transitions, and parent-close rules cannot be enforced (FR-114/FR-120).

**Approach:** A single `POST /api/v1/workorders/{id}/transition` drives all manual transitions through an explicit table-driven state machine (AD-4), with role/scope/executor gates per transition. A `recomputeProcurementState(workOrderId)` application method derives ON_PROCUREMENT from sparepart-request readiness behind a port (`SparepartRequestReadinessPort`, no-op until Epic 12), writing DERIVED/SYSTEM history rows under a row lock (AD-5). `DONE → CLOSED` enforces FR-120 (children must be terminal, `SELECT ... FOR UPDATE`; SUPER_ADMIN/MANAGER override with audit-logged reason). No new schema needed — V47/V48 already carry status, history, and source.

## Boundaries & Constraints

**Always:**
- **Transition endpoint** `POST /api/v1/workorders/{id}/transition` — body `{toStatus, reason?, overrideReason?}`. Response is `WorkOrderView` with the new status. Manual transitions write a MANUAL history row (actor = current user) + audit UPDATE row. `toStatus` enum-validated (400 VALIDATION_ERROR).
- **State machine (AD-4)** explicit table. Valid manual targets: `OPEN` (from DRAFT), `IN_PROGRESS` (from ASSIGNED/ON_PROCUREMENT), `ON_PROCUREMENT` (from IN_PROGRESS), `DONE` (from IN_PROGRESS), `CLOSED` (from DONE), `CANCELLED` (from OPEN/ASSIGNED). `OPEN → ASSIGNED` remains exclusive to `POST /{id}/assign` (10-2); the transition endpoint returns `INVALID_STATE_TRANSITION` (409) for `toStatus=ASSIGNED`. Any other invalid from→to → `INVALID_STATE_TRANSITION` (409). Terminal states (DONE/CLOSED/CANCELLED) have no outgoing edges (NFR-P2-9 machine side).
- **Actor gates (FR-114):** executor = currentUserId == `assigned_technician_id` (role TECHNICIAN or STAFF_MAINTENANCE by construction). Executor OR in-scope leader may start (ASSIGNED→IN_PROGRESS), resume (ON_PROCUREMENT→IN_PROGRESS), complete (IN_PROGRESS→DONE). Leader-only (in-scope): IN_PROGRESS→ON_PROCUREMENT, DONE→CLOSED, OPEN/ASSIGNED→CANCELLED, DRAFT→OPEN. Leader scope: SECTION_LEADER group-in-scope only; MAINTENANCE_LEADER/MANAGER_MAINTENANCE group-OR-plant; SUPER_ADMIN unrestricted. Wrong role/scope/executor → `FORBIDDEN` (403). Unknown id → `WORKORDER_NOT_FOUND` (404). Manual transitions on SYNCED workorders rejected (403 FORBIDDEN) — only derived/SYNC transitions apply (10-2 assign precedent).
- **Manual ON_PROCUREMENT (AD-5):** leader placement to ON_PROCUREMENT or manual resume to IN_PROGRESS allowed only when `sparepartReadinessPort.hasLiveNonReadyRequest(id)` is false, else `PROCUREMENT_REQUEST_CONFLICT` (409). Derivation is authoritative; recompute overrides manual states.
- **Parent close (FR-120/AD-3):** on DONE→CLOSED with `parentId != null`, take `SELECT ... FOR UPDATE` on children (`findByParentIdForUpdate`); if any child status ∉ {CLOSED, CANCELLED} → `CHILDREN_NOT_TERMINAL` (409). SUPER_ADMIN or MANAGER_MAINTENANCE may override with a non-blank `overrideReason`, written into the audit `new_value` JSON `{toStatus, overrideReason}`; other roles → `CHILDREN_NOT_TERMINAL`. Override-eligible actor without reason → `OVERRIDE_REASON_REQUIRED` (400).
- **Derivation (AD-5):** `recomputeProcurementState(workOrderId)` — public `@Transactional`; takes `SELECT ... FOR UPDATE` on the workorder row (serializes per-workorder derivations); applies only when current status ∈ {IN_PROGRESS, ON_PROCUREMENT}. Port reports a live non-READY request → transition to ON_PROCUREMENT; status==ON_PROCUREMENT and no live non-READY request → transition to IN_PROGRESS. Each derivation writes history `source=DERIVED, actor=SYSTEM`. Idempotent — no history row when state is unchanged. Epic 12 implements the port and calls recompute on request transition events (never a poller).
- **Port:** `com.syncro.maintenance.application.SparepartRequestReadinessPort` — `boolean hasLiveNonReadyRequest(String workOrderId)`; `NoopSparepartRequestReadinessPort` (`@Component`, returns false) until Epic 12 replaces it.
- **Rego:** `workorder_transition_paths = {"/api/v1/workorders/*/transition"}` allowed for {STAFF_MAINTENANCE, TECHNICIAN, SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE}; SUPER_ADMIN bypass; AUDITOR/PRODUCTION_LEADER/INVENTORY_MAINTENANCE/STOREKEEPER denied. Rego is coarse (can't read body/assignment/scope) — the service gate is authoritative. `.env.example` enforced-paths gains the transition path; `authz_test.rego` parity cases added.

**Block If:** nothing.

**Never:**
- Never touch V47/V48 or add a V49 migration — no schema change is needed for 10.3.
- Never implement GET workorder list/detail, repair sessions, DONE-without-session or stop-time checks (10.4/10.6), reports/FMEA (10.6), todos/kanban (10.7).
- Never remove the in-service gates (parity + defense-in-depth); never let rego bypass service checks.
- Never allow manual transitions on SYNCED workorders or transitions out of terminal states.
- Never poll for ON_PROCUREMENT; derivation is event-driven via the port (AD-5).
- Never persist request bodies, phone numbers, or WAHA secrets in history/audit (structural fields only).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| TRANSITION_OK | ASSIGNED→IN_PROGRESS by assigned TECHNICIAN | 200 status IN_PROGRESS, MANUAL history + audit | — |
| TRANSITION_INVALID | OPEN→IN_PROGRESS | 409 INVALID_STATE_TRANSITION | — |
| TRANSITION_ASSIGN | any→ASSIGNED | 409 INVALID_STATE_TRANSITION (use /assign) | — |
| TRANSITION_FORBIDDEN | IN_PROGRESS→ON_PROCUREMENT by technician | 403 FORBIDDEN | — |
| TRANSITION_NOT_EXECUTOR | ASSIGNED→IN_PROGRESS by unassigned user | 403 FORBIDDEN | — |
| TRANSITION_SYNCED | manual transition on SYNCED workorder | 403 FORBIDDEN | — |
| MANUAL_PROC_CONFLICT | IN_PROGRESS→ON_PROCUREMENT while live non-READY request | 409 PROCUREMENT_REQUEST_CONFLICT | — |
| COMPLETE_OK | IN_PROGRESS→DONE by executor | 200 DONE, history + audit | — |
| CLOSE_CHILD_BLOCK | DONE→CLOSED parent with child OPEN | 409 CHILDREN_NOT_TERMINAL | — |
| CLOSE_OVERRIDE_OK | DONE→CLOSED SUPER_ADMIN with overrideReason | 200 CLOSED, audit includes reason | — |
| CLOSE_OVERRIDE_NO_REASON | MANAGER with non-terminal children, no reason | 400 OVERRIDE_REASON_REQUIRED | — |
| CANCEL_OK | OPEN→CANCELLED by in-scope leader | 200 CANCELLED | — |
| DERIVED_ENTER | IN_PROGRESS + live non-READY request, recompute | ON_PROCUREMENT, DERIVED/SYSTEM history | — |
| DERIVED_RESUME | ON_PROCUREMENT + all READY, recompute | IN_PROGRESS, DERIVED/SYSTEM history | — |
| DERIVED_NOOP | recompute with state unchanged | no new history row | — |
| NOT_FOUND | unknown id | 404 WORKORDER_NOT_FOUND | — |

</intent-contract>

## Code Map

- `com/syncro/maintenance/domain/workorder/WorkOrderStateMachine.java` -- NEW -- explicit valid-transition table (AD-4) + terminal-state set; `can(from, to)`, `isTerminal(status)`.
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- MODIFY -- generic `transitionTo(status, updatedAt)`.
- `com/syncro/maintenance/infrastructure/db/WorkOrderRepository.java` -- MODIFY -- `findByParentIdForUpdate(parentId)` (`@Lock(PESSIMISTIC_WRITE)`).
- `com/syncro/maintenance/application/SparepartRequestReadinessPort.java` -- NEW -- port interface for request readiness.
- `com/syncro/maintenance/infrastructure/db/NoopSparepartRequestReadinessPort.java` -- NEW -- returns false until Epic 12.
- `com/syncro/maintenance/application/WorkOrderService.java` -- MODIFY -- `transition(...)` + `recomputeProcurementState(...)` + actor gates + parent-close + history/audit.
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- `POST /{id}/transition`.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- `TransitionWorkOrderRequest`.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- CHILDREN_NOT_TERMINAL / PROCUREMENT_REQUEST_CONFLICT / OVERRIDE_REASON_REQUIRED mappings.
- `syncro/authz/policy/authz.rego` -- MODIFY -- `workorder_transition_paths` + role rules.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases.
- `syncro/.env.example` -- MODIFY -- transition path in enforced-paths.
- `com/syncro/maintenance/application/WorkOrderTransitionServiceTest.java` -- NEW -- transition matrix, actor matrix, parent-close/override, derivation with fake port.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- 200/403/409/404/400 shapes.

## Tasks & Acceptance

**Execution:**
- [x] `WorkOrderStateMachine` -- table-driven transitions + terminal-state set.
- [x] `WorkOrderEntity.transitionTo` + `WorkOrderRepository.findByParentIdForUpdate`.
- [x] `SparepartRequestReadinessPort` + `NoopSparepartRequestReadinessPort`.
- [x] `WorkOrderService.transition` -- machine check, actor gate, SYNCED guard, manual ON_PROCUREMENT guard, parent-close + override, MANUAL history + audit.
- [x] `WorkOrderService.recomputeProcurementState` -- row lock, DERIVED history, idempotent.
- [x] Controller + DTO + exception-handler codes.
- [x] Rego + authz_test.rego + .env.example.
- [x] Tests (service, controller, rego).

**Acceptance Criteria:**
- Given any workorder, when a manual transition is requested, then valid transitions per `DRAFT → OPEN → ASSIGNED → IN_PROGRESS → ON_PROCUREMENT → IN_PROGRESS → DONE → CLOSED` (+ CANCELLED from OPEN/ASSIGNED) succeed with MANUAL history + audit, and invalid ones return `INVALID_STATE_TRANSITION` (409). [FR-114/AD-4]
- Given an assigned executor or in-scope leader, when they start/resume/complete, then ASSIGNED→IN_PROGRESS / ON_PROCUREMENT→IN_PROGRESS / IN_PROGRESS→DONE succeed; DONE→CLOSED is leader-only; wrong role/scope/executor or SYNCED workorder → FORBIDDEN (403). [FR-114]
- Given `recomputeProcurementState` with a live non-READY request, then the workorder enters ON_PROCUREMENT with a DERIVED/SYSTEM history row, resumes IN_PROGRESS when all requests are READY, is guarded by a row lock, and produces no duplicate history rows. [FR-114/AD-5]
- Given a leader manually places or resumes ON_PROCUREMENT, then it succeeds only when no live non-READY request exists (else 409). [AD-5]
- Given a parent workorder with a non-terminal child, when a leader closes it, then `CHILDREN_NOT_TERMINAL` (409) is returned with children `SELECT ... FOR UPDATE`; SUPER_ADMIN/MANAGER override with an audit-logged reason succeeds. [FR-120/AD-3]
- Given OPA enforcement, then the transition path is default-deny with the five-role allow set and parity tests. [FR-160]

## Spec Change Log

## Review Triage Log

### 2026-08-26 — Review pass (step-04)
- intent_gap: 0
- bad_spec: 0
- patch: 6 (high 3, medium 2, low 1)
- defer: 3 (medium 1, low 2)
- reject: 7 (low 7)
- addressed_findings:
  - `[high]` `[patch]` Manual `transition()` read the workorder unlocked (`findById`) — concurrent transitions (or a transition vs derivation race) both passed the state-machine check from the same snapshot and last-write-wins committed, with duplicate MANUAL history/audit rows. Fixed: transition loads via `findByIdForUpdate`, serializing per-workorder manual mutations with derivations; parent double-close (same class) closed by the same lock.
  - `[high]` `[patch]` `recomputeProcurementState` had no source guard — once Epic 12 wires the real port, a SYNCED workorder in IN_PROGRESS would be flipped to ON_PROCUREMENT without a sync_version bump. Fixed: recompute returns early for non-INTERNAL sources (same invariant as the manual path).
  - `[high]` `[patch]` The enum/type-mismatch handler targeted `com.fasterxml...InvalidFormatException`, which never matches at runtime — Spring Boot 4 ships Jackson 3 (`tools.jackson.core:jackson-databind:3.1.2`, confirmed via dependency tree + javap); the old test only passed via the fragile "Cannot deserialize" message heuristic that also swallowed genuinely malformed bodies into VALIDATION_ERROR and could inject a null fieldErrors key. Fixed: cause-chain check on `tools.jackson.databind.exc.InvalidFormatException`, `Reference.getPropertyName()` (null-safe), empty-fieldErrors falls back to MALFORMED_JSON; message heuristic removed.
  - `[medium]` `[patch]` `TransitionWorkOrderRequest.reason` was accepted end-to-end but silently discarded. Fixed: non-blank reason persisted into the audit `new_value` JSON alongside status.
  - `[medium]` `[patch]` Spec drift: Verification command omitted `WorkOrderTransitionServiceTest` (a developer following it would get green without running 32 transition tests) and Code Map pointed the transition matrix at `WorkOrderServiceTest`. Fixed: command now includes all three classes; Code Map maps the matrix to the NEW test file.
  - `[low]` `[patch]` Derived-transitions-without-audit asymmetry was undocumented. Fixed: Design Notes line records the decision (history is the derived trail; audit stays actor-driven).
- deferred_findings: none recorded this pass in addressed list; defers appended to deferred-work.md:
  - `[medium]` No DB-level/Testcontainers exercise of the two new `@Lock(PESSIMISTIC_WRITE)` finders (mock-only; wrong JPQL or lock misconfiguration would surface late).
  - `[low]` Malformed JWT subject (`UUID.fromString(user.id())`) can 500 instead of 401/403 — pre-existing pattern widened by the executor gate.
  - `[low]` Derived-enter branch untested against the real shipped bean (no-op port until Epic 12 replaces it).

## Design Notes

- **DRAFT reconcile (10-2):** create still materializes OPEN (shipped contract, FR-110 AC). DRAFT → OPEN exists in the machine for canonical completeness but is unreachable today; no create change in 10.3.
- **Executor vs scope:** assigned TECHNICIAN/STAFF_MAINTENANCE act regardless of plant/group scope; leaders need scope. SECTION_LEADER is group-only (10-2 parity fix).
- **Rego coarser than service:** rego can't read the body, assignment, or scope; it grants the five-role allow set on the transition path, and the service gate is authoritative (9-5/10-2 pattern).
- **Derivation port:** no `sparepart_requests` table until Epic 12; the no-op port reports "no live requests", so manual placement works and derivation is a no-op; Epic 12 replaces the bean and calls recompute on request transitions.
- **No V49:** the status CHECK, history source enum, and audit_log already cover 10.3; the override reason rides the audit `new_value` JSON.
- **Derived transitions write history only:** ON_PROCUREMENT↔IN_PROGRESS derivations record a DERIVED/SYSTEM `_status_history` row but deliberately skip the audit_log UPDATE — the history table is the status-change trail; audit stays for actor-driven mutations. Revisit if compliance reporting needs derived legs.

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkOrderTransitionServiceTest"` -- expected BUILD SUCCESS with new transition/derivation/close tests (per-class JVM, DW-127).
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new transition parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green (no frontend changes).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 3; spec-10-3 `ready-for-dev` resumed; clean tree on main |
| 03 implement | pass | state machine, transition endpoint, derivation port + recompute, parent-close/override, rego; verified independently |
| 04 review | pass | Blind Hunter (4 P1/P2-class) + Edge Case Hunter (4 findings); deduped to 6 patches (high 3, medium 2, low 1), 0 intent_gap, 0 bad_spec, 3 defers, 7 rejects |
| commit | ad74eac | `feat(workorder): status lifecycle & ON_PROCUREMENT derivation (story 10-3)` |
| finalize | ad74eac~1 | status done; followup_review_recommended: true |

**Summary:** Manual lifecycle transitions via `POST /api/v1/workorders/{id}/transition` behind an explicit AD-4 table-driven machine with executor-vs-leader gates; ON_PROCUREMENT derived from request readiness through `SparepartRequestReadinessPort` (no-op until Epic 12) under a row lock writing DERIVED/SYSTEM history; FR-120 parent-close with FOR UPDATE children lock and audited SUPER_ADMIN/MANAGER override; rego transition-path set with parity tests. No schema change.

**Files changed:**
- NEW `WorkOrderStateMachine.java` -- AD-4 valid-edge table + terminal set.
- NEW `SparepartRequestReadinessPort.java` / `NoopSparepartRequestReadinessPort.java` -- readiness port + Epic-12 placeholder.
- MOD `WorkOrderService.java` -- transition() (locked load, actor gates, procurement guard, parent close/override) + recomputeProcurementState().
- MOD `WorkOrderEntity.java`, `WorkOrderRepository.java` -- generic transitionTo + PESSIMISTIC_WRITE finders.
- MOD `WorkOrderController.java`, `WorkOrderDtos.java`, `WorkOrderExceptionHandler.java` -- POST /{id}/transition, request DTO, new error mappings incl. Jackson 3 classification.
- MOD `authz.rego`, `authz_test.rego`, `.env.example` -- workorder_transition_paths + parity cases.
- Tests: NEW `WorkOrderTransitionServiceTest` (32), MOD controller (10 new) + service constructor.

**Review findings:** patches applied 6 (high 3: transition row lock vs lost update/double-close, recompute SYNCED guard, Jackson 3 exception classification; medium 2: reason persisted to audit, spec verification/test-map fix; low 1: derived-audit design note). Deferred 3 (DW-136 lock-finder DB test, DW-137 JWT-subject hardening, DW-138 real-port derivation coverage). Rejected 7 (by-design rego coarseness, .env redundancy, DONE-child strictness per FR-120 wording, optional-field semantics, cosmetic error-code shift on 10-2 endpoints, pre-existing traceId fallback).

**Verification:**
- `mvnd -o ... test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkOrderTransitionServiceTest"` -- BUILD SUCCESS, 80/80 (27 controller + 21 service + 32 transition).
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 69/69 (incl. 10 new transition parity cases).
- `cd syncro/apps/web && npx tsc --noEmit` -- green (no frontend changes).

**Residual risks:** PRODUCTION_LEADER stays off the transition path by design; manual ON_PROCUREMENT placement dead-end while a live non-READY request exists is AD-5-sanctioned (derivation authoritative); wrong-typed JSON fields across all three workorder endpoints now return VALIDATION_ERROR instead of MALFORMED_JSON (same 400 family, unpinned by older tests); lock finders proven at mock level only (DW-136).


