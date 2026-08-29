---
title: 'WAHA Notifications & 4-Hour Acknowledgment'
type: 'feature'
created: '2026-08-29'
status: 'done'
baseline_revision: '82cedcc'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/implementation-artifacts/epic-14-context.md'
warnings: []
deferred: []
---

<intent-contract>

## Intent

**Problem:** Workorder lifecycle events (new breakdown, ON_PROCUREMENT, part READY, DONE/CLOSED) do not notify section leaders/inventory via WAHA (FR-180), and there is no 4-hour acknowledgment flow with an auto-login link (FR-181).

**Approach:** Publish a `WorkOrderLifecycleEvent` from `WorkOrderService.transition()` / `recomputeProcurementState()`; an AFTER_COMMIT listener in the notification module enqueues per-recipient notification jobs (idempotency key `WORKORDER:{woId}:{event}:{recipientUserId}`), resolved via machine responsibilities (LEADER+) and inventory roles with WhatsApp. A scheduled ack worker computes net IN_PROGRESS time from status history (excluding ON_PROCUREMENT), sends a WAHA message with an auto-login link (short-lived JWT bound to the recipient's WhatsApp number, single-use), and a landing task list shows acknowledged vs pending acks and rated vs unrated closed workorders.

## Boundaries & Constraints

**Always:**
- Notification enqueue follows the existing outbox pattern (notification_jobs, idempotency-key prefix `WORKORDER:{woId}:{event}:{recipientUserId}`, ROUTING_FAILED when no phone, DataIntegrityViolationException → idempotent skip). No AD-9 polymorphic columns (existing convention explicitly avoids them).
- Recipients for lifecycle events: machine responsibility LEADER/SPV/MANAGER + all INVENTORY_MAINTENANCE/STOREKEEPER users with WhatsApp (reuse `findAllByApplicationRoleInWithWhatsapp`); SYNCED-source workorders are excluded from notification (internal-only events).
- 4-hour ack: threshold configurable via `escalation_configs` WORKORDER scope `ACK_WAITING` step (480 min default; WORKORDER scope already allowed by the V60 CHECK); net IN_PROGRESS time computed from `work_order_status_history` segments where `toStatus='IN_PROGRESS'` until next transition, excluding ON_PROCUREMENT spans.
- Auto-login token: extend `JwtTokenService` with `createToken(user, ttl, extraClaims)`; payload carries `typ=AUTO_LOGIN`, `wa=<whatsappNumber>`, `wo=<workOrderId>`; single-use (revoked after first login), TTL default 15 min, expired/mismatch → fallback to normal login. JWT filter passes AUTO_LOGIN tokens for the ack-task-list endpoint.
- Ack records to workorder timeline (status history row source=DERIVED? No — ack is a notification-side record; store in a new `workorder_acks` table) + audit, stops further escalation (cancel pending jobs for that workorder).
- Templates: new `workorder_lifecycle` template key (variables: workOrderId, machineCode, status, eventLabel, transitionedAt) and `workorder_ack` key (variables: workOrderId, machineCode, ackDeadline, ackLink); extend `WahaTemplateRenderer` with a workorder variant; extend `WahaTemplate.KNOWN_VARIABLES`.
- Rate limiter: `WahaRateLimiter` keyed on alertId — add a discriminator for workorder jobs (idempotency key or workorder id).
- The landing task list: acknowledged vs pending acks, rated vs unrated closed workorders (from workorder_ratings + workorder_acks).
- No new dependencies. No schema change beyond V67 (workorder_acks + any needed columns).

**Never:**
- Do NOT modify the workorder state machine or lifecycle transitions.
- Do NOT modify Epic 5 alert notification paths.
- No new dependencies.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | internal WO transitions to a lifecycle event | jobs enqueued for each recipient with phone | no error |
| NO_PHONE | recipient without whatsapp number | ROUTING_FAILED job with errorDetail | no crash |
| IDEMPOTENT | same event enqueued twice | second save skipped (unique idempotency key) | DataIntegrityViolationException caught |
| SYNCED_WO | SYNCED workorder transitions | no notification jobs created | no error |
| 4H_REACHED | WO in IN_PROGRESS net > threshold | ack message with link sent to PRODUCTION_LEADER | no error |
| ACK_EXPIRED | token used after TTL or reused | fallback to normal login, no residual access | no error |
| ACK_DONE | leader acknowledges | ack recorded, pending ack jobs cancelled, task list updates | no error |
| ACK_PHONE_MISMATCH | login from different phone | normal login fallback | no error |
| NO_IN_PROGRESS | WO never in IN_PROGRESS | no ack scheduled | no error |

</intent-contract>

## Code Map

Backend (`com.syncro`):
- `maintenance/application/WorkOrderService.java` — `transition()` (line ~234) + `recomputeProcurementState()` (line ~295) publish `WorkOrderLifecycleEvent` (publisher injected)
- NEW `maintenance/application/WorkOrderLifecycleEvent.java` — record(workOrderId, eventType, status, traceId)
- NEW `notification/application/WorkOrderNotificationRoutingService.java` — `@TransactionalEventListener(AFTER_COMMIT)` on WorkOrderLifecycleEvent; resolves recipients (machine LEADER+ via MachineResponsibilityRepository + inventory roles via AuthUserRepository.findAllByApplicationRoleInWithWhatsapp); enqueues jobs with idempotency key prefix `WORKORDER:...`; extends WahaTemplateRenderer for workorder templates; rate limiter discriminator for workorder
- NEW `notification/application/WorkOrderAckWorker.java` — `@Scheduled` polls status history for net IN_PROGRESS > threshold (WORKORDER ACK_WAITING from escalation_configs); sends ack message with auto-login link to PRODUCTION_LEADER; marks sent
- NEW `maintenance/application/WorkOrderAckService.java` — acknowledge endpoint (records to workorder_acks, cancels pending jobs, audit); task-list query (acknowledged vs pending, rated vs unrated)
- NEW `maintenance/infrastructure/db/WorkOrderAckEntity.java` + repository — V67 table
- NEW `auth/application/JwtTokenService` extension — `createToken(user, ttl, extraClaims)`; `parseAutoLogin(token)` validating typ=AUTO_LOGIN + wa claim; filter pass-through for ack endpoint
- MODIFY `WahaTemplateRenderer.java` — workorder render variant + new template keys
- MODIFY `WahaTemplate.java` — KNOWN_VARIABLES extended
- MODIFY `WahaRateLimiter.java` — discriminator for workorder jobs
- MODIFY `EscalationConfigService.java` — WORKORDER scope support
- NEW `V67__workorder_acks.sql` — workorder_acks table (work_order_id, acknowledged_by, acknowledged_at, trace_id, UNIQUE(work_order_id))
- NEW endpoints: `POST /api/v1/workorders/{id}/acknowledge`, `GET /api/v1/workorders/ack-task-list`
- Tests: unit + integration for routing, ack worker, ack service, token, template renderer, rate limiter

Frontend (`syncro/apps/web/src`):
- NEW `features/workorders/hooks/use-ack-task-list.ts`, `use-acknowledge.ts` — hand-written TanStack Query hooks
- NEW `features/workorders/components/ack-task-list.tsx` — acknowledged vs pending acks, rated vs unrated closed workorders
- NEW route page for ack task list (`/dashboard/workorders/ack-task-list`) or accessible from the workorder dashboard
- Extend `features/waha-templates` editor with new template keys/variables if needed

## Tasks & Acceptance

**Execution:**
- `backend WorkOrderLifecycleEvent` + `WorkOrderService` publishing — event hook
- `backend WorkOrderNotificationRoutingService` — AFTER_COMMIT listener, recipients, enqueue with idempotency
- `backend WorkOrderAckWorker` + `WorkOrderAckService` + `WorkOrderAckEntity` + V67 — 4h ack flow
- `backend JwtTokenService` extension + filter — auto-login token
- `backend WahaTemplateRenderer` + `WahaTemplate` + `WahaRateLimiter` + `EscalationConfigService` — template/rate-limit/scope support
- `backend endpoints` — acknowledge + ack-task-list
- `backend tests` — routing, ack worker (incl. 4h clock), ack service (dup, unauthorized), token (expiry, phone mismatch, single-use), renderer, rate limiter
- `web hooks` + `ack-task-list.tsx` + route — FR-181 surface
- `web template editor` extension — new keys

**Acceptance Criteria:**
- Given an internal workorder transitions to a lifecycle event (new breakdown, ON_PROCUREMENT, part READY, DONE/CLOSED), when the transition commits, then WAHA notification jobs are enqueued for machine LEADER+ and inventory roles with WhatsApp numbers, idempotent per (workorder, event, recipient).
- Given a workorder has been in IN_PROGRESS (net of ON_PROCUREMENT) beyond the configured threshold, when the ack worker runs, then a WAHA message with a short-lived auto-login link is sent to the PRODUCTION_LEADER.
- Given the leader opens the auto-login link, when the token is valid (bound to their WhatsApp number, not expired, first use), then they land on the task list showing acknowledged vs pending acks and rated vs unrated closed workorders; acking records the action and stops further escalation.
- Given the token is expired, reused, or the phone mismatches, when the link is opened, then the user falls back to normal login with no residual access.
- Given a SYNCED workorder transitions, when the event fires, then no notification jobs are created.

## Verification

**Commands:**
- `cd syncro/apps/backend && mvn -q test -Dtest="WorkOrderNotificationRoutingServiceTest,WorkOrderAckWorkerTest,WorkOrderAckServiceTest,JwtTokenServiceTest,WahaTemplateRendererTest"` -- expected: green
- `cd syncro/apps/web && npm run lint` -- expected: no new violations
- `cd syncro/apps/web && npm run build` -- expected: production build succeeds
