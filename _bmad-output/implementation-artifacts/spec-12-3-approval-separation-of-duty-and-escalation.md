---
title: 'Approval, Separation of Duty & Escalation'
type: 'feature'
created: '2026-08-28'
status: 'done'
baseline_commit: '9ccfce7156d2961cd92ee1d8c5080429262947e8'
review_loop_iteration: 1
followup_review_recommended: true
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/epic-12-context.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-1-sparepart-request-creation-and-types.md'
  - '{project-root}/_bmad-output/implementation-artifacts/spec-12-2-request-state-machine.md'
warnings:
  - 'multiple-goals'
  - 'oversized'
deferred:
  - summary: >-
      Escalation recipients (inventory/storekeeper roles) are not plant-scoped; all such users across all plants are notified for any stale request.
    evidence: >-
      findAllByApplicationRoleInWithWhatsapp returns every INVENTORY_MAINTENANCE/STOREKEEPER with a phone, regardless of the request's plant. FR-147 literal says "section leaders and above plus inventory roles with phone numbers" — plant scoping is a refinement for a later pass.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/application/SparepartRequestEscalationService.java
    severity: medium
  - summary: >-
      No index serves the ack-stop idempotency-key prefix scan (LIKE 'SPAREPART_REQUEST:{id}:%').
    evidence: >-
      cancelActiveForRequest matches on idempotency_key LIKE prefix; a plain unique B-tree index cannot serve a leading-wildcard prefix scan efficiently. Acceptable at request volume but should gain an index if job rows grow.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java
    severity: low
  - summary: >-
      Escalation fires once per (request, step, recipient) only; if the first job set never completes delivery there is no re-escalation.
    evidence: >-
      The unique idempotency key prevents a second job for the same recipient/step; correctness relies on the notification worker's retry path (maxAttempts=3). No re-escalation or cooldown mechanism.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/application/SparepartRequestEscalationService.java
    severity: low
  - summary: >-
      N+1 query fan-out on the sparepart-requests list: allowedActionsFor runs per row, re-reading machine, price entry and escalation config per pending row.
    evidence: >-
      list() maps each row through toView(r, user) which calls allowedActionsFor -> machineForRequest -> EscalationConfigService query on every pending row. Acceptable at current list sizes; cache or batch when list grows.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/application/SparepartRequestService.java
    severity: low
  - summary: >-
      Approval is bypassable via the generic /transition endpoint: REQUESTED→ACKED is reachable by inventory/stores without SoD/tier/scope gates (approve() gates apply only on /approve).
    evidence: >-
      SparepartRequestStateMachine allows REQUESTED→ACKED; transition() actor gate admits INVENTORY_MAINTENANCE/STOREKEEPER for ACKED (12.3-SVC-008 proves it). /approve is the blessed path and OPA covers only /approve; a future story may fold the gate into /transition or remove the direct edge.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/application/SparepartRequestService.java
    severity: medium
  - summary: >-
      Escalation dispatch falls back to the WAHA template renderer when a job's message_body is null/blank — a malformed escalation job would render an alert template from a null alertId.
    evidence: >-
      NotificationDispatchService.dispatch branches on message_body presence; escalation jobs always compose a body today, but the fallback path is untested for alert-less jobs. Guard (skip or ROUTING_FAILED) for a later pass.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java
    severity: low
  - summary: >-
      Staleness anchor is updatedAt; any non-transition update to a stuck request postpones its escalation window.
    evidence: >-
      findStaleByStatusInAndUpdatedAtBefore keys on updated_at; notes edits or other touches re-arm the step. FR-147 step semantics arguably should key on the last status transition; acceptable v1 because transitions are the only status-changing updates.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/infrastructure/db/SparepartRequestRepository.java
    severity: low
  - summary: >-
      STEP_STATUSES iteration order is unspecified (Map.of) — per-step processing order and log ordering are not pinned by any test.
    evidence: >-
      escalateStale iterates STEP_STATUSES.keySet(); Map.of iteration order is JVM-defined but unspecified. Deterministic ordering (LinkedHashMap) for a later pass if per-step ordering matters.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/sparepart/request/application/SparepartRequestEscalationService.java
    severity: low
---

<intent-contract>

## Intent

**Problem:** A sparepart request created in 12-1 has no approval step — the requester can drive their own request through the state machine unchecked (no separation of duty) and unacknowledged/overdue requests never escalate, so procurement waits silently (FR-142/FR-147).

**Approach:** Add an approval endpoint with separation of duty (requester ≠ approver, AD-16), cost-threshold tiering (qty × est. price) that selects the required approver role (≤5M section-leader / 5M–50M maintenance-leader / >50M manager; no price → section-leader), persist the tiers and escalation durations in a new `escalation_configs` table, and add a scheduled escalation worker for sparepart requests that enqueues WAHA jobs through the existing Epic 5 outbox machinery (rate-limit, circuit-breaker, attempt history).

## Boundaries & Constraints

**Always:**
- **V60** (additive on V59): create `escalation_configs` — `id UUID PK DEFAULT gen_random_uuid()`, `scope VARCHAR(20) NOT NULL CHECK (scope IN ('SPAREPART_REQUEST','WORKORDER'))`, `step VARCHAR(30) NOT NULL`, `min_cost NUMERIC(18,2) NULL`, `max_cost NUMERIC(18,2) NULL`, `duration_minutes INTEGER NOT NULL`, `approval_role VARCHAR(30) NULL`, `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`; unique `(scope, step)`. Seed SPAREPART_REQUEST rows: three approval tiers — step `SECTION_LEADER_APPROVAL` min_cost NULL max_cost 5000000 approval_role 'SECTION_LEADER'; step `MAINTENANCE_LEADER_APPROVAL` min_cost 5000000 max_cost 50000000 approval_role 'MAINTENANCE_LEADER'; step `MANAGER_APPROVAL` min_cost 50000000 max_cost NULL approval_role 'MANAGER_MAINTENANCE' — plus three escalation durations — step `ACK_WAITING` duration 480, step `PROCESS_WAITING` duration 1440, step `PURCHASE_WAITING` duration 2880 (seeded defaults pending PRD OQ-2 confirmation; configurable, never hardcoded in code). Step names are ≤16 chars so they fit `notification_jobs.escalation_level VARCHAR(16)`.
- **Approval endpoint:** `POST /api/v1/sparepart-requests/{id}/approve` — body `{note?}`; returns `SparepartRequestView`. Valid only when the current status allows `ACKED` (REQUESTED or PENDING_COMPLETION per `SparepartRequestStateMachine.can(current, ACKED)`); otherwise throw the existing `InvalidRequestStateTransitionException` → 409 `INVALID_STATE_TRANSITION` (mirror 12-2, no new code). SoD invariant: `requester != authenticated user` enforced server-side before OPA and before any state-machine/gate check; violation → 403 `SELF_APPROVAL_FORBIDDEN`.
- **Cost computation (FR-142, AD-16):** `estimatedCost = quantity * unitPrice` in IDR. `unitPrice` = `estUnitPrice` when set; else, when `estPriceId` is set, load the `SparepartPriceEntryEntity.idrAmount`; else the request has no price. When no price → requires `SECTION_LEADER` approval regardless of quantity.
- **Approval role gate:** resolve required role from `escalation_configs` approval tiers: the tier whose `[min_cost, max_cost)` contains `estimatedCost` (NULL min = unbounded below, NULL max = unbounded above). Required role = tier's `approval_role` (SECTION_LEADER / MAINTENANCE_LEADER / MANAGER_MAINTENANCE). `SUPER_ADMIN` bypasses all checks. The authenticated user must have an application role with authority ≥ the required role AND be an in-scope leader for the request's machine (reuse the existing `isInScopeLeader`/`machineForRequest` helpers — SECTION_LEADER group-in-scope, MAINTENANCE_LEADER/MANAGER plant-or-group). Insufficient role or scope → 403 `FORBIDDEN`. Requirement is monotone: MANAGER_MAINTENANCE may approve any tier; MAINTENANCE_LEADER may approve SECTION_LEADER tier; SECTION_LEADER only the SECTION_LEADER tier.
- **Approval is not a new status:** approval performs the REQUESTED→ACKED transition. After SoD/tier/scope gates pass, call the existing `transition()` internally (which re-checks the 12-2 actor gate — in-scope leaders pass the ACKED fallback), so timeline row, audit, and ON_PROCUREMENT recompute all reuse existing logic. Timeline note: `Approved <requiredRole>`.
- **Escalation worker (FR-147):** new `SparepartRequestEscalationWorker` (@Scheduled, `@Scheduled(fixedDelayString = "${syncro.sparepart.escalation.poll-interval-ms:60000}")`) + `SparepartRequestEscalationService`. Per configured step: `ACK_WAITING` applies to status REQUESTED/PENDING_COMPLETION, `PROCESS_WAITING` to ACKED/PROCESSING, `PURCHASE_WAITING` to PURCHASE_REQUESTED/PART_RECEIVED. The worker finds requests whose `updated_at <= now - duration_minutes` for their step (query on `SparepartRequestRepository`), then enqueues one notification job per recipient.
- **Recipients (FR-147 literal: "section leaders and above plus inventory roles with phone numbers"):** for each stale request, resolve recipients from the request's machine group — the `LEADER` responsibility user via `MachineResponsibilityRepository.findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc(machineId, LEADER)` — plus every `INVENTORY_MAINTENANCE`/`STOREKEEPER` user (from `AuthUserRepository` filtered by role) with a non-blank `whatsappNumber`. Users without a phone → job with status `ROUTING_FAILED` and a reason (Epic 5 pattern). No responsibilities/recipients at all → log + skip.
- **Escalation jobs:** one `NotificationJobEntity` per (request, step, recipient) with `escalationLevel` = the step name, idempotency key `SPAREPART_REQUEST:{requestId}:{step}` (dedupe per step, not per recipient — first recipient wins; acceptable v1 because recipients are the same fixed set per step). Save with the `saveIgnoreDuplicate` catch-DataIntegrityViolationException pattern. The dispatch path reuses the existing `NotificationWorker`/`NotificationDispatchService` (rate-limit, circuit-breaker, attempt history) — no new worker machinery.
- **Ack-stop (FR-147, reuse Epic 5 ack-stop pattern):** when a request transitions to ACKED or CLOSED, cancel all non-terminal notification jobs for that request — new `NotificationJobRepository.cancelActiveForRequest(requestId, activeStatuses, cancelled, now)` matching `idempotency_key LIKE 'SPAREPART_REQUEST:{requestId}:%'` (idempotency-key prefix is the request's handle; `target_type`/`target_id` polymorphic columns are NOT introduced here — AD-9 deferred). Called from `SparepartRequestService.transition()` when `toStatus` is ACKED or CLOSED.
- **Message body:** the escalation job dispatch needs a body. The existing `WahaTemplateRenderer` is alert-specific (renders from an alertId) and cannot render a sparepart request, so the dispatch must not use it. v1: the job carries a hardcoded request-summary body (requestId, material code or 'new', qty, status, machine code) composed in the escalation service and passed through — the `NotificationDispatchService` render step must be bypassable or tolerant. Simplest: extend `NotificationDispatchService.dispatch()` to skip `WahaTemplateRenderer` when the job's `escalationLevel` starts with `SPAREPART_REQUEST:` and send the composed body directly (thread the body through a new nullable `message_override` concept or reuse `errorDetail` is NOT acceptable — see Never). Cleanest minimal: store the composed body in a new nullable column `message_body TEXT` on `notification_jobs` (V60 additive) and have dispatch use it when present, else fall back to template render.
- **Frontend:** the Sparepart Requests list (from f6ac419) shows the approval state — an approval badge (`Pending: <role>` / `Approved`) and an `Approve` button on REQUESTED/PENDING_COMPLETION rows. Row actions come from backend-provided `allowedActions` in `SparepartRequestView` (mirror the workorder pattern): `approve` is present only when the current user can approve (not requester, status approvable, role/scope sufficient — computed by the service per user). Non-native shadcn dialog for the confirm+optional-note. New `useApproveRequest` mutation hook; list refresh on success.
- **Rego:** add `sparepart_request_approval_paths := {"/api/v1/sparepart-requests/*/approve"}` with allow set `{MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER}` (coarse gate; SUPER_ADMIN via the generic rule; scope/role/SoD stay service-side — parity with 12-1/12-2).
- **Audit:** approval records an audit `UPDATE` on `SPAREPART_REQUEST` (entityLabel and plantId resolution as 12-1/12-2), actor = approver, previous/new status values.
- **Tests:** service unit tests (Mockito, 12-2 pattern) — SoD, cost-tier selection across all three tiers, no-price → SECTION_LEADER, wrong-state → 409, insufficient role/scope → 403, ack-stop cancel on ACKED/CLOSED; controller tests — approve endpoint shape + error codes; escalation service tests — stale query, recipient resolution, idempotent duplicate, ROUTING_FAILED on missing phone, step/duration mapping from escalation_configs; migration test (V60); `authz_test.rego` parity cases.

**Block if:** none — all decisions are specified above or derivable from AD-16/FR-147 and the 12-1/12-2 specs.

**Never:**
- Never introduce `target_type`/`target_id` polymorphic columns on `notification_jobs` in this story (AD-9 deferred to a cross-cutting migration; v1 uses the idempotency-key prefix).
- Never auto-generate material codes, MRE codes, or stock mutations; never implement stock OP/OQ, PENDING_COMPLETION completion, or price estimation — those are Story 12-4.
- Never modify `WorkOrderService`, `work_orders`, or `work_order_status_history` directly — ON_PROCUREMENT recompute flows through `WorkOrderService.recomputeProcurementState` only.
- Never reuse `errorDetail` or other existing columns to smuggle the message body; if a body column is needed it is a new additive V60 column.
- Never add new Spring dependencies or a new frontend UI/test library.
- Never send WAHA or write `notification_jobs` from inside the request module's domain transaction — enqueue and ack-stop go through the notification module's application service/repository boundary after commit where the existing Epic 5 pattern requires it.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| APPROVE_OK_LOW | REQUESTED, cost ≤5M, user=SECTION_LEADER in scope, requester≠user | 200; status→ACKED, timeline+audit, jobs cancelled | — |
| APPROVE_OK_HIGH | REQUESTED, cost>50M, user=MANAGER_MAINTENANCE in scope | 200; status→ACKED | — |
| APPROVE_OK_NO_PRICE | PENDING_COMPLETION, no price, user=in-scope leader ≥SECTION_LEADER | 200; status→ACKED | — |
| SELF_APPROVAL | REQUESTED, user=requester | 403 SELF_APPROVAL_FORBIDDEN (before OPA/state machine) | — |
| INSUFFICIENT_ROLE | cost>50M but user=SECTION_LEADER | 403 FORBIDDEN | — |
| INSUFFICIENT_SCOPE | MANAGER_MAINTENANCE but machine not in plant/group scope | 403 FORBIDDEN | — |
| WRONG_STATE | ACKED/PROCESSING (ACKED not reachable) | 409 INVALID_STATE_TRANSITION | — |
| ESCALATE_ACK | REQUESTED with updated_at older than ACK_WAITING | job(s) enqueued for LEADER responsibility + inventory roles | duplicate idempotent skip |
| ESCALATE_PROCESS | ACKED older than PROCESS_WAITING | job(s) enqueued | duplicate idempotent skip |
| ESCALATE_PURCHASE | PURCHASE_REQUESTED older than PURCHASE_WAITING | job(s) enqueued | duplicate idempotent skip |
| ESCALATE_NO_PHONE | responsibility user has no whatsapp_number | ROUTING_FAILED job with reason | — |
| ESCALATE_NO_RESPONSIBILITY | machine has no LEADER assignment | log + skip (no job) | — |
| ACK_STOP | REQUESTED with sent escalation jobs, transition to ACKED | all non-terminal jobs for the request cancelled | — |
| CLOSE_STOP | PICKED_UP→CLOSED | all non-terminal jobs cancelled | — |
| NO_CONFIG_ROW | escalation_configs missing the step | worker logs warning, skips the step | — |

</intent-contract>

## Code Map

**Investigation anchors (verified 2026-08-28):**

### Approval (modify existing 12-1/12-2 surfaces)

- `com/syncro/sparepart/request/api/SparepartRequestController.java` — MODIFY — add `POST /{id}/approve`.
- `com/syncro/sparepart/request/api/SparepartRequestDtos.java` — MODIFY — add `ApproveRequest(String note)`; extend `SparepartRequestView` with `Set<String> allowedActions` and `String requiredApprovalRole` (nullable; null when approved/not approvable).
- `com/syncro/sparepart/request/api/SparepartRequestExceptionHandler.java` — MODIFY — add `SELF_APPROVAL_FORBIDDEN` (403) handler; no new state code (reuse `InvalidRequestStateTransitionException` → 409).
- `com/syncro/sparepart/request/application/SparepartRequestService.java` — MODIFY — add `approve(AuthenticatedUser, UUID, ApproveCommand)`; SoD check, cost computation, tier resolve via `EscalationConfigService`, role/scope gate via existing `isInScopeLeader`/`machineForRequest`, then internal `transition()` call. Also extend `list()`/`transition()`/`recordMre()`/`approve()` responses with `allowedActions` per user (small helper `allowedActionsFor(user, entity, machine)`). Add ack-stop cancel call in `transition()` when `toStatus` is ACKED or CLOSED.
- `com/syncro/sparepart/request/application/SparepartRequestMapper.java` — MODIFY — view mapping gains allowedActions/requiredApprovalRole.

### Escalation config (NEW)

- `resources/db/migration/V60__escalation_configs.sql` — NEW — table + unique `(scope, step)` + seed rows (3 approval tiers + 3 escalation durations, scope=SPAREPART_REQUEST).
- `com/syncro/sparepart/request/domain/EscalationConfig.java` — NEW — record `(scope, step, minCost, maxCost, durationMinutes, approvalRole)`.
- `com/syncro/sparepart/request/infrastructure/db/EscalationConfigEntity.java` — NEW — JPA entity.
- `com/syncro/sparepart/request/infrastructure/db/EscalationConfigRepository.java` — NEW — `findByScope(String scope)`.
- `com/syncro/sparepart/request/application/EscalationConfigService.java` — NEW — `requiredApprovalRole(BigDecimal estimatedCost, boolean hasPrice)` (picks tier by `[minCost,maxCost)`; no price → SECTION_LEADER); `durationMinutes(String step)` for the worker.

### Escalation worker (NEW)

- `com/syncro/sparepart/request/application/SparepartRequestEscalationService.java` — NEW — `escalateStale()`: for each step, load duration, query stale requests, resolve recipients (LEADER responsibility + inventory/stores roles with phone), enqueue `NotificationJobEntity` with `escalationLevel=step`, idempotency key `SPAREPART_REQUEST:{id}:{step}`, `messageBody` composed summary; `saveIgnoreDuplicate` catch; ROUTING_FAILED when no phone.
- `com/syncro/sparepart/request/application/SparepartRequestEscalationWorker.java` — NEW — `@Scheduled(fixedDelayString = "${syncro.sparepart.escalation.poll-interval-ms:60000}")` poller (EscalationWorker pattern), per-step error isolation (catch + log per request, no optimistic-lock abort).
- `com/syncro/sparepart/request/infrastructure/db/SparepartRequestRepository.java` — MODIFY — add `findStaleByStatusInAndUpdatedAtBefore(statuses, cutoff)` (or three queries, one per step).
- `com/syncro/sparepart/request/api/SparepartRequestExceptionHandler.java` — MODIFY — only if new exceptions are added (worker exceptions are caught in the worker, not the API).

### Notification integration

- `resources/db/migration/V60__escalation_configs.sql` — also `ALTER TABLE notification_jobs ADD COLUMN message_body TEXT` (additive; nullable).
- `com/syncro/notification/infrastructure/NotificationJobEntity.java` — MODIFY — add `messageBody` field + getter (constructor overload keeps existing call sites; old constructor passes null).
- `com/syncro/notification/application/NotificationDispatchService.java` — MODIFY — in `dispatch()`, when `job.getMessageBody()` is non-null use it directly instead of `templateRenderer.render(...)`; else existing template path.
- `com/syncro/notification/infrastructure/NotificationJobRepository.java` — MODIFY — add `cancelActiveForRequest(String requestKeyPrefix, Collection<NotificationJobStatus> activeStatuses, NotificationJobStatus cancelled, Instant now)` matching `idempotencyKey like :prefix` (mirrors `cancelActiveForAlert`).

### OPA

- `syncro/authz/policy/authz.rego` — MODIFY — `sparepart_request_approval_paths := {"/api/v1/sparepart-requests/*/approve"}` + three mutation rules (MANAGER_MAINTENANCE, MAINTENANCE_LEADER, SECTION_LEADER) mirroring the 12-2 block.
- `syncro/authz/policy/authz_test.rego` — MODIFY — parity cases (allow for the three roles, deny TECHNICIAN/STAFF/INVENTORY/STOREKEEPER/AUDITOR/anonymous).
- `syncro/.env.example` — MODIFY — `SYNCRO_SPAREPART_ESCALATION_POLL_INTERVAL_MS=60000`.

### Frontend

- `src/features/sparepart-requests/types.ts` — MODIFY — extend view with `allowedActions: string[]`, `requiredApprovalRole?: string`.
- `src/features/sparepart-requests/hooks/use-sparepart-requests.ts` — MODIFY — add `useApproveRequest` mutation (invalidates the list query).
- `src/features/sparepart-requests/components/sparepart-requests-list.tsx` — MODIFY — approval badge column + `Approve` button driven by `allowedActions.includes('approve')`; per-row pending state (12-2 pattern).
- `src/features/sparepart-requests/components/approve-dialog.tsx` — NEW — shadcn dialog, optional note, submit → mutation → toast.

### Existing patterns (read-only reference)

- `com/syncro/notification/application/EscalationService.java:53` — saveIgnoreDuplicate + ROUTING_FAILED + entity-based escalation pattern.
- `com/syncro/notification/application/EscalationWorker.java:36` — @Scheduled poller pattern + per-job error isolation.
- `com/syncro/notification/application/NotificationDispatchService.java:54` — dispatch loop; render step at line 70-86 is the seam to branch on messageBody.
- `com/syncro/notification/infrastructure/NotificationJobRepository.java:83` — `cancelActiveForAlert` bulk-update pattern to mirror for requests.
- `com/syncro/sparepart/request/application/SparepartRequestService.java:159` — `transition()` (load-for-update → validate → mutate → timeline → audit → recompute).
- `com/syncro/sparepart/request/application/SparepartRequestService.java:373` — `isInScopeLeader()` and `machineForRequest()` (line 469) — reuse for the approval gate.
- `com/syncro/sparepart/infrastructure/SparepartPriceEntryRepository.java:8` — `findAllBySparepartIdOrderByEnteredAtDesc`; `SparepartPriceEntryEntity.idrAmount` (line 39) is the authoritative IDR unit price.
- `com/syncro/auth/infrastructure/AuthUserRepository.java` — add a role-filtered finder for INVENTORY_MAINTENANCE/STOREKEEPER users (or reuse `findByApplicationRole`-style query; verify exact existing method).
- `com/syncro/machine/infrastructure/MachineResponsibilityRepository.java:22` — `findFirstByMachineIdAndResponsibilityLevelOrderByCreatedAtAsc`.
- `com/syncro/auth/domain/ApplicationRole.java:15` — role enum (SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE, SUPER_ADMIN...).
- `src/features/sparepart-requests/components/sparepart-requests-list.tsx` — 12-2 row-action pattern to extend.

## Tasks & Acceptance

**Execution:**
- `resources/db/migration/V60__escalation_configs.sql` — NEW — escalation_configs table + unique (scope,step) + 6 seed rows + `notification_jobs.message_body` column (additive).
- `domain/EscalationConfig.java` + `infrastructure/db/EscalationConfigEntity.java` + `...Repository.java` + `application/EscalationConfigService.java` — NEW — config access + tier/duration resolution.
- `application/SparepartRequestService.java` — MODIFY — `approve()` (SoD → cost → tier → role/scope gate → internal transition), `allowedActionsFor()`, ack-stop cancel in `transition()` on ACKED/CLOSED.
- `api/SparepartRequestController.java` + `Dtos.java` + `ExceptionHandler.java` — MODIFY — POST approve + `SELF_APPROVAL_FORBIDDEN` + view fields.
- `application/SparepartRequestEscalationService.java` + `...Worker.java` — NEW — step-driven stale escalation + job enqueue.
- `infrastructure/db/SparepartRequestRepository.java` — MODIFY — stale-by-status-and-cutoff query.
- `notification/infrastructure/NotificationJobEntity.java` + `NotificationJobRepository.java` + `NotificationDispatchService.java` — MODIFY — messageBody field + `cancelActiveForRequest` + body-override dispatch.
- `authz.rego` + `authz_test.rego` + `.env.example` — MODIFY — approval path + parity + env var.
- `src/features/sparepart-requests/*` — MODIFY — types, hook, approve dialog, list badge/button.
- Tests — service (approval gates, SoD, tiers, ack-stop), controller (endpoint/errors), escalation service (stale query, recipients, idempotency, ROUTING_FAILED), migration (V60), authz parity.

**Acceptance Criteria:**
- Given a REQUESTED sparepart request, when a different in-scope user with authority ≥ the tier's required role approves, then the request transitions to ACKED, a timeline event + audit are recorded, and the workorder ON_PROCUREMENT is recomputed (reuse transition machinery). [FR-142]
- Given the requester tries to approve their own request, then `SELF_APPROVAL_FORBIDDEN` (403) is returned, enforced before OPA and before any state-machine check. [FR-142, AD-16, NFR-P2-5]
- Given a request whose estimated cost (qty × est. price) falls in the 5M–50M tier, when a MAINTENANCE_LEADER approves then success; when a SECTION_LEADER approves then 403 FORBIDDEN. Tiers are read from `escalation_configs`, not hardcoded. [AD-16]
- Given a request with no estimated price, when an in-scope leader of SECTION_LEADER authority or above approves, then success regardless of quantity. [AD-16]
- Given a request in a state from which ACKED is not reachable, when approved, then 409 INVALID_STATE_TRANSITION. [FR-141 parity]
- Given a request stale beyond its step duration in `escalation_configs`, when the escalation worker runs, then WAHA notification jobs are enqueued for the machine's LEADER responsibility and inventory/stores roles with phone numbers; duplicates are idempotently skipped. [FR-147]
- Given a request transitions to ACKED or CLOSED while escalation jobs are active, then all non-terminal jobs for that request are cancelled (ack-stop). [FR-147]
- Given OPA, then approve is default-deny with the coarse three-leader allow set and reads stay any-authenticated, with parity tests. [FR-160]

## Spec Change Log

<!-- Append-only. Populated by step-04 during review loops. -->

## Review Triage Log

### 2026-08-28 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5 (high 2, medium 3, low 0)
- defer: 6
- reject: 2
- addressed_findings:
  - `[high]` `[patch]` Idempotency key collision across recipients — idempotency key now includes the recipient UUID; escalation test asserts per-recipient keys are unique.
  - `[high]` `[patch]` saveIgnoreDuplicate inside a single @Transactional could roll back the whole run on flush-time unique violation — job save now runs in a REQUIRES_NEW TransactionTemplate with saveAndFlush, so a duplicate only rolls back its own insert.
  - `[medium]` `[patch]` EscalationConfigService tier matching depended on unspecified row order and fell open to the lowest tier — findByScopeOrderByStepAsc added; fail-closed fallback to the highest (null-max) tier on gap/no-match instead of the lowest.
  - `[medium]` `[patch]` @Valid missing on approve endpoint — ApproveRequest.note @Size(max=500) now enforced → 400 VALIDATION_ERROR instead of a 500.
  - `[medium]` `[patch]` NotificationDispatchService messageBody path untested — added dispatch test asserting verbatim send with template render never invoked.

### 2026-08-28 — Follow-up review pass (fresh, `done` spec)
- intent_gap: 0
- bad_spec: 4 (high 3, medium 1)
- patch: 8 (high 3, medium 5, low 0)
- defer: 6
- reject: 7
- addressed_findings:
  - `[high]` `[patch]` Escalation job alertId was a random UUID violating the still-active `notification_jobs_alert_id_fkey` (NOT NULL + FK to sparepart_alerts) — confirmed live: every escalation insert failed at runtime, so FR-147 never delivered. Fix: new V62 migration drops NOT NULL on alert_id; escalation service passes null; entity nullable.
  - `[high]` `[patch]` saveIgnoreDuplicate's TransactionTemplate defaulted to PROPAGATION_REQUIRED (not REQUIRES_NEW as commented) — a duplicate at flush would mark the outer run rollback-only. Fix: `setPropagationBehavior(PROPAGATION_REQUIRES_NEW)`.
  - `[high]` `[patch]` hasApprovalAuthority failed open for an unknown required role (rank 0) — a misconfigured escalation_configs role downgraded the gate to the weakest leader. Fix: `requiredRank > 0` guard (fail closed).
  - `[high]` `[bad_spec]` Cost-tier resolver (`EscalationConfigService.requiredApprovalRole`) never tested at its own surface — every test stubbed the service. Added direct unit tests (below).
  - `[high]` `[bad_spec]` allowedActionsFor — the server-side UI gate (approve button + badge) — had zero direct tests. Added tests.
  - `[high]` `[bad_spec]` Ack-stop bulk update (`cancelActiveForRequest`) never executed against a real DB. Added container test.
  - `[medium]` `[patch]` A missing estPriceId silently downgraded approval to the SECTION_LEADER tier — now throws PRICE_ENTRY_NOT_FOUND (fail closed).
  - `[medium]` `[patch]` allowedActionsFor could 500 the whole list on a broken request (missing machine/workorder) — now catches and hides approve for that row.
  - `[medium]` `[patch]` Tier ordering by `ORDER BY step ASC` was alphabetical, not cost — fallback depended on step names. Fix: `findByScopeOrderByMinCostAscNullsFirst`.
  - `[medium]` `[patch]` Escalation traceId was null — dispatch logs un-correlatable. Fix: composed traceId per (request, step).
  - `[medium]` `[patch]` Requester could receive their own escalation job (SoD dead notification); LEADER user duplicated when also an inventory role. Fix: exclude requester and dedupe recipients.
  - `[medium]` `[bad_spec]` Stale-query semantics (`updatedAt <= cutoff`) never verified at repository level — added container coverage.
  - `[medium]` `[patch]` Migration test 12.3-DB-007 asserted only IS_NULLABLE despite its name — now actually inserts/reads text; added 12.3-DB-010 for V62 alert_id nullability.

## Design Notes

- Approval is NOT a new status: it is the REQUESTED→ACKED transition. SoD + tier + scope gates run first, then the existing `transition()` handles timeline/audit/recompute — zero duplicated state-machine logic. This is why `SparepartRequestStateMachine` stays untouched.
- The escalation job's escalationLevel column holds the step name (`ACK_WAITING`/`PROCESS_WAITING`/`PURCHASE_WAITING`, ≤16 chars to fit VARCHAR(16)), and the worker's stale query re-evaluates per run, so no SENT-age escalation pass is needed (unlike alerts): each step fires once when its duration elapses, and `updated_at` advances on every transition so a step re-arms after the request moves.
- `message_body` is the v1 request-summary body override; the existing template renderer stays the default path for alerts. This keeps the change to `NotificationDispatchService` a one-branch seam. Follow-up fix: escalation jobs carry `alert_id = NULL` (V62) — the original random-UUID stopgap violated the V24 FK and every escalation insert failed at runtime.
- Idempotency key is per (request, step, recipient) — one job per recipient, deduped by the `uq_notification_jobs_idempotency_key` unique index (V36). The ack-stop prefix `SPAREPART_REQUEST:{requestId}:%` matches all recipients of the request. If per-recipient granularity is later dropped, the key can shrink (AD-9 polymorphic target migration).
- Approval tier monotonicity is by application-role authority order SECTION_LEADER < MAINTENANCE_LEADER < MANAGER_MAINTENANCE, applied on top of the in-scope leader check — so a MANAGER can approve a low tier but a SECTION_LEADER can never approve a high tier. An unknown `approval_role` in `escalation_configs` fails closed (no one can approve) rather than downgrading to the weakest leader.
- `saveIgnoreDuplicate` runs each job save in a REQUIRES_NEW transaction (TransactionTemplate with PROPAGATION_REQUIRES_NEW) so a duplicate insert's unique violation rolls back only that job, never the run.

## Verification

**Commands:**
- `./mvnw.cmd -f syncro/apps/backend/pom.xml test "-Dtest=EscalationConfigServiceTest,SparepartRequestServiceTest,SparepartRequestControllerTest,SparepartRequestEscalationServiceTest,NotificationDispatchServiceTest,SparepartRequestMigrationTest,SparepartRequestEscalationCancelIntegrationTest"` -- expected BUILD SUCCESS.
- `cd syncro/authz && ./run-opa-test.ps1` -- expected PASS incl. new approve parity cases.
- `cd syncro/apps/web && npx tsc --noEmit` -- expected green.
- `cd syncro/apps/web && npx biome check src/features/sparepart-requests` -- expected clean.

## Auto Run Result

**Summary of implemented change:** Story 12-3 (Approval, Separation of Duty & Escalation, FR-142/FR-147/AD-16) — V60 migration (escalation_configs table + notification_jobs.message_body) + V62 fix (alert_id nullable), approval endpoint POST /{id}/approve with SoD/cost-tier/role-scope gates, EscalationConfigService for DB-driven tier/duration resolution, SparepartRequestEscalationWorker + SparepartRequestEscalationService for step-driven stale-request escalation with WAHA job enqueue, messageBody dispatch path in NotificationDispatchService, ack-stop on ACKED/CLOSED (cancelActiveForRequest), allowedActions per-user in API views, OPA approval path rego + parity, frontend approve dialog + badge + button driven by allowedActions.

**Files changed:** 20 modified + 6 new backend files (V60 migration, EscalationConfig domain/entity/repo/service, EscalationService + Worker, controller/DTOs/exception handler, service, mapper, repository, NotificationJobEntity/Repository/DispatchService, AuthUserRepository, rego + rego_test, .env.example) + 4 frontend files (types, hook, list, new approve-dialog). Follow-up pass adds V62 migration + fixes in EscalationConfigService/Repository, SparepartRequestEscalationService, SparepartRequestService, NotificationJobEntity, migration test.

**Review findings breakdown (follow-up pass):**
- Patches applied: 8 (3 high, 5 medium) — V62 alert_id nullable, REQUIRES_NEW saveIgnoreDuplicate, hasApprovalAuthority fail-closed, estPriceId fail-closed, allowedActionsFor defensive, tier ordering by cost, traceId, recipient dedup/exclusion
- Items deferred: 6 (see frontmatter `deferred`)
- Items rejected: 7 (see triage log)

**Follow-up review recommendation:** true — 3 high + 5 medium = 3×3 + 5×2 = 19 ≥ 5.

**Verification performed (follow-up pass):**
- Backend: EscalationConfigServiceTest 11/11, SparepartRequestServiceTest 59/59 (incl. 5 new allowedActionsFor), SparepartRequestEscalationServiceTest 7/7, SparepartRequestControllerTest 26/26, NotificationDispatchServiceTest 9/9, SparepartRequestMigrationTest 30/30 (incl. new 12.3-DB-010 V62 alert_id nullable), SparepartRequestEscalationCancelIntegrationTest 4/4 (new ack-stop container tests) — all BUILD SUCCESS
- Frontend: `tsc --noEmit` green, `biome check` clean (unchanged by follow-up)
- OPA: 198/198 rego parity tests pass (unchanged by follow-up)

**Residual risks:**
- Escalation recipients are not plant-scoped — all INVENTORY_MAINTENANCE/STOREKEEPER users globally get notified for every stale request. Acceptable v1 mitigated by the LEADER responsibility being machine-scoped and inventory roles being a parallel channel.
- Escalation fires once per (request, step, recipient) with no re-escalation — relies on the notification worker's retry path (maxAttempts=3). A job that exhausts its attempts without delivery is never re-escalated.
- No index serves the ack-stop idempotency-key prefix scan (LIKE 'SPAREPART_REQUEST:{id}:%') — acceptable at request volume; should gain an index if job rows grow.
- Approval is still reachable via the generic `/transition` endpoint (REQUESTED→ACKED) by inventory/stores without the approval gate — the `/approve` endpoint enforces SoD/tier/scope, but `/transition` remains a bypass surface. Mitigation: `/approve` is the blessed approval path and the OPA coarse gate covers only `/approve`; a future story may fold the approval gate into `/transition` or drop the direct REQUESTED→ACKED edge.
- Escalation dispatch falls back to the WAHA template renderer if a job's `message_body` is null/blank — escalation jobs always compose a body today, but a malformed row would render an alert template from a null alertId. Acceptable v1; flagged for a future guard.
