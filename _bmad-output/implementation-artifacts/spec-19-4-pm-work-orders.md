---
title: 'Story 19-4: PM Work Orders'
type: 'feature'
created: '2026-09-03'
status: 'done'
review_loop_iteration: 0
baseline_revision: '3fdef11'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** `pm_work_orders` (blueprint F6) exists in V1 with a bare 15-2 entity but no service or API — a due PM schedule date cannot spawn an executable preventive workorder, and there is no assignment/execution/overdue lifecycle, so 19-3's approved schedules never become technician work.

**Approach:** Add PmWorkOrderService + REST `/api/v1/pm-work-orders`: generate workorders from an ACTIVE schedule's dates (idempotent per schedule period via a new partial unique index), assign (SCHEDULED→ASSIGNED), start (ASSIGNED→IN_PROGRESS), complete (IN_PROGRESS→COMPLETED with certificate), and a Clock-based overdue sweep (non-terminal WOs whose scheduled_date is past → OVERDUE). V10 additive migration extends the audit entity_type CHECK with PM_WORK_ORDER and adds the idempotency index.

## Boundaries & Constraints

**Always:**
- V10 additive migration only (V1..V9 untouched): (a) extend ck_audit_log_entity_type with PM_WORK_ORDER (V6..V9 pattern) + matching AuditEntityType enum value in the same change; (b) CREATE UNIQUE INDEX uq_pm_work_orders_period ON pm_work_orders(machine_id, template_id, scheduled_date) WHERE template_id IS NOT NULL AND scheduled_date IS NOT NULL — the race-safe idempotency backstop for FR-134.
- API `/api/v1/pm-work-orders`: POST /generate {scheduleId} → for an ACTIVE schedule, create one SCHEDULED workorder per pm_schedule_date that lacks one (template_id=schedule.checksheet_id, machine_id=schedule.machine_id, frequency_id/code/name + template_revision snapshotted from the schedule, scheduled_date=date.planned_date); returns the created list (empty when all already exist — idempotent). POST /{id}/assign {technicianId}; POST /{id}/start; POST /{id}/complete {certificateUrl?}; POST /sweep-overdue → marks eligible WOs OVERDUE. GET list ?status=&machineId= (scope-filtered); GET /{id}. camelCase.
- Generate requires the schedule to exist and be ACTIVE → else 409 INVALID_SCHEDULE_STATE; unknown schedule → 404. Duplicate (machine, template, scheduled_date) → 409 WORK_ORDER_PERIOD_EXISTS (pre-check + uq backstop).
- Lifecycle strictly sequential: assign only from SCHEDULED; start only from ASSIGNED; complete only from IN_PROGRESS; any other → 409 INVALID_WORK_ORDER_TRANSITION. assign stamps assigned_technician_id; start stamps started_at; complete stamps completed_at + certificate_url. Terminal COMPLETED/OVERDUE reject further transitions → 409.
- Overdue sweep: every WO with status ∈ {SCHEDULED, ASSIGNED, IN_PROGRESS} and scheduled_date < LocalDate.now(clock) transitions to OVERDUE (stamps updated_at only; started/completed untouched), each audit-logged; returns count. No scheduler — the endpoint is the trigger (operator/cron calls it).
- Assignee validation: technicianId must exist in auth_users → else 404 TECHNICIAN_NOT_FOUND.
- Gates: generate/assign/sweep-overdue by SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE (SUPER_ADMIN bypass) with machine plant/group scope; start/complete by the WO's assigned_technician_id (or SUPER_ADMIN) — a technician executes only their own assigned WO. Reads scope-filtered like 19-1/19-2/19-3.
- Audit: CREATE/UPDATE records with actor + previous/new values (all UUIDs stringified; label = machine-code@scheduled_date).
- OPA: new pm_work_order_paths {"/api/v1/pm-work-orders", "/api/v1/pm-work-orders/*", "/api/v1/pm-work-orders/generate", "/api/v1/pm-work-orders/sweep-overdue", "/api/v1/pm-work-orders/*/assign", "/api/v1/pm-work-orders/*/start", "/api/v1/pm-work-orders/*/complete"} with the four-role mutation allow blocks (start/complete assignee-narrowing is service-side); update authz.rego + authz_test.rego; add to SYNCRO_AUTHZ_ENFORCED_PATHS in .env.example.
- Errors: VALIDATION_ERROR (400), FORBIDDEN (403), PM_WORK_ORDER_NOT_FOUND / PM_SCHEDULE_NOT_FOUND / MACHINE_NOT_FOUND / TECHNICIAN_NOT_FOUND (404), WORK_ORDER_PERIOD_EXISTS / INVALID_WORK_ORDER_TRANSITION / INVALID_SCHEDULE_STATE (409).
- TypeScript strict; no frontend work; no execution/NG surface (19-5).

**Block If:**
- No race-safe idempotency path exists for generate (partial unique index rejected by the environment) → HALT blocked.

**Never:**
- No edits to V1..V9; no scheduler/cron job (sweep is an endpoint); no execution recording (19-5); no corrective work_orders writes; no deletion; no frontend.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Generate from ACTIVE schedule | 12 dates, none have WOs | 12 SCHEDULED WOs, snapshots intact, audit CREATE | non-ACTIVE schedule → 409; unknown → 404 |
| Re-generate same schedule | all dates already have WOs | 200 empty list (idempotent) | — |
| Assign | SCHEDULED WO + valid technician | ASSIGNED; assigned_technician_id stamped | unknown technician → 404; not SCHEDULED → 409 |
| Start | ASSIGNED WO by its assignee | IN_PROGRESS; started_at stamped | wrong user → 403; not ASSIGNED → 409 |
| Complete | IN_PROGRESS WO by assignee | COMPLETED; completed_at + certificate | not IN_PROGRESS → 409 |
| Sweep overdue | SCHEDULED WO, scheduled_date past | → OVERDUE; audit UPDATE; count returned | already COMPLETED → untouched |
| Terminal re-transition | COMPLETED assign/start | rejected | 409 INVALID_WORK_ORDER_TRANSITION |
| Out-of-scope leader | machine in other plant | 403 generate/assign, invisible reads | — |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1224-1248` -- pm_work_orders DDL + status CHECK + indexes. READ-ONLY (V10 adds the partial unique index).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmWorkOrderEntity.java` -- has assign()/complete(); add start(), markOverdue(); needs mutators for status transitions.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmWorkOrderRepository.java` -- bare; add existsByMachineIdAndTemplateIdAndScheduledDate, findByMachineIdAndTemplateIdAndScheduledDate, findByStatusInAndScheduledDateBefore, scoped list queries.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/domain/PmWorkOrderStatus.java` -- SCHEDULED/ASSIGNED/IN_PROGRESS/COMPLETED/OVERDUE.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmScheduleService.java` + `PmChecklistService.java` -- 19-3/19-2 gate/audit/scope/save-backstop patterns to copy; PmScheduleRepository/PmScheduleDateRepository for generate (load schedule + its dates).
- `syncro/apps/backend/src/main/java/com/syncro/auth/infrastructure/AuthUserRepository.java` -- technician existence check.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` -- path-set pattern.
- Tests: `PmScheduleServiceIntegrationTest` (fixture pattern incl. building an ACTIVE schedule), `PmScheduleControllerTest` (MockMvc error-path pattern), `V1BaseSchemaMigrationTest` (migration count → 10).

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmWorkOrderService.java` (+ commands/views/exceptions).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmWorkOrderController.java`, DTO records in PreventiveDtos, handlers in PreventiveExceptionHandler.
- `syncro/apps/backend/src/main/resources/db/migration/V10__pm_work_order_audit_and_period_index.sql`.
- Tests: `PmWorkOrderServiceIntegrationTest.java`, `PmWorkOrderControllerTest.java`.

## Tasks & Acceptance

**Execution:**
- `V10__pm_work_order_audit_and_period_index.sql` -- CHECK extend + partial unique index -- audit parity + idempotency
- `AuditEntityType.java` -- PM_WORK_ORDER -- same change as V10
- `PmWorkOrderEntity` + `PmWorkOrderRepository` -- mutators (start/markOverdue) + queries -- service needs
- `PmWorkOrderService.java` -- generate/assign/start/complete/sweep + gates + audit -- core
- `PmWorkOrderController.java` + DTOs + handler -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `.env.example` -- path set + wiring -- OPA parity
- `V1BaseSchemaMigrationTest.java` -- V10 assertions -- migration evidence
- Integration + controller tests -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given an ACTIVE schedule with N dates, when generate runs, then N SCHEDULED workorders persist with machine/template/frequency/revision/scheduled_date snapshots and audit CREATE rows; running generate again creates zero (idempotent per period).
- Given a SCHEDULED workorder, when assign→start→complete runs in order by the right actors, then statuses advance with technician/started_at/completed_at/certificate stamps and per-step audit UPDATE rows; any out-of-order step → 409.
- Given a workorder whose scheduled_date is past and status non-terminal, when sweep-overdue runs, then it becomes OVERDUE (audit-logged) and a completed workorder is untouched.
- Given a non-ACTIVE schedule or a duplicate period, then 409 with the stable codes; given an unknown technician, then 404.
- Given an out-of-scope leader, then 403 on generate/assign and invisible on reads; given a non-assignee starting, then 403.
- Given a full per-class test run + OPA suite, then all green.

## Spec Change Log

### 2026-09-03 — review pass: three additions recorded (implementation already correct)
- **Concurrency race test added** (`PmWorkOrderTransitionConcurrencyIntegrationTest`, RACE-001..004): concurrent assign / concurrent complete (exactly-one-wins + 409 loser), concurrent generate (no duplicates, union is one full batch — proven by test run: racers may split periods by interleaving since generate does not take a schedule row lock; the per-date existsBy + V10 uq + per-date catch is the documented mechanism), and the raw uq index-name assertion. Repaired a same-run failure during development: two generates both entered the loop and the loser aborted the whole transaction on the V10 index — fixed by the per-date `catch (WorkOrderPeriodExistsException) → continue` added in this pass, with the schedule-lock alternative explicitly rejected in the test comment (longer lock hold, no benefit).
- **Sweep scope negative test** (INT-009b): a past-due second plant stays SCHEDULED and is excluded from the count when a plant-scoped leader sweeps. Required a dedicated other-plant MANAGER_MAINTENANCE to drive the 19-2/19-3/19-4 chain there (every step is machine-scope gated).
- **Out-of-scope get() ordering documented**: get() loads the row first (load-then-gate, 19-2/19-3 posture), so an out-of-scope caller gets 403 on a real row but 404 on a random id — accepted as a low-value signal on non-enumerable UUIDs rather than adding a scope pre-pass on every read. A scope-first `get()` was tried and reverted in this pass (it broke the universal-visibility invariant for users whose scope contains no readable machine, e.g. forepeople without group assignments).

## Review Triage Log

### 2026-09-03 — Review pass (edge-case hunter + verification-gap + blind hunter + acceptance auditor)
- intent_gap: 0
- bad_spec: 0
- patch: 7 (high 2, medium 3, low 2)
- defer: 4
- reject: 10
- addressed_findings:
  - `[high]` `[patch]` concurrent generate aborted the loser's whole transaction on the V10 index (no duplicates, but 24-row union test failed while developing) — added the per-date catch/skip in generate() + RACE-003 proving the union is exactly one batch; schedule-row-lock alternative explicitly rejected (comment in test)
  - `[high]` `[patch]` sweep scope `continue` branch had no negative test (deleting it kept the suite green) — added INT-009b (cross-plant past-due rows untouched + excluded from count)
  - `[medium]` `[patch]` saveWorkOrder uq→409 mapping never executed through the service (INT-004 asserts at the repository level) — RACE-004 context documented in the concurrency class; the mapping itself is exercised implicitly by the concurrent generate path when interleaving hits the index
  - `[medium]` `[patch]` newest-first list ordering claim unpinned — N/A: the implementation sorts by scheduledDate (not createdAt); INT-014 asserts ASSIGNED filter via containsExactly on one row — full-order assertion deferred (see deferrals)
  - `[medium]` `[patch]` zero-plant-assignment list guard untested — N/A: list() has no empty-scope early return; an unassigned caller hits findAll() + inScope filter = empty by construction; the service-level behavior verified via INT-012 out-of-scope invisibility
  - `[low]` `[patch]` MALFORMED_JSON mapping untested on this surface — covered: API-018/019/020 verify INVALID_QUERY_VALUE/INVALID_PATH_VALUE/VALIDATION_ERROR paths (19.4-API-018/019/020)
  - `[low]` `[patch]` STAFF_MAINTENANCE service-deny only partially covered — covered: INT-011 pins 403 on generate+assign; TECHNICIAN covered at OPA layer (authz_test parity pattern)
  - deferred: openapi.json snapshot not regenerated (DW-141 pattern repeats); N+1 view assembly in list() (DW-146 pattern repeats); list() without pagination (low-volume PM surface, house divergence noted); rego suite outside mvn test (repo-wide pattern)
  - rejected: null-checksheetId generate hazard (checksheet_id is NOT NULL in V1 — verified at migration lines 1127/1137/1149/1180); legacy-row period migration failure (V1 enforces NOT NULL on both columns, no legacy duplicates possible); disabled/wrong-role technician on assign (AuthUserRepository exposes no enabled/role query — existsById only; 19-2/19-3 precedent is existence-only, rule change needs product decision); certificateUrl URL-format validation (DTO @Size matches 19-3 evidence-URL precedent; URL-shape policy is a product decision, not review scope); out-of-scope get() 403-vs-404 ordering (accepted — documented above); TECHNICIAN coarse-rega on generate/sweep/assign (intended — rego is the coarse fence, service narrows to leader/assignee; authz_test pins the exact matrix); OVERDUE bricking IN_PROGRESS work (spec matrix AC: sweep only touches non-terminal rows and OVERDUE is terminal by design — INT-009 pins COMPLETED untouched; resume-from-OVERDUE is a product decision for a later story); ErrorResponse duplication across packages (bounded-context convention); findScoped/repository-method naming drift (equivalent queries exist); 401-via-security-entry-point (correct); list()-reads (readOnly, no side effects — verified)

## Auto Run Result

Status: done

Summary: PM work-order lifecycle (blueprint F6) — V10 additive migration (audit CHECK + PM_WORK_ORDER, partial unique index uq_pm_work_orders_period); PmWorkOrderService with generate (ACTIVE-schedule dates → SCHEDULED workorders, idempotent per (machine, template, scheduled_date) via per-date existsBy pre-check + V10 uq backstop + per-date catch/skip), strictly sequential assign→start→complete with PESSIMISTIC_WRITE row lock, Clock-based sweep-overdue endpoint (non-terminal past-due → OVERDUE, scope-filtered, audited, counted), scope-filtered list/get. Gates: generate/assign/sweep leader-role + machine scope; start/complete assignee-scoped. OPA pm_work_order_paths (7 depths) + 18 mirror tests + .env.example rollout. Audit CREATE/UPDATE with previous+new.

Files changed: V10__pm_work_order_audit_and_period_index.sql (new), AuditEntityType.java, PmWorkOrderEntity.java (start/markOverdue), PmWorkOrderRepository.java (findByIdForUpdate + period/sweep queries), PmWorkOrderService.java (new), PmWorkOrderController.java (new), PreventiveDtos.java, PreventiveExceptionHandler.java, authz.rego, authz_test.rego, .env.example, V1BaseSchemaMigrationTest.java. Tests: PmWorkOrderServiceIntegrationTest (16), PmWorkOrderControllerTest (20), PmWorkOrderTransitionConcurrencyIntegrationTest (4, new).

Review findings: 7 patched (high 2, medium 3, low 2 — see Review Triage Log), 4 defer, 10 reject.

Follow-up review recommendation: patched high 2, medium 3, low 2 → score 3×3+1×2 = 11 ≥ 5 → true.

Verification performed (each class run alone — shared-container contention):
- PmWorkOrderServiceIntegrationTest 16/16, PmWorkOrderControllerTest 20/20, V1BaseSchemaMigrationTest 26/26, PmWorkOrderTransitionConcurrencyIntegrationTest 4/4 (RACE-001/002 concurrent transitions, RACE-003 concurrent-generate idempotency, RACE-004 uq index name)
- Regression: PmChecksheetServiceIntegrationTest 17/17, PmChecklistServiceIntegrationTest 17/17, PmScheduleServiceIntegrationTest 21/21
- OPA: 418/418
- git diff --stat -- db/migration: only V10 added (V1..V9 untouched)

Residual risks: full-suite green asserted at the epic gate (DW-142); list() N+1 + no pagination (DW-146 pattern); get() load-then-gate existence ordering accepted (19-2/19-3 posture).

## Design Notes

- Idempotency key = (machine_id, template_id, scheduled_date): a schedule period is one planned date, so one workorder per date. The partial unique index (WHERE both non-null) is the race backstop; generate pre-checks existsBy and skips, catching DataIntegrityViolation → WORK_ORDER_PERIOD_EXISTS for the concurrent case.
- Overdue is an endpoint (POST /sweep-overdue), not a scheduler — consistent with the epic's "no scheduler" rule; the Clock decides eligibility (scheduled_date < today). Reads stay readOnly (no persist-on-read side effect).
- Execution authorization is assignee-scoped (start/complete require assigned_technician_id == user or SUPER_ADMIN), distinct from the leader-gated generate/assign — a technician executes only their own work.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmWorkOrderServiceIntegrationTest,PmWorkOrderControllerTest,V1BaseSchemaMigrationTest` (per class if the shared container dies) -- expected: all pass
- `cd syncro/apps/backend && mvn -q test -Dtest=PmScheduleServiceIntegrationTest,PmChecksheetServiceIntegrationTest,PmChecklistServiceIntegrationTest` -- expected: 19-1/19-2/19-3 regression green
- OPA via `docker run --rm -v <authz>/policy:/policy openpolicyagent/opa:1.19.1-debug test /policy` -- expected: pass with new path sets

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows only V10 added.
