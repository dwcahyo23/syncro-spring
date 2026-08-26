---
title: 'Repair Sessions & MTTR'
type: 'feature'
created: '2026-08-26'
status: 'done'
baseline_revision: 3a2c9d5
final_revision: 5ffdfb9
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-10-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-10-3-status-lifecycle-and-on-procurement.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Workorders can be created, assigned and transitioned (10-2/10-3), but repair time cannot be recorded — so MTTR, the DONE-without-session guard, and category SLA response time (FR-115/FR-123) are unimplemented.

**Approach:** Add a `repair_sessions` table (V49, with a Postgres EXCLUDE constraint so sessions can never overlap), start/stop/list session endpoints, backend-computed MTTR (cumulative completed durations) and SLA response time (OPEN → first session start) persisted on the workorder, a DONE gate requiring a completed session or a documented reason, and a per-category target response time settable through the existing category endpoints. Backend owns every calculation (NFR-P2-2); no frontend work yet (no workorder feature module exists).

## Boundaries & Constraints

**Always:**
- **V49** (additive): `repair_sessions(id, work_order_id, technician_id, description, started_at, ended_at, duration_minutes, created_at, updated_at)`; `excl_repair_sessions_no_overlap` EXCLUDE USING gist (work_order_id WITH =, tstzrange(started_at, ended_at) WITH &&) — DB-enforced non-overlap incl. at most one open (unbounded) session; `ck_repair_sessions_end_after_start`, `ck_repair_sessions_duration` (ended → duration_minutes NOT NULL ≥ 0); index on work_order_id. `work_orders` ADD `mttr_minutes BIGINT NULL`, `response_time_minutes BIGINT NULL`, `done_reason VARCHAR(1000) NULL`. `work_order_categories` ADD `target_response_minutes INT NULL`. audit_log entity_type CHECK widened with `REPAIR_SESSION` (drop/re-add pattern).
- **Timestamps server-side only** (project rule): `startedAt`/`endedAt` always `Instant.now(clock)` — never client-supplied. `duration_minutes = Duration.between(startedAt, endedAt).toMinutes()` (truncated, ≥ 0).
- **Start session** `POST /api/v1/workorders/{id}/sessions` — body `{description?}` (@Size(max=2000)). Loads workorder `findByIdForUpdate` (serializes with transitions/derivations). Requires status == IN_PROGRESS else `WORKORDER_NOT_IN_PROGRESS` (409); access = in-scope leader OR assigned executor (reuse `isInScopeLeader` + `isExecutor`, both sources allowed — sessions are local operational fields, AD-3 "preserved", no status/sync_version change). Existing open session → `SESSION_ALREADY_OPEN` (409); app-level overlap pre-check → `SESSION_OVERLAP` (409) with DB EXCLUDE as authoritative backstop. On first-ever start, compute `response_time_minutes = startedAt − MIN(status_history.transitioned_at where to_status='OPEN')` (source-agnostic; skip when no OPEN history row). Writes audit CREATE `REPAIR_SESSION`. Returns full `RepairSessionsView`.
- **Stop session** `POST /api/v1/workorders/{id}/sessions/stop` — no body. Loads workorder `findByIdForUpdate`; access gate same as start (stop allowed in any status — closing a time interval is always safe). No open session → `NO_OPEN_SESSION` (409). Sets endedAt=now + duration_minutes; recomputes `mttr_minutes = SUM(duration_minutes)` over completed sessions; saves workorder. Writes audit UPDATE `REPAIR_SESSION` (prev/new duration). Returns `RepairSessionsView`.
- **List sessions** `GET /api/v1/workorders/{id}/sessions` — any authenticated user (workorder read posture); ordered by `started_at ASC`. Returns `RepairSessionsView(workorder, sessions)`. No scope gate on reads.
- **DONE gate (FR-115)** in `transition()` when `toStatus == DONE`: open session present → `SESSION_OPEN_CONFLICT` (409); zero completed sessions and blank `reason` → `DONE_WITHOUT_SESSION_REASON_REQUIRED` (400). Non-blank reason persisted into `work_orders.done_reason` (and already rides audit new_value from 10.3).
- **Category target (FR-123):** `WorkOrderCategoryService`/DTOs accept optional `targetResponseMinutes` on create/update and expose it on the category view. `WorkOrderView` gains `mttrMinutes`, `responseTimeMinutes`, `doneReason`.
- **Rego:** `workorder_session_paths := {"/api/v1/workorders/*/sessions", "/api/v1/workorders/*/sessions/stop"}` allowed for {STAFF_MAINTENANCE, TECHNICIAN, SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE} (SUPER_ADMIN bypass; same role set as transitions — the service gate is authoritative, rego is coarse). `.env.example` enforced-paths += both paths; GET sessions covered by generic `read_allowed` + parity test.
- **Errors:** unknown workorder → 404 `WORKORDER_NOT_FOUND`; session overlap/conflict/state → 409 codes above; access → 403 `FORBIDDEN`. DB `excl_repair_sessions_no_overlap` violation surfaces as 409 `SESSION_OVERLAP` via a `DataIntegrityViolationException` cause-chain check (isIdempotencyKeyViolation pattern).

**Block If:** nothing.

**Never:**
- Never touch V47/V48 or add V50 — V49 is the only migration for 10.4.
- Never implement workorder GET list/detail, MTBF or dashboards (14.2), evidence (10.5), ratings (10.8), todos/kanban (10.7).
- Never compute MTTR or SLA on the frontend (NFR-P2-2).
- Never let sessions overlap or double-count; DB EXCLUDE + service checks both enforce.
- Never mutate status or sync_version for sessions (sessions are local operational fields on both sources).
- Never accept client timestamps or poll for recomputation — start/stop are event-driven on the server clock.
- Never regress the 10.3 transition/derivation logic; the DONE gate is additive.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| SESSION_START_OK | IN_PROGRESS, assigned executor, no open session | 200 RepairSessionsView; session persisted (technicianId=user, description); response_time computed on first start | — |
| SESSION_START_ALREADY_OPEN | open session exists | 409 SESSION_ALREADY_OPEN | — |
| SESSION_START_OVERLAP | closed session with ended_at > now | 409 SESSION_OVERLAP (service check + DB EXCLUDE backstop) | — |
| SESSION_START_NOT_IN_PROGRESS | status ≠ IN_PROGRESS | 409 WORKORDER_NOT_IN_PROGRESS | — |
| SESSION_START_FORBIDDEN | not executor nor in-scope leader | 403 FORBIDDEN | — |
| SESSION_START_NOT_FOUND | unknown id | 404 WORKORDER_NOT_FOUND | — |
| SESSION_STOP_OK | open session, leader/executor | 200 RepairSessionsView; endedAt+duration set; mttr_minutes recomputed; audit UPDATE | — |
| SESSION_STOP_NO_OPEN | no open session | 409 NO_OPEN_SESSION | — |
| SESSION_STOP_FORBIDDEN | no access | 403 FORBIDDEN | — |
| SESSION_LIST | GET /{id}/sessions | 200 {workorder, sessions[]} ordered by startedAt | — |
| DONE_NO_SESSION_REASON | IN_PROGRESS→DONE, zero sessions, blank reason | 400 DONE_WITHOUT_SESSION_REASON_REQUIRED | — |
| DONE_NO_SESSION_WITH_REASON | same + non-blank reason | 200 DONE; done_reason persisted | — |
| DONE_SESSION_OPEN | IN_PROGRESS→DONE while session open | 409 SESSION_OPEN_CONFLICT | — |
| DONE_WITH_COMPLETED_SESSION | ≥1 completed session | 200 DONE; mttr_minutes set | — |
| SLA_COMPUTED | first session start; category has target | response_time_minutes stored on workorder; view exposes it | — |
| CATEGORY_TARGET_SET | category create/update with targetResponseMinutes | persisted, returned in category view | — |

</intent-contract>

## Code Map

**Migration:**
- `resources/db/migration/V49__repair_sessions_mttr.sql` -- NEW -- repair_sessions table + EXCLUDE overlap constraint + work_orders mttr/response_time/done_reason + categories target_response_minutes + audit CHECK widen.

**Domain/service:**
- `com/syncro/maintenance/domain/workorder/RepairSession.java` -- NEW -- domain record (id, workOrderId, technicianId, description, startedAt, endedAt, durationMinutes).
- `com/syncro/maintenance/infrastructure/db/RepairSessionEntity.java` -- NEW -- JPA entity.
- `com/syncro/maintenance/infrastructure/db/RepairSessionRepository.java` -- NEW -- findByWorkOrderIdOrderByStartedAt, findFirstByWorkOrderIdAndEndedAtIsNull, sumCompletedDuration (@Query COALESCE(SUM(durationMinutes)) where endedAt IS NOT NULL).
- `com/syncro/maintenance/infrastructure/db/WorkOrderStatusHistoryRepository.java` -- MODIFY -- findFirstOpenTransitionedAt(@Query MIN(transitionedAt) where toStatus='OPEN').
- `com/syncro/maintenance/infrastructure/db/WorkOrderEntity.java` -- MODIFY -- mttrMinutes/responseTimeMinutes/doneReason fields + setters.
- `com/syncro/maintenance/infrastructure/db/WorkOrderCategoryEntity.java` -- MODIFY -- targetResponseMinutes field (ctor + update).
- `com/syncro/maintenance/domain/workorder/WorkOrder.java` -- MODIFY -- record += mttrMinutes, responseTimeMinutes, doneReason.
- `com/syncro/maintenance/application/WorkOrderMapper.java` -- MODIFY -- map new fields.
- `com/syncro/maintenance/application/WorkOrderService.java` -- MODIFY -- startSession/stopSession/listSessions + session access gate + DONE gates + MTTR/SLA recompute + session audit; constructor += RepairSessionRepository (test constructors updated).
- `com/syncro/maintenance/application/WorkOrderCategoryService.java` -- MODIFY -- persist targetResponseMinutes.

**API:**
- `com/syncro/maintenance/api/WorkOrderController.java` -- MODIFY -- POST /{id}/sessions, POST /{id}/sessions/stop, GET /{id}/sessions.
- `com/syncro/maintenance/api/WorkOrderDtos.java` -- MODIFY -- StartSessionRequest, RepairSessionView, RepairSessionsView; WorkOrderView += mttrMinutes/responseTimeMinutes/doneReason.
- `com/syncro/maintenance/api/WorkOrderExceptionHandler.java` -- MODIFY -- SESSION_ALREADY_OPEN / NO_OPEN_SESSION / SESSION_OVERLAP / WORKORDER_NOT_IN_PROGRESS / DONE_WITHOUT_SESSION_REASON_REQUIRED / SESSION_OPEN_CONFLICT + DataIntegrityViolationException exclude-constraint backstop.
- `com/syncro/maintenance/api/WorkOrderCategoryDtos.java` -- MODIFY -- targetResponseMinutes on request/view.

**Audit:**
- `com/syncro/audit/domain/AuditEntityType.java` -- MODIFY -- REPAIR_SESSION.

**Enforcement:**
- `syncro/authz/policy/authz.rego` -- MODIFY -- workorder_session_paths + role rule.
- `syncro/authz/policy/authz_test.rego` -- MODIFY -- parity cases (technician/staff allowed; auditor denied; read allowed).
- `syncro/.env.example` -- MODIFY -- session paths in enforced-paths.

**Tests:**
- `com/syncro/maintenance/application/WorkOrderRepairSessionServiceTest.java` -- NEW -- start/stop/list matrix, DONE gates, MTTR/SLA with fixed Clock, overlap/open checks, access gates, audit.
- `com/syncro/maintenance/application/WorkOrderTransitionServiceTest.java` -- MODIFY -- DONE gate cases + constructor.
- `com/syncro/maintenance/application/WorkOrderServiceTest.java` -- MODIFY -- constructor.
- `com/syncro/maintenance/api/WorkOrderControllerTest.java` -- MODIFY -- session endpoint 200/403/409/404/400 shapes.
- `com/syncro/db/RepairSessionsMigrationTest.java` -- NEW -- V49 table/EXCLUDE/columns/audit CHECK (Testcontainers).

## Tasks & Acceptance

**Execution:**
- [ ] V49 migration + RepairSession entity/repo + WorkOrderEntity/Category/StatusHistory repo changes.
- [ ] `WorkOrderService.startSession` -- lock, IN_PROGRESS gate, session access, open/overlap checks, SLA compute, audit CREATE.
- [ ] `WorkOrderService.stopSession` -- lock, open-session lookup, endedAt+duration, MTTR recompute, audit UPDATE.
- [ ] `WorkOrderService.listSessions` + DONE gate (open-session conflict + no-completed-session reason) + done_reason persist.
- [ ] Controller + DTOs + exception handler (incl. DB EXCLUDE backstop) + WorkOrderView/Category target.
- [ ] AuditEntityType.REPAIR_SESSION.
- [ ] Rego workorder_session_paths + authz_test.rego + .env.example.
- [ ] Tests (service, transition, controller, V49 migration).

**Acceptance Criteria:**
- Given a workorder IN_PROGRESS assigned to a technician, when the technician starts and stops a repair session, then the session (start/end, description, technician) is persisted, multiple non-overlapping sessions are allowed, and cumulative completed duration equals the workorder MTTR computed backend-side. [FR-115/AD-6]
- Given a workorder with no completed session, when a leader transitions it to DONE, then it is blocked with a machine-readable code unless a documented reason is provided, and a non-blank reason is persisted on the workorder; an open session blocks DONE. [FR-115]
- Given a category defines a target response time and a workorder's first IN_PROGRESS session starts, then response time (OPEN → first session start) is computed and stored backend-side and exposed on the workorder view. [FR-123/AD-6]
- Given overlapping or duplicate open sessions, then the DB EXCLUDE constraint and service checks reject them so MTTR is never double-counted. [FR-115]
- Given OPA enforcement, then the session mutation paths are default-deny with the executor/leader role set and parity tests. [FR-160]

## Spec Change Log

## Review Triage Log

### 2026-08-26 — Review pass (step-04)
- intent_gap: 0
- bad_spec: 0
- patch: 4 (high 1, medium 2, low 1)
- defer: 1 (high 1)
- reject: 6 (medium 3, low 3)
- addressed_findings:
  - `[high]` `[patch]` DONE gate used `sumCompletedDuration > 0` as "has completed session" — a genuinely completed session truncated to 0 minutes was treated as absent and forced a documented reason. Fixed: `countByWorkOrderIdAndEndedAtIsNotNull > 0` (repo derived query) so any completed session satisfies the gate regardless of duration.
  - `[medium]` `[patch]` REPAIR_SESSION audit rows keyed `entity_id` by a namespaced hash of `"session-"+uuid`, unreachable by the session's real UUID; `entity_label` was the workorder id. Fixed: audit `entity_id` now the session's actual UUID (sessions already have UUID ids; the hash pattern exists for VARCHAR workorder PKs).
  - `[medium]` `[patch]` Category update silently nulled `targetResponseMinutes` when the client omitted it. Fixed: PATCH-style partial update — the field is only overwritten when non-null.
  - `[low]` `[patch]` Session `description` omitted from audit `sessionValues`. Fixed: added to the map.
- deferred_findings: appended to deferred-work.md:
  - `[high]` V49 `CREATE EXTENSION btree_gist` requires superuser; local/infra Postgres (postgres:17-alpine) applies it (migration test passes), but managed Postgres (RDS/Cloud SQL) needs a pre-deployment extension grant — documented in Design Notes.
- rejected_findings (silently dropped): overlap pre-check inspects only the latest session (EXCLUDE constraint is the authoritative backstop, documented in code); `.env.example` explicit session paths redundant with `/api/v1/workorders/**` (spec-intended); migration test reuses static workorder id under @Transactional (repo-wide V48 pattern); SYNCED ON_PROCUREMENT can't auto-resume (can't-happen, manual transitions forbidden on SYNCED); stopSession session row not locked (workorder lock serializes); zero-duration edge already covered by the count fix.

## Design Notes

- **Sessions on both sources:** unlike manual transitions (10.3), repair sessions are local operational fields — the AD-3 "preserved" class (same as evidence/ratings). They never touch status or sync_version, so they are allowed on SYNCED workorders; the status gate (IN_PROGRESS for start) and the executor/leader access gate still apply.
- **Non-overlap is DB-owned:** the gist EXCLUDE on `(work_order_id, tstzrange(started_at, ended_at))` makes overlap (and a second open session — two unbounded ranges always `&&`) a hard database invariant. The service pre-checks for friendly 409s; a concurrent insert that slips through is caught by the constraint and mapped to `SESSION_OVERLAP` (isIdempotencyKeyViolation pattern).
- **SLA baseline from history, not createdAt:** response time = first session start − MIN(transitioned_at) over `_status_history` rows with `to_status='OPEN'`. Source-agnostic and consistent with AD-4/AD-5 (elapsed time computed from history rows). Internal workorders always have the null→OPEN row (10.2); when no OPEN row exists (rare SYNCED case) the value stays null.
- **MTTR recomputed at stop only:** `mttr_minutes = SUM(duration_minutes)` over completed sessions, persisted on the workorder so 14.2 dashboards can query it; recomputation is event-driven on stop (no poller, no analytic watermark needed at this layer).
- **DONE gate ordering:** open-session check runs first (blocked as `SESSION_OPEN_CONFLICT`), then the zero-completed-sessions check (blank reason → 400). A completed session is one with `ended_at NOT NULL` (count-based, so a 0-minute session still counts — review fix).
- **btree_gist extension (DW-139):** V49 is the first migration to install an extension; local postgres:17-alpine applies it under the default superuser (migration test proves it). Managed PostgreSQL (RDS/Cloud SQL/Supabase) needs a superuser grant before Flyway runs — document in the deploy runbook when cloud infra lands.
- **Session audit identity:** REPAIR_SESSION audit rows use the session's real UUID as `entity_id` (sessions have UUID ids, unlike VARCHAR workorder PKs).

## Verification

**Commands:**
- `mvnd -o -f syncro/apps/backend/pom.xml test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkOrderTransitionServiceTest,WorkOrderRepairSessionServiceTest,RepairSessionsMigrationTest"` -- expected BUILD SUCCESS (per-class JVM, DW-127).
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new session parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green (no frontend changes).

## Auto Run Result

| Step | Outcome | Notes |
|------|---------|-------|
| 01 route | pass | epic 10, story 4; epic-10-context valid; spec-10-3 continuity loaded; clean tree on main |
| 02 plan | pass | spec-10-4 written (V49, session endpoints, MTTR/SLA, DONE gate, category target, rego) |
| 03 implement | pass | full backend impl + tests; 3 test defects fixed (transition DONE-gate stubs, session-list mock, migration FK test ordering); verified independently |
| 04 review | pass | Blind Hunter (10 findings) + Edge Case Hunter (5 findings); deduped to 4 patches (high 1, medium 2, low 1), 1 defer (DW-139), 6 rejects |
| commit | 94a0da1 | `feat(workorder): repair sessions & MTTR (story 10-4)` |
| finalize | 5ffdfb9 | status done; followup_review_recommended: true |

**Summary:** Repair sessions for workorders with DB-enforced non-overlap (V49 gist EXCLUDE), start/stop/list endpoints gated on IN_PROGRESS + executor/leader access; MTTR (cumulative completed durations) and SLA response time (OPEN → first session start) backend-computed and persisted on the workorder; DONE requires a completed session or a documented reason; categories gain optional target_response_minutes; rego session paths + parity tests. Review fixed: count-based DONE gate (0-min sessions count), session-UUID audit entity_id, PATCH-style category target update, description in audit values.

**Files changed:**
- NEW `V49__repair_sessions_mttr.sql` -- repair_sessions + EXCLUDE overlap constraint + work_orders MTTR/SLA/done_reason + category target + audit CHECK.
- NEW `RepairSession.java` / `RepairSessionEntity.java` / `RepairSessionRepository.java` -- session aggregate + persistence (sum, open-session lookup, count).
- MOD `WorkOrderService.java` -- startSession/stopSession/listSessions, session access gate, DONE gate, MTTR/SLA recompute, session audits.
- MOD `WorkOrderController.java`, `WorkOrderDtos.java`, `WorkOrderExceptionHandler.java` -- POST/GET session endpoints, views, new error codes + EXCLUDE backstop.
- MOD `WorkOrderEntity` / `WorkOrderCategoryEntity` / `WorkOrderMapper` / `WorkOrderCategoryService` + DTOs -- MTTR/SLA/done_reason fields, category target.
- MOD `WorkOrderStatusHistoryRepository.java` -- findFirstOpenTransitionedAt.
- MOD `AuditEntityType.java` -- REPAIR_SESSION.
- MOD `authz.rego`, `authz_test.rego`, `.env.example` -- workorder_session_paths + parity.
- Tests: NEW `WorkOrderRepairSessionServiceTest` (15), NEW `RepairSessionsMigrationTest` (9); MOD transition/controller/category tests.

**Review findings:** patches applied 4 (high 1: DONE gate count vs sum; medium 2: session-UUID audit entity_id, category target partial update; low 1: description in audit values). Deferred 1 (DW-139 btree_gist superuser in managed Postgres). Rejected 6 (overlap pre-check by-design w/ EXCLUDE backstop, .env redundancy per spec, migration test static id under @Transactional, SYNCED ON_PROCUREMENT can't-happen, session read lock benign, 0-minute already covered).

**Verification:**
- `mvnd -o ... test "-Dtest=WorkOrderServiceTest,WorkOrderControllerTest,WorkOrderTransitionServiceTest,WorkOrderRepairSessionServiceTest,RepairSessionsMigrationTest,WorkOrderCategoryServiceTest,WorkOrderCategoryControllerTest"` -- BUILD SUCCESS, 133/133 (38 controller + 21 service + 36 transition + 15 session + 9 migration + 7 category svc + 7 category ctrl).
- `cd syncro/authz && ./run-opa-test.ps1` -- PASS 81/81 (incl. session parity).
- `cd syncro/apps/web && npx tsc --noEmit` -- green (no frontend changes).

**Residual risks:** btree_gist extension requires superuser in managed Postgres (DW-139); sessions on SYNCED workorders rely on the service access gate (rego coarse, matching the 10.3 transition precedent); MTTR/SLA persisted values are not recomputed by sync mutations yet (14.2 analytics will own cache invalidation).
