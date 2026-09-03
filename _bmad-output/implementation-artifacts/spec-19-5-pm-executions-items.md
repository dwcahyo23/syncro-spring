---
title: 'Story 19-5: PM Executions & Execution Items'
type: 'feature'
created: '2026-09-04'
status: 'done'
review_loop_iteration: 0
baseline_revision: '46bc6dc'
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-19-context.md'
warnings: []
deferred:
  - summary: >-
      N+1 unbounded list() (findAll + per-row work-order/machine/item lookups).
    evidence: >-
      DW-146 pattern repeats; correct per matrix, scales with total executions.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionService.java
    severity: low
  - summary: >-
      openapi.json snapshot not regenerated for the pm-executions surface.
    evidence: >-
      DW-141 pattern repeats; the committed snapshot already omits all PM
      surfaces (repo-wide staleness since story 9-5).
    location: syncro/apps/web/openapi.json
    severity: low
  - summary: >-
      technician_signature_id / technician_signed_at never set; item-level
      ng_resolved_at / per-item spv columns never written.
    evidence: >-
      Capturing technician sign-off at complete and an NG-resolution flow are
      product decisions outside this story's spec (complete() has no signature
      body; no NG-resolution endpoint defined).
    severity: low
  - summary: >-
      PmChecklistService.itemLabel (19-2) carries the same surrogate-pair crash
      fixed in 19-5's itemLabel.
    evidence: >-
      Found while writing 19-5 PATCH 9; out of this story's scope. Tracked as
      DW-147.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmChecklistService.java
    severity: medium
---

<intent-contract>

## Intent

**Problem:** `pm_executions` (F7) and `pm_execution_items` (F8) exist in V1 with bare 15-2 entities but no service or API — a technician cannot record a PM execution against a work order, per-item results (measurement or OK/NG) cannot be captured, non-conforming items cannot be escalated to a corrective work order, and an SPV cannot sign off. 19-4's work orders therefore never produce evidenced preventive results.

**Approach:** Add PmExecutionService + REST `/api/v1/pm-executions`: start an execution on an IN_PROGRESS PM work order (one per work order, unique pm_wo_id), fill per-item results snapshotting the checklist item, complete (roll up NG counters, transition the PM work order to COMPLETED, mark the schedule date EXECUTED, and for each critical NG item create a corrective work order via the 11-3 system-creation path linked through finding_wo_id/blocking_wo_id), and SPV verify (signature + timestamp). V11 additive migration extends the audit entity_type CHECK with PM_EXECUTION and PM_EXECUTION_ITEM.

## Boundaries & Constraints

**Always:**
- V11 additive migration only (V1..V10 untouched): extend ck_audit_log_entity_type with PM_EXECUTION and PM_EXECUTION_ITEM (V6..V10 drop-and-recreate CHECK idiom, preserving every prior value) + matching AuditEntityType enum values in the same change. No schema changes to pm_executions / pm_execution_items (both already carry every F7/F8 column in V1).
- API `/api/v1/pm-executions`: POST /start {pmWoId} → creates an execution (started_at, technician_id = the work order's assignee — a SUPER_ADMIN starting on behalf still binds the assignee) for an IN_PROGRESS PM work order; POST /{id}/items/{itemId}/fill {actualValue?, ok?, ng?, ngNotes?, ngPhotoUrl?, blocked?, blockingWoCode?} (itemId is the CHECKLIST item id; the response row id is the snapshot id); POST /{id}/complete; POST /{id}/verify {spvSignatureId?}; GET /{id} (execution + items); GET list ?pmWoId=&technicianId=. camelCase, UUID opaque.
- Start requires the PM work order to exist (404), be IN_PROGRESS and have an assignee (else 409 INVALID_EXECUTION_STATE — a null-assignee row has no technician to bind); one execution per work order (uq_pm_executions_pm_wo) AND per schedule date (uq_pm_executions_schedule_date) → 409 EXECUTION_ALREADY_EXISTS (pre-check + uq backstops).
- Fill: the item must belong to the execution's work order's checksheet (snapshot source) — resolve the checklist item by id, snapshot sequence/category_name/parameter_text/check_method/input_type/critical_flag/unit/lsl/nominal/usl onto the execution-item row, then record the result. MEASUREMENT input_type requires actualValue; OK_NG requires ok or ng. ng=true requires ngNotes (non-blank). Filling after complete → 409. Fill on an execution not started → 409.
- Complete: rolls up has_ng_items (any item ng) and ng_count; sets completed_at; transitions the PM work order IN_PROGRESS→COMPLETED (via PmWorkOrderService.complete-equivalent state move, assignee-or-SUPER_ADMIN gate already satisfied by the execution's technician); marks the linked schedule date EXECUTED (schedule_date_id set at start from the work order's period — see Design Notes). When any CRITICAL NG item exists, create ONE corrective work order per execution via WorkOrderService system-creation (category "01" breakdown, description referencing the first critical NG's parameter), stamp its id into execution.finding_wo_id and every critical-NG item's blocking_wo_id (finding_wo_id is a single column — one WO per execution is the design, not one per item). A work order with no items filled cannot be completed → 409.
- Verify: SPV sign-off requires the execution completed; stamps spv_verifier_id (= caller), spv_verified_at, spv_signature_id (optional). Gate: SECTION_LEADER/MAINTENANCE_LEADER/MANAGER_MAINTENANCE (SUPER_ADMIN bypass) with the work order's machine scope. Double-verify → 409.
- Gates: start/fill/complete by the assigned technician of the PM work order (or SUPER_ADMIN) — a technician executes only their own work; the execution's technician_id is fixed at start and fill/complete require caller == technician_id. Reads: assigned users (machine scope) or SUPER_ADMIN.
- Audit: CREATE on start; UPDATE on fill/complete/verify (previous+new values, all UUIDs stringified). The corrective work order created at complete is audited by WorkOrderService's own system path (recordSystem), not double-audited here.
- OPA rego: new pm_execution_paths {"/api/v1/pm-executions", "/api/v1/pm-executions/*", "/api/v1/pm-executions/start", "/api/v1/pm-executions/*/items/*/fill", "/api/v1/pm-executions/*/complete", "/api/v1/pm-executions/*/verify"} for the technician + leader roles (SUPER_ADMIN via generic bypass). OPA `*` matches exactly ONE path segment — enumerate each depth. Add mirror tests in authz_test.rego; add the paths to SYNCRO_AUTHZ_ENFORCED_PATHS in .env.example.
- Machine-readable errors: VALIDATION_ERROR (400), FORBIDDEN (403), PM_EXECUTION_NOT_FOUND / PM_WORK_ORDER_NOT_FOUND / PM_CHECKLIST_ITEM_NOT_FOUND / MACHINE_NOT_FOUND (404), EXECUTION_ALREADY_EXISTS / INVALID_EXECUTION_STATE / INVALID_EXECUTION_TRANSITION (409).
- TypeScript strict; no frontend work; no scheduler.

**Block If:**
- The corrective-work-order system-creation cannot be reached without a cross-module private write (WorkOrderService exposes no suitable system path for a non-schedule-linked WO) → HALT blocked with the analysis (do NOT reach into WorkOrderRepository directly).

**Never:**
- No edits to V1..V10; no direct work_orders writes from the preventive module (go through WorkOrderService); no deletion; no frontend; no execution recording on the legacy preventive_checklist_* tables.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Start on IN_PROGRESS WO | WO IN_PROGRESS, no execution yet | 201 execution, started_at + technician stamped, audit CREATE | WO not IN_PROGRESS → 409; existing execution → 409; unknown WO → 404 |
| Fill MEASUREMENT item | started execution, item input_type MEASUREMENT | item row snapshotted + actual_value stored, audit UPDATE | missing actualValue → 400; after complete → 409 |
| Fill OK_NG item | item input_type OK_NG, ng=true | is_ng stored, ngNotes required | blank ngNotes → 400 |
| Complete with critical NG | ≥1 critical NG item filled | WO→COMPLETED, schedule date→EXECUTED, finding WO created + linked, ng_count set | no items → 409 |
| Complete clean | all items OK | WO→COMPLETED, has_ng_items=false, no finding WO | — |
| Verify | completed execution, leader role | spv fields stamped, audit UPDATE | not completed → 409; double verify → 409; non-leader → 403 |
| Wrong technician fills | caller != execution.technician_id | rejected | 403 FORBIDDEN |
| Out-of-scope reader | machine in other plant | invisible on list, 403 on get | — |

</intent-contract>

## Code Map

### Existing (reuse)
- `syncro/apps/backend/src/main/resources/db/migration/V1__orm_foundation_schema.sql:1250-1316` -- pm_executions + pm_execution_items DDL (uq_pm_executions_pm_wo, uq_pm_executions_schedule_date, FKs). READ-ONLY (V11 adds audit values only).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionEntity.java` -- has verifyBySpv() + complete(); add start()/setFindingWoId() mutators as needed.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionItemEntity.java` -- has fill(); add resolveNg()/markBlocked() if the model needs it.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionRepository.java` -- findByPmWoId exists; add findByScheduleDateId, existsByPmWoId, findByIdForUpdate (PESSIMISTIC_WRITE).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmExecutionItemRepository.java` -- findByExecutionIdOrderBySequenceAsc exists; add findByExecutionIdAndChecklistItemId.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmChecklistItemRepository.java` -- source snapshot rows (findByChecksheetIdOrderBySequenceAsc from 19-2).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmWorkOrderService.java` + `PmWorkOrderRepository.java` -- the IN_PROGRESS→COMPLETED move + WO load; PmWorkOrderEntity.getTemplateId() is the checksheet id for item resolution.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/infrastructure/db/PmScheduleDateRepository.java` + `PmScheduleDateEntity.java` -- mark EXECUTED (transitionTo) at complete; the schedule date is resolved from the work order's (machine, template/checksheet, scheduled_date) period.
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/application/WorkOrderService.java:179` -- createSystem(machineId, categoryCode, description, preventiveScheduleId) is the 11-3 system path; it sets preventive_schedule_id (nullable). For a finding WO there is no preventive schedule — either pass null (verify the column is nullable + the unique index tolerates nulls) or add a sibling system method. READ the method before choosing; do NOT write work_orders directly.
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- add PM_EXECUTION + PM_EXECUTION_ITEM.
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `syncro/.env.example` -- path-set pattern.
- Tests: `PmWorkOrderServiceIntegrationTest` (builds an ACTIVE schedule + generates a WO — reuse to reach IN_PROGRESS), `PmChecklistServiceIntegrationTest` (checklist item fixtures), `PmWorkOrderTransitionConcurrencyIntegrationTest` (race pattern), `V1BaseSchemaMigrationTest` (migration count → 11).

### New
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/application/PmExecutionService.java` (+ commands/views/exceptions).
- `syncro/apps/backend/src/main/java/com/syncro/maintenance/preventive/api/PmExecutionController.java` + DTO records in PreventiveDtos + handlers in PreventiveExceptionHandler.
- `syncro/apps/backend/src/main/resources/db/migration/V11__pm_execution_audit_types.sql`.
- Tests: `PmExecutionServiceIntegrationTest.java`, `PmExecutionControllerTest.java`.

## Tasks & Acceptance

**Execution:**
- `V11__pm_execution_audit_types.sql` -- CHECK extend (PM_EXECUTION, PM_EXECUTION_ITEM) -- audit parity
- `AuditEntityType.java` -- two enum values -- same change as V11
- `PmExecutionEntity`/`PmExecutionItemEntity`/repos -- mutators + lock + lookup queries -- service needs
- `PmExecutionService.java` -- start/fill/complete/verify + gates + audit + finding-WO + schedule-date EXECUTED -- core
- `PmExecutionController.java` + DTOs + handler -- API surface
- `syncro/authz/policy/authz.rego` + `authz_test.rego` + `.env.example` -- path set + wiring -- OPA parity
- `V1BaseSchemaMigrationTest.java` -- V11 assertions -- migration evidence
- Integration + controller tests -- every matrix row -- AC evidence

**Acceptance Criteria:**
- Given an IN_PROGRESS PM work order, when start runs, then one execution persists (started_at + technician stamped, audit CREATE); a second start on the same work order is 409; a non-IN_PROGRESS work order is 409.
- Given a started execution, when items are filled (MEASUREMENT with actualValue, OK_NG with ok/ng), then each execution-item row snapshots the checklist item and stores the result with an audit UPDATE; a MEASUREMENT fill without actualValue is 400; an NG fill without notes is 400; a fill after complete is 409.
- Given items filled including a critical NG, when complete runs, then the PM work order moves to COMPLETED, the schedule date moves to EXECUTED, has_ng_items/ng_count roll up, and a corrective work order is created and linked via finding_wo_id + the critical item's blocking_wo_id; a complete with zero filled items is 409.
- Given a completed execution, when a leader verifies, then spv_verifier_id/spv_verified_at (+ optional signature) are stamped with an audit UPDATE; verifying a non-completed execution or double-verifying is 409; a non-leader is 403.
- Given a caller who is not the execution's technician, when they fill/complete, then 403; given an out-of-scope leader, then 403 on get and invisible on list.
- Given a full per-class test run + OPA suite, then all green.

## Spec Change Log

### 2026-09-04 — bad_spec: internal contradictions resolved (code already correct)
- "For each CRITICAL NG item, create one corrective work order" contradicted the Design Note "one per execution" and the single-column `finding_wo_id`. Resolution: ONE finding WO per execution (description references the first critical NG's parameter), stamped onto every critical-NG item's blocking_wo_id. The Always bullet was amended to match.
- "technician_id = caller" was imprecise: it is the work order's ASSIGNEE (a SUPER_ADMIN starting on behalf still binds the assignee). Amended.
- "ng" in the fill DTO is optional (null → false), matching the service coercion and the matrix; the contract line's bare `ng` was a typo. Amended to `ng?`.
- start() has an additional 409 path not in the original matrix: an IN_PROGRESS work order with a null assignee → INVALID_EXECUTION_STATE (technician_id is NOT NULL). Amended into the Always bullet.
- The schedule-date uniqueness (uq_pm_executions_schedule_date) is a second start-race backstop alongside uq_pm_executions_pm_wo. Amended.

## Review Triage Log

### 2026-09-04 — Review pass (build-auto step-04 + /bmad-code-review 4 layers)
- intent_gap: 0
- bad_spec: 1 (medium) — internal contradictions (finding-WO cardinality, technician_id wording, ng optionality, null-assignee 409, schedule-date uq); all resolved in Spec Change Log, code already correct
- patch: 10 (high 1, medium 5, low 4)
- defer: 6
- reject: 10
- addressed_findings:
  - `[high]` `[patch]` OK_NG fill accepted ok=false+ng=false (item "filled" but neither OK nor NG) and ok=true+ng=true — enforce exactly one of ok/ng true for OK_NG items
  - `[medium]` `[patch]` blocked=true accepted without blockingWoCode — require it (traceability of the blocking reference)
  - `[medium]` `[patch]` fill only checked execution.completedAt, not the work order status — a work order swept OVERDUE between start and fill still accepted results; fill now requires the WO still IN_PROGRESS
  - `[medium]` `[patch]` start() catch mapped only uq_pm_executions_pm_wo — a schedule-date collision (uq_pm_executions_schedule_date) escaped as raw 500; both index names now map to EXECUTION_ALREADY_EXISTS
  - `[medium]` `[patch]` complete() retry could create a duplicate finding WO (createSystem is REQUIRES_NEW and commits before the outer tx) — skip createSystem when execution.findingWoId is already set (idempotent retry); orphan-WO-on-rollback documented as accepted (11-3 precedent: the corrective action should survive)
  - `[medium]` `[patch]` itemLabel surrogate-pair crash — text.length()>255 but codePointCount<=255 threw offsetByCodePoints; guard on codePointCount first
  - `[low]` `[patch]` dead findByScheduleDateId removed
  - `[low]` `[patch]` no concurrency test for the start/complete races the javadoc advertises — added PmExecutionTransitionConcurrencyIntegrationTest (double-start loser 409 not 500; double-complete one winner) mirroring 19-4
  - `[low]` `[patch]` itemLabel truncation untested at the new site (19-2 pins the identical rule) — added a >255-char fill audit-label test
  - `[low]` `[patch]` 403 untested at API layer for start/fill/verify (only complete) — added
  - deferred: N+1 unbounded list() (DW-146 pattern repeats); openapi.json snapshot (DW-141 pattern repeats); technician_signature_id/technician_signed_at never set (capturing technician sign-off at complete is a product decision, spec's complete() has no signature body); item-level ng_resolved_at/per-item spv columns never written (no NG-resolution endpoint in this story's scope); INVALID_EXECUTION_TRANSITION conflates 4 causes (observability); reassignment between start and complete (WO's own assignee gate re-checks at complete — acceptable, undocumented edge)
  - rejected: scope NPE on machineGroupIds (OperationalScope contract guarantees non-null sets); free-text blockingWoCode/spvSignatureId not validated (spec never required; matches 19-3 evidence-URL store-as-is precedent); MEASUREMENT actualValue not auto-checked against lsl/usl (technician declares ng — product decision); actualValue-on-OK_NG / ngPhotoUrl-on-OK extra data (harmless, not forbidden); unfilled items invisible on get() (spec returns execution+filled items; progress is UI); view-record duplication (bounded-context convention, every story); itemId semantics (fixed in spec wording); finding-WO actor traceability (SYSTEM by 11-3 design); ng optionality (fixed in spec); technician_id wording (fixed in spec)


## Auto Run Result

Status: done

Summary: PM execution lifecycle (blueprint F7/F8) — V11 additive migration extends the audit entity_type CHECK with PM_EXECUTION + PM_EXECUTION_ITEM (V6..V10 idiom, all prior values preserved). PmExecutionService: start (one execution per work order AND per schedule date — uq_pm_executions_pm_wo + uq_pm_executions_schedule_date both mapped to 409 EXECUTION_ALREADY_EXISTS; WO must be IN_PROGRESS with an assignee; technician_id bound to the WO's assignee), fill (snapshot the checklist item onto the execution-item row, MEASUREMENT needs actualValue, OK_NG requires exactly one of ok/ng true, ng needs notes, blocked needs blockingWoCode, WO must still be IN_PROGRESS), complete (NG rollup, WO→COMPLETED via PmWorkOrderService, schedule date→EXECUTED, one corrective finding WO via WorkOrderService.createSystem for critical NG items — idempotent on retry by reusing execution.findingWoId), verify (leader-gated SPV sign-off, completed-only, double-verify 409). PESSIMISTIC_WRITE row lock on every mutation. OPA pm_execution_paths (6 depths) + 16 tests + .env.example rollout. Audit CREATE/UPDATE with stringified previous+new.

Files changed: V11__pm_execution_audit_types.sql (new), AuditEntityType.java, PmExecutionEntity.java (setFindingWoId), PmExecutionItemEntity.java (fill overload + linkBlockingWo), PmExecutionRepository.java (existsByPmWoId, findByIdForUpdate, findByTechnicianIdOrderByStartedAtDesc), PmExecutionItemRepository.java (findByExecutionIdAndChecklistItemId), PmScheduleDateRepository.java (findByScheduleIdAndPlannedDate), PmExecutionService.java (new), PmExecutionController.java (new), PreventiveDtos.java, PreventiveExceptionHandler.java, authz.rego, authz_test.rego, .env.example, V1BaseSchemaMigrationTest.java. Tests: PmExecutionServiceIntegrationTest (18), PmExecutionControllerTest (22), PmExecutionTransitionConcurrencyIntegrationTest (2, new).

Review findings: 10 patched (high 1, medium 5, low 4 — see Review Triage Log), 1 bad_spec (internal contradictions resolved in Spec Change Log; code already correct), 6 defer, 10 reject.

Follow-up review recommendation: patched high 1, medium 5, low 4 → score 3×5+1×4 = 19 ≥ 5 → true.

Verification performed (each class run alone — shared-container contention):
- PmExecutionServiceIntegrationTest 18/18, PmExecutionControllerTest 22/22, PmExecutionTransitionConcurrencyIntegrationTest 2/2 (double-start loser 409 not 500; double-complete one winner + exactly one finding WO), V1BaseSchemaMigrationTest 27/27 (Flyway at v11), PmWorkOrderServiceIntegrationTest 15/15 (19-4 regression)
- OPA: 434/434
- git diff --stat -- db/migration: only V11 added (V1..V10 untouched)

Residual risks: full-suite green asserted at the epic gate (DW-142); N+1 list() (DW-146 pattern); PmChecklistService.itemLabel carries the same surrogate crash fixed here (DW-147); orphan finding WO on outer-tx rollback accepted (11-3 precedent — corrective action must survive).

## Design Notes

- Schedule-date linkage: pm_executions.schedule_date_id is unique and nullable. At start, resolve the schedule date from the work order's period (machine, template_id, scheduled_date) — the same triple that generated the work order in 19-4 — and store it; complete marks that date EXECUTED. If no matching date exists (a manually-created work order), schedule_date_id stays null and the EXECUTED step is skipped (documented, not an error).
- Finding work order: created only for CRITICAL NG items (is_critical_flag && is_ng), one per execution (the first critical NG's parameter in the description), category "01" (breakdown) via WorkOrderService.createSystem with a null preventive_schedule_id (the column is nullable; the unique index uq_work_orders_preventive_schedule is partial WHERE NOT NULL, so nulls don't collide). Its id is stored in execution.finding_wo_id and echoed onto every critical-NG item's blocking_wo_id. Non-critical NG items are recorded but do not spawn a work order.
- Execution authorization is technician-scoped (start/fill/complete require caller == the work order's assigned_technician_id or SUPER_ADMIN), distinct from the leader-gated verify — mirrors 19-4's assignee-vs-leader split.
- Transitions serialize on findByIdForUpdate (PESSIMISTIC_WRITE) like 19-4; the unique pm_wo_id index is the start race backstop.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest=PmExecutionServiceIntegrationTest` (alone) -- expected: pass
- `cd syncro/apps/backend && mvn -q test -Dtest=PmExecutionControllerTest` (alone) -- expected: pass
- `cd syncro/apps/backend && mvn -q test -Dtest=V1BaseSchemaMigrationTest` (alone) -- expected: pass, count → 11
- `cd syncro/apps/backend && mvn -q test -Dtest=PmWorkOrderServiceIntegrationTest` (alone) -- expected: 19-4 regression green
- OPA via `docker run --rm -v <authz>/policy:/policy openpolicyagent/opa:1.19.1-debug test /policy` -- expected: pass with new path sets

**Manual checks:**
- `git diff --stat -- syncro/apps/backend/src/main/resources/db/migration` shows only V11 added.
