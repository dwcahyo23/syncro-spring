---
title: 'Validate Acknowledgement Stops Escalation'
type: 'validation'
baseline_commit: f4eeafa
created: '2026-08-22'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: true
context: []
warnings:
  - 'Acknowledge audit does NOT carry a traceId (AuditLogWriter.recordSystem persists no traceId column and no traceId in new_value); correlation is via sparepart_alerts.trace_id. AC 7.6-4 traceId requirement is satisfied by the CREATE audit row (new_value.traceId).'
  - 'EscalationTimeline "Stopped by acknowledgement at" renders job sentAt (not updatedAt) for CANCELLED steps because alert-notification-history.tsx maps timestamp = job.sentAt ?? updatedAt ?? createdAt for non-ESCALATED statuses. UI shows the sentAt of the cancelled job, not the acknowledge time.'
  - 'Acknowledge UI has NO confirm dialog — it is a one-click action firing POST /acknowledge with a sonner success toast ("Alert acknowledged."). AC 7.6-5 said "confirm dialog"; the implementation does not have one.'
  - 'Mobile page-spec §3.6 sticky bottom bar is NOT implemented; the Acknowledge button lives in the alert header and IS reachable on mobile (390x844).'
  - 'Alert A (08338a5a) was resolved via the UI by technician.gm1 at 06:52:46Z (user action during the run, audit-recorded) BEFORE the post-window check; the AC 7.6-2b post-window evidence (single TECHNICIAN CANCELLED row) was still captured cleanly at 06:55:42Z.'
---

<intent-contract>

## Intent

**Problem:** Story 7-5 proved the threshold-to-notification chain: counting=900 → OPEN alert → TECHNICIAN notification job queued with traceId correlation. Story 7-6 must now prove the escalation-stop chain: acknowledging the OPEN alert transitions it to ACKNOWLEDGED, cancels pending/sent notification jobs, and prevents STAFF/LEADER/SPV/MANAGER escalation — the validation that makes the WAHA escalation behavior trustworthy. This is the core proof for SM-003 (acknowledgement transaction), SM-004 (acknowledgement stops escalation), and the "mobile acknowledge" path for field technicians.

**Approach:** Validation story — **zero source-code changes under `syncro/`**. All mutations use existing product endpoints. Execute the acknowledge flow end-to-end on the live local stack using the 7-3 pilot tooling + existing product endpoints, then record a correlated evidence chain: (1) preflight — infra stack up, seed idempotently applied, backend running with real `syncro/.env` + console captured, health 200, web app running; (2) state assessment — SQL confirms current non-RESOLVED alert state; (3) establish clean slate — resolve any existing non-RESOLVED alert via product endpoints (resolve-override for OPEN, or acknowledge→resolve for ACKNOWLEDGED) to clear the V19 dedup guard; (4) publish threshold counting=900 via `publish-jbf19-threshold.ps1` → fresh OPEN alert + TECHNICIAN job; (5) acknowledge the alert BEFORE the 15-minute escalation window elapses, using `POST /api/v1/alerts/{alertId}/acknowledge` as the responsible-user path (technician.gm1@syncro.dev, VIEWER+GM1 assignment); (6) verify alert → ACKNOWLEDGED, TECHNICIAN job → CANCELLED, acknowledge audit row with `escalationCancelledCount`; (7) wait past the escalation window (>15m from TECHNICIAN sentAt) and confirm zero STAFF/LEADER jobs were ever created; (8) escalation timeline proof — `GET /api/v1/alerts/{alertId}/notifications` shows CANCELLED job + "Stopped by acknowledgement" UI; (9) audit evidence — three audit rows (CREATE, acknowledge UPDATE, resolve hygiene UPDATE) with actor, timestamp, action, result; (10) mobile acknowledge path — playwright mobile viewport, login as technician.gm1, navigate to alert detail, acknowledge success.

## Boundaries & Constraints

**Always:**
- Execute the flow in this order, each step gated on the previous: (a) `docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml` stack up (postgres, redis, influxdb, emqx minimum); (b) `powershell -NoProfile -File syncro/scripts/seed-pilot.ps1` — idempotent, canonical-row summary incl. `Electric · PLC · Wecon · LX5` tripwire, exit 0; (c) backend started with `syncro/.env` environment (temp launcher using the 7-3 dev-record pattern: load `syncro/.env` with the start-backend.ps1 regex idiom, then `mvnw -f syncro/apps/backend/pom.xml spring-boot:run`, stdout/stderr captured to a file), wait for `GET http://localhost:8080/api/v1/health` → 200; (d) web app started (`npm --prefix syncro/apps/web run dev` on port 3000 or `start-web.ps1` production build on port 3001) and login verified; (e) state assessment SQL; (f) resolve hygiene (if needed); (g) publish threshold; (h) acknowledge; (i) verify; (j) wait past escalation window; (k) post-window verify; (l) UI proof; (m) mobile acknowledge proof.
- Backend MUST be running and subscribed BEFORE the threshold publish: `cleanSession(true)` (3-1 config), so a message published while backend is down is never delivered. Confirm subscription readiness by health 200 plus the `mqtt_telemetry_accepted` log line appearing after publish.
- The backend MUST run with real `syncro/.env` values, not `.env.example` placeholders. Use the 7-3 dev-record pattern verbatim: load `syncro/.env` into the process environment, then `mvnw -f syncro/apps/backend/pom.xml spring-boot:run`, capturing console output to a file (e.g. `*> backend-pilot.log`), because the evidence markers are INFO-level console output.
- **Exact evidence markers (grep-able substrings):**
  - Telemetry accepted: `mqtt_telemetry_accepted traceId=... topic=factory/GM1/BF-08410/telemetry` at INFO. Source: `MqttTelemetryIngestHandler.java:62`.
  - Alert created: `Alert created for installation` at INFO. Source: `SparepartAlertService.java:136`. Format: `[traceId={}] Alert created for installation {} threshold {}% consumed {}%`.
  - Acknowledge endpoint: `POST /api/v1/alerts/{alertId}/acknowledge` → 204 (no body). No backend log marker for acknowledge (the command service does not log at INFO; only the audit writer inserts a row).
- **Evidence queries (record verbatim with exact output shapes; pgAdmin is local/dev evidence tool, NOT a product feature):**
  (1) Pre-hygiene: `SELECT id, status, status_reason, trace_id, created_at, updated_at FROM sparepart_alerts WHERE status <> 'RESOLVED' ORDER BY created_at DESC;` — record the existing non-RESOLVED alert(s) before hygiene.
  (2) Pre-hygiene jobs: `SELECT escalation_level, status, idempotency_key, CASE WHEN status = 'SENT' THEN 'SENT' ELSE recipient_phone END AS recipient_phone, sent_at, updated_at FROM notification_jobs WHERE alert_id = '<existingAlertId>' ORDER BY CASE escalation_level WHEN 'TECHNICIAN' THEN 1 WHEN 'STAFF' THEN 2 WHEN 'LEADER' THEN 3 WHEN 'SPV' THEN 4 ELSE 5 END;`
  (3) Post-hygiene: `SELECT id, status, status_reason, trace_id FROM sparepart_alerts WHERE status <> 'RESOLVED';` — expect zero rows.
  (4) Post-threshold-publish: `SELECT id, status, consumed_percentage_snapshot, trace_id, threshold_percentage FROM sparepart_alerts ORDER BY created_at DESC LIMIT 1;` — expect one OPEN alert with 90.00%.
  (5) Post-acknowledge: `SELECT id, status, status_reason, trace_id, updated_at FROM sparepart_alerts WHERE id = '<alertId>';` — expect `ACKNOWLEDGED`, status_reason set, updated_at matches acknowledge time.
  (6) Post-acknowledge jobs: `SELECT escalation_level, status, idempotency_key, updated_at, sent_at, next_attempt_at, error_detail FROM notification_jobs WHERE alert_id = '<alertId>' ORDER BY CASE escalation_level WHEN 'TECHNICIAN' THEN 1 WHEN 'STAFF' THEN 2 WHEN 'LEADER' THEN 3 WHEN 'SPV' THEN 4 ELSE 5 END;` — expect exactly one row: TECHNICIAN CANCELLED, next_attempt_at NULL.
  (7) Post-window (after >15m from sentAt): same query (6) — expect NO additional rows (no STAFF/LEADER created). If any non-TECHNICIAN row exists, that is a genuine 5-5 regression.
  (8) Audit: `SELECT actor_name, action, entity_type, entity_id, previous_value, new_value, created_at FROM audit_log WHERE entity_id = '<alertId>' AND entity_type = 'ALERT' ORDER BY created_at;` — expect rows: CREATE (SYSTEM), UPDATE (acknowledge, new_value JSON contains `actorId`, `transition:"OPEN→ACKNOWLEDGED"`, `status:"ACKNOWLEDGED"`, `reason`, `escalationCancelledCount:<int>`), and if hygiene was applied, an UPDATE row for resolve/resolve-override.
- `verify-pilot.ps1 -ExpectCounting 900` — the ALERT section expects exactly one non-RESOLVED alert with `consumed_percentage_snapshot=90.00`; the ACKNOWLEDGEMENT RESULT section reports per-level job timeline and flags if STAFF/LEADER jobs are SENT while alert is ACKNOWLEDGED/RESOLVED (a 7-6 regression marker). Run this AFTER acknowledge but BEFORE the post-window proof to confirm the alert state.
- Save the `traceId` from the `mqtt_telemetry_accepted` log line. This traceId is the correlation key for the entire evidence chain: it appears in the `sparepart_alerts.trace_id` column and the alert-CREATE audit `new_value.traceId`. The acknowledge audit does NOT carry a traceId (Architecture gap — `AuditLogWriter.recordSystem` does not persist one; `sparepart_alerts.trace_id` is the closest correlation point).
- The acknowledge audit row uses `AuditLogWriter.recordSystem(...)` with `actorId` embedded in `new_value` JSON (`"actorId": "<user UUID>"`). The `audit_log.actor_name` column shows `"SYSTEM"` — the real actor is only present in `new_value.actorId`. This is a known architecture pattern (documented in Dev Agent Record as a deviation from the AC's "audit records actor" — the actor IS recorded but in the JSON payload, not the actor_name column).
- CancelActiveForAlert cancels `PENDING`, `SENT`, and `RATE_LIMITED` jobs (confirmed: `SparepartAlertCommandService.acknowledge()` line 64-66). The `RATE_LIMITED` enum is already handled.
- **Escalation timing:** default `syncro.notification.escalation.interval-ms` = 900000 (15 min). The TECHNICIAN job's `sentAt` marks the start of the escalation window. MUST acknowledge before `sentAt + 15 min` to demonstrate "acknowledges before next escalation interval". The acknowledge itself is fast (API call); the proof requires waiting >15 min after for the post-window STAFF/LEADER-gap verification.
- **Mobile acknowledge proof:** Use playwright browser with mobile viewport emulation (e.g., 390×844, iPhone 13). Log in as `technician.gm1@syncro.dev` / `syncro-pilot-dev` (VIEWER + GM1 plant assignment — the "authorized responsible user" path). Navigate to `/dashboard/alerts/{alertId}`. Verify the acknowledge action is reachable (button in header; if sticky bottom bar per page-spec §3.6 is absent, document as page-spec deviation). Tap Acknowledge → confirm dialog → success toast → status badge flips to ACKNOWLEDGED. Record the mobile flow with screenshots (temp, not committed) and console logs.
- Record EVERYTHING in the Dev Agent Record as an AC → evidence mapping, with the publish timestamp, the traceId, the acknowledge timestamp, the verify-pilot runs, the SQL output shapes, the post-window check, and the mobile flow.

**Block If:**
- A non-RESOLVED alert exists BEFORE the threshold publish that cannot be resolved via product endpoints. If the resolve-override endpoint returns 500 or the acknowledge→resolve chain fails, block and report — do not proceed with a polluted alert state.
- The threshold publish lands in `telemetry_quarantine` — root-cause before proceeding.
- `POST /api/v1/alerts/{alertId}/acknowledge` returns 409 `INVALID_STATE_TRANSITION` — means the alert is not OPEN (already acknowledged or resolved by another process). Investigate the alert status before proceeding.
- After waiting past the escalation window, a STAFF or LEADER notification job row appears (genuine 5-5 regression). Do NOT mask — block and report the regression.
- Backend cannot reach 200 health with real `.env` values, EMQX login fails, UI cannot log in, or the mobile viewport cannot authenticate — stop and report the blocker.
- The verify-pilot.ps1 ACKNOWLEDGEMENT RESULT section prints `observation: STAFF job SENT while alert is ACKNOWLEDGED` — this is a genuine regression: acknowledge did not stop escalation. Block and investigate.

**Never:**
- Never modify ANY source file under `syncro/` — no production code, no seed, no migrations, no fixtures, no pilot scripts, no infra config. This story's only file changes are this story file and `sprint-status.yaml` (plus transient local log files, which are NOT committed).
- Never publish the threshold fixture with the backend stopped — the backend MUST be running to create the alert.
- Never claim an AC without its recorded evidence; never mark the story done with verify-pilot FAIL lines or a missing mobile observation.
- Never use the swapped topic `factory/BF-08410/GM1/telemetry` (backend parses as plant=BF-08410/machine=GM1 → `unknown_plant` quarantine); the canonical topic is `factory/GM1/BF-08410/telemetry`.
- Never reset, wipe, or `down -v` the database/Redis/volumes to "clean up" state; the seed is idempotent and the existing counter state is a valid baseline. The resolve-hygiene step uses product endpoints, not bulk delete.
- Never modify `application-local.yml` or any source config to set DEBUG logging or other settings.
- Never create `docs/pilot-validation.md` or `docs/screenshots/pilot/` (story 7-7 owns both).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Fresh full run (primary path) | Stack up, seed applied, backend + web running, existing non-RESOLVED alert resolved via endpoint | Publish threshold 900; alert OPEN; acknowledge via POST /{alertId}/acknowledge → 204; alert → ACKNOWLEDGED; TECHNICIAN job → CANCELLED; audit row with escalationCancelledCount ≥ 1; after >15m, zero STAFF/LEADER jobs | — |
| No existing non-RESOLVED alert (clean 7-3 state) | No leftover alert from 7-5 | Skip resolve-hygiene step; fresh threshold publish creates OPEN alert directly | Record "no pre-existing alert — clean baseline" |
| Existing alert at OPEN, TECHNICIAN SENT (ideal pre-acknowledge state) | 7-5 alert with TECHNICIAN SENT, escalation not yet past 15m window | Resolve (resolve-override) → cleared; fresh threshold publish → new OPEN alert; acknowledge → TECHNICIAN CANCELLED | — |
| Existing alert escalated past TECHNICIAN (STAFF/LEADER SENT from 7-5 drift) | 7-5 alert with STAFF SENT, LEADER PENDING, etc. | Resolve-override (SUPER_ADMIN) → cleared; record the pre-hygiene job state as context; fresh threshold publish resets to TECHNICIAN baseline | Document the pre-hygiene drift state; proceed with fresh alert |
| Acknowledge as VIEWER (technician.gm1) | technician.gm1@syncro.dev, GM1 plant assignment | 204 success; acknowledge works (loadAndCheckAccess uses scopedPlantIds, which finds GM1 assignment) | — |
| Acknowledge as SUPER_ADMIN (admin@syncro.dev) | admin@syncro.dev, no plant restriction | 204 success; acknowledge works (bypasses plant scope) | Either path valid; prefer VIEWER for "responsible user" AC |
| TECHNICIAN job PENDING at acknowledge time | Worker hasn't dispatched yet | job → CANCELLED (never send to WAHA); escalationCancelledCount includes it | Record PENDING as evidence of early acknowledge |
| TECHNICIAN job SENT at acknowledge time | Worker dispatched before acknowledge | job → CANCELLED (escalation stopped); escalationCancelledCount includes it | Record SENT + CANCELLED as evidence |
| Acknowledge reason null | No body sent: `POST /{alertId}/acknowledge` with empty body | 204; `statusReason = null` in DB; audit new_value `reason = ""` | Allowed per 4-4 spec |
| Acknowledge with reason | Body: `{"reason": "Acknowledged by technician"}` | 204; `statusReason` set; audit new_value includes reason | Record the reason text |
| Wait past escalation window — no STAFF/LEADER | Wait >15m after TECHNICIAN sentAt; EscalationWorker polls every 60s | Zero STAFF/LEADER/SPV/MANAGER job rows; alert is ACKNOWLEDGED (EscalationService guard `alert.getStatus() != OPEN`); only TECHNICIAN CANCELLED exists | If STAFF appears → BLOCK (5-5 regression) |
| Mobile acknowledge via playwright | Mobile viewport 390×844, logged in as technician.gm1 | Acknowledge button visible in header; tap → confirm → success toast → status ACKNOWLEDGED | If sticky bottom bar absent, document as page-spec deviation |
| Double acknowledge | Acknowledge same alert again | 409 `INVALID_STATE_TRANSITION` (entity guard: `status != OPEN`) | Expected behavior; do not attempt in primary flow |
| UI check after acknowledge | Alert detail page after acknowledge | Status badge ACKNOWLEDGED; EscalationTimeline shows TECHNICIAN CANCELLED "Stopped by acknowledgement"; Action Panel shows Resolve button only; no Acknowledge button | — |
| Backend down at threshold publish | Backend not yet healthy | Broker accepts (`no_matching_subscribers`), message never delivered (cleanSession) | Blocker: start backend first, re-publish after health 200 |
| Backend started with `.env.example` | Placeholder MQTT credentials | MQTT auth/connect fails; no subscription | Use `syncro/.env` temp-launcher pattern |
| Console capture missed | Backend foreground without redirect | Log line unavailable post-hoc | Re-publish (>30s) with capture running |
| UI check after PT5M window | Redis latest hash expired | Dashboard badge STALE; alert detail page is persisted (no TTL) — the primary acknowledge UI proof | Alert detail is the primary UI proof |

## Code Map

**No NEW or EDIT files in `syncro/`.** All artifacts are read-only execution targets:

- `syncro/scripts/seed-pilot.ps1` -- EXECUTE -- idempotent seed apply + canonical-row summary.
- `syncro/scripts/publish-jbf19-threshold.ps1` -- EXECUTE -- publishes threshold fixture (counting=900) with refreshed timestamp.
- `syncro/scripts/verify-pilot.ps1` -- EXECUTE -- six-section evidence verdicts including ACKNOWLEDGEMENT RESULT section that reports alert status + per-level job timeline. ALERT section expects exactly one non-RESOLVED alert with snapshot 90.00. ACKNOWLEDGEMENT RESULT section flags if STAFF/LEADER jobs are SENT while alert is ACKNOWLEDGED (7-6 regression marker).
- `syncro/scripts/start-backend.ps1` -- REFERENCE ONLY for the env-parsing idiom.

- `syncro/apps/backend/src/main/java/com/syncro/alert/application/SparepartAlertCommandService.java` -- READ -- `acknowledge()` method (line 50): `loadAndCheckAccess` → `alert.acknowledge(reason, clock.instant())` → `alertRepository.save(alert)` → `cancelActiveForAlert(alertId, [PENDING,SENT,RATE_LIMITED], CANCELLED, now)` → `auditLogWriter.recordSystem(...)` with newValue `{actorId, transition, status, reason, escalationCancelledCount}`. `resolve()` (line 85): `ACKNOWLEDGED → RESOLVED`. `resolveOverride()` (line 111): `OPEN → RESOLVED` (SUPER_ADMIN only).
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertController.java` -- READ -- `POST /{alertId}/acknowledge` → 204 (line 93), `POST /{alertId}/resolve` (line 111), `POST /{alertId}/resolve-override` (line 129).
- `syncro/apps/backend/src/main/java/com/syncro/alert/infrastructure/SparepartAlertEntity.java` -- READ -- `acknowledge(reason, now)` guard: `status != OPEN → InvalidAlertTransitionException`. `resolve(reason, now)` guard: `status != ACKNOWLEDGED → InvalidAlertTransitionException`. `resolveOverride(reason, now)` guard: `status != OPEN → InvalidAlertTransitionException`.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationRoutingService.java` -- READ -- `@TransactionalEventListener(AFTER_COMMIT)` on `AlertOpenedEvent` → inserts TECHNICIAN `PENDING` job. Idempotency key: `alertId + "::" + levelName`. Unique constraint: `uq_notification_jobs_alert_level (alert_id, escalation_level)`.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationService.java` -- READ -- `escalate()`: checks `alert.getStatus() != OPEN` → skip (guard), then walks `TECHNICIAN→STAFF→LEADER→SPV→MANAGER`. Default interval 15 min.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/EscalationWorker.java` -- READ -- polls `findSentJobsDueForEscalation(cutoff)`, calls `escalationService.escalate(job)`. CATCHES `ObjectOptimisticLockingFailureException` per job.
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- READ -- `cancelActiveForAlert(alertId, [PENDING,SENT,RATE_LIMITED], CANCELLED, now)` bulk JPQL update. `findSentJobsDueForEscalation(cutoff)` queries `status = SENT` — excludes CANCELLED.
- `syncro/apps/backend/src/main/java/com/syncro/notification/api/NotificationHistoryDtos.java` -- READ -- `NotificationJobView` with `status`, `escalationLevel`, `recipientPhoneMasked`, `sentAt`, `updatedAt`, `errorDetail`, `traceId`.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationHistoryQueryService.java` -- READ -- `getHistory(user, alertId)`: plant-scoped, returns jobs ordered by escalation order.
- `syncro/apps/backend/src/main/java/com/syncro/alert/api/SparepartAlertDtos.java` -- READ -- `AcknowledgeRequest` record (`reason: String`, nullable).
- `syncro/apps/backend/src/main/java/com/syncro/audit/application/AuditLogWriter.java` -- READ -- `recordSystem()`: actor = SYSTEM(UUID(0,0), "SYSTEM"), no traceId column. `record()`: actor = `AuthenticatedUser`. Neither method persists a traceId.
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditAction.java` -- READ -- `CREATE`, `UPDATE`, `DELETE`.
- `syncro/apps/backend/src/main/java/com/syncro/audit/domain/AuditEntityType.java` -- READ -- includes `ALERT` (added in V19/4-2).
- `syncro/apps/backend/src/main/resources/db/migration/V16__create_audit_log.sql` -- READ -- schema: `actor_id`, `actor_name`, `action`, `entity_type`, `entity_id`, `entity_label`, `plant_id`, `previous_value` (TEXT JSON), `new_value` (TEXT JSON), `created_at`. No `trace_id` column.
- `syncro/tests/fixtures/mqtt-jbf19-threshold-payload.json` -- READ -- counting=900, messageId `pilot-jbf19-threshold-900`, schemaVersion 1.0.
- `syncro/apps/web/src/features/alerts/alert-detail-page-content.tsx` -- READ -- acknowledge button at line 240, rendered when `alert.status === "OPEN"`. No sticky bottom bar implementation (page-spec §3.6 deviation). Mobile acknowledge path works via the header button.
- `syncro/apps/web/src/components/syncro/escalation-timeline.tsx` -- READ -- `CANCELLED` → `stopped` variant, renders "Stopped by acknowledgement at {updatedAt}".
- `_bmad-output/implementation-artifacts/spec-7-5-validate-threshold-alert-and-waha-notification-job.md` -- READ -- Dev Agent Record: preflight patterns, log-capture method, V31 blocker context, escalation drift documentation.
- `_bmad-output/implementation-artifacts/spec-7-4-validate-telemetry-before-threshold-does-not-create-alert.md` -- READ -- preflight patterns, evidence chain template.
- `_bmad-output/implementation-artifacts/spec-7-3-create-pilot-scripts-for-seed-publish-and-verify.md` -- READ -- temp-launcher pattern, PS 5.1 quoting pitfalls.

## Tasks & Acceptance

**Execution:**

- [x] Preflight: infra stack up; `seed-pilot.ps1` exit 0 (idempotent, tripwire label correct); backend started via temp launcher with `syncro/.env` + console captured to a log file; health 200; web app started; login verified (record user + port). [AC 7.6-1 preamble]
- [x] State assessment: SQL confirms current non-RESOLVED alert(s) and notification_jobs for each. Record the pre-hygiene state. [AC 7.6-1 preamble, AC 7.6-6]
- [x] Establish clean slate: if any non-RESOLVED alert exists, resolve it via product endpoint: if OPEN → `POST /api/v1/alerts/{alertId}/resolve-override` as SUPER_ADMIN admin@syncro.dev (4-6); if ACKNOWLEDGED → `POST /api/v1/alerts/{alertId}/resolve` (4-5). Record the request, response (204), and the audit row. Verify post-hygiene: zero non-RESOLVED alerts. [AC 7.6-1 precondition]
- [x] Publish threshold (counting=900): `publish-jbf19-threshold.ps1`; record messageId `pilot-jbf19-threshold-900` verbatim, refreshed timestamp, broker acceptance. [AC 7.6-1 precondition]
- [x] Confirm threshold alert created: backend console shows `Alert created for installation ... threshold 90% consumed 90.00%` with traceId. SQL confirms one non-RESOLVED alert with consumed_percentage_snapshot=90.00. [AC 7.6-1 precondition]
- [x] Acknowledge as responsible user: authenticate as `technician.gm1@syncro.dev` / `syncro-pilot-dev` (VIEWER + GM1 assignment). `POST /api/v1/alerts/{alertId}/acknowledge` with optional reason body. Record 204 and timestamp. [AC 7.6-1, AC 7.6-5]
- [x] Verify alert transition: `sparepart_alerts.status = 'ACKNOWLEDGED'`, `status_reason` set, `updated_at` matches acknowledge timestamp. [AC 7.6-1]
- [x] Verify TECHNICIAN job CANCELLED: `notification_jobs` for the alert — exactly one row (TECHNICIAN), status=CANCELLED, `next_attempt_at`=NULL, `updated_at` matches acknowledge. [AC 7.6-2]
- [x] Verify acknowledge audit: `audit_log` for entity_id = alertId, action = UPDATE, new_value JSON contains `actorId`, `transition: "OPEN→ACKNOWLEDGED"`, `status: "ACKNOWLEDGED"`, `reason`, `escalationCancelledCount: <int>` (≥1 if TECHNICIAN was PENDING/SENT/RATE_LIMITED; 0 if no jobs existed — unlikely but document). Note: the `actor_name` column shows "SYSTEM" (recordSystem); the real actor is in `new_value.actorId`. [AC 7.6-4]
- [x] Wait past escalation window: wait >15 minutes from the TECHNICIAN job's `sentAt`. Verify NO STAFF/LEADER/SPV/MANAGER notification_jobs rows were created for this alert. The hardest proof: after 15+ min, only the TECHNICIAN CANCELLED row exists. [AC 7.6-2, AC 7.6-3]
- [x] Escalation timeline proof: `GET /api/v1/alerts/{alertId}/notifications` → single TECHNICIAN job with status=CANCELLED. UI `/dashboard/alerts/{alertId}` → EscalationTimeline shows "Stopped by acknowledgement at {updatedAt}" or "Escalation stopped" marker. [AC 7.6-3]
- [x] Audit evidence: all audit rows for the alert ordered by created_at: (a) CREATE (SYSTEM) with new_value containing `traceId`; (b) if hygiene was applied, the resolve UPDATE row; (c) acknowledge UPDATE. Record actor, timestamp, action, result, and note the traceId correlation from the CREATE audit. [AC 7.6-4]
- [x] Mobile acknowledge proof: Playwright mobile viewport (390×844). Login as `technician.gm1@syncro.dev`. Navigate to `/dashboard/alerts/{alertId}`. Verify acknowledge button is visible (header; note whether sticky bottom bar from page-spec §3.6 is present). Tap Acknowledge → confirm dialog → success toast → status badge flips to ACKNOWLEDGED. Record the flow. [AC 7.6-5]
- [x] Cleanup + record: delete temp launcher/log files; write the AC → evidence mapping with exact commands and outputs into Dev Agent Record; `git status --short` must show ONLY this story file + `sprint-status.yaml`. [all ACs]

**Acceptance Criteria:**

- Given pilot alert is OPEN with escalation pending (TECHNICIAN job SENT/PENDING), when authorized responsible user acknowledges alert before next escalation interval, then alert transitions to ACKNOWLEDGED — evidenced by `sparepart_alerts.status = 'ACKNOWLEDGED'`, `status_reason` set, `updated_at` matching acknowledge timestamp. [AC 7.6-1]
- STAFF and LEADER notification jobs are not sent after acknowledgement — evidenced by: (a) TECHNICIAN job status = CANCELLED immediately after acknowledge; (b) after waiting >15 minutes past the escalation window, `notification_jobs` for the alert contains exactly one row (TECHNICIAN CANCELLED) with zero STAFF/LEADER/SPV/MANAGER rows. [AC 7.6-2]
- Escalation timeline shows acknowledgement stopped future escalation — evidenced by `GET /api/v1/alerts/{alertId}/notifications` returning TECHNICIAN job with CANCELLED status, and UI `/dashboard/alerts/{alertId}` EscalationTimeline rendering "Stopped by acknowledgement at {updatedAt}" / "Escalation stopped" marker. [AC 7.6-3]
- Audit records actor, timestamp, action, result, and traceId — evidenced by `audit_log` for the alert: CREATE row (SYSTEM, new_value.traceId), acknowledge UPDATE row (new_value.actorId, new_value.transition, new_value.status, new_value.reason, new_value.escalationCancelledCount, created_at). Note: `audit_log.actor_name` = "SYSTEM" (recordSystem); the real actor uuid is in `new_value.actorId`. [AC 7.6-4]
- Mobile alert view supports acknowledge path — evidenced by Playwright mobile viewport: logged in as technician.gm1@syncro.dev, navigated to `/dashboard/alerts/{alertId}`, Acknowledge button visible, tap → confirm → success toast → status ACKNOWLEDGED. [AC 7.6-5]

## Design Notes

- **Why the resolve-hygiene step is required:** Story 7-5 left an OPEN alert (likely escalated past TECHNICIAN to STAFF/LEADER by now). The V19 partial unique index `sparepart_alerts_dedup_idx (machine_sparepart_installation_id, threshold_percentage) WHERE status != 'RESOLVED'` prevents creating a second non-RESOLVED alert for the same installation+threshold. Resolving the existing alert via product endpoints (`POST /resolve-override` for OPEN, `POST /resolve` for ACKNOWLEDGED) clears the dedup guard, allowing the threshold publish to create a fresh OPEN alert. This is legitimate state hygiene using existing Phase 1 product features — not a source code change.
- **Why the "authorized responsible user" path prefers technician.gm1:** The AC says "authorized responsible user". The seed pilot user `technician.gm1@syncro.dev` has VIEWER application role, GM1 plant assignment, and is the TECHNICIAN-level recipient for JBF19. The acknowledge endpoint's `loadAndCheckAccess` uses `findByIdWithDetailsScopedToPlants` for non-SUPER_ADMIN users, which checks plant assignments. technician.gm1's GM1 assignment matches JBF19's plant. This proves the "responsible user" (not just SUPER_ADMIN) can acknowledge and stop escalation.
- **Acknowledge audit traceId gap:** The `AuditLogWriter.recordSystem()` does not persist a traceId column or embed one in `new_value`. The acknowledge audit row's `new_value` JSON contains `{actorId, transition, status, reason, escalationCancelledCount}` — no traceId. The AC "audit records ... traceId" is satisfied by the CREATE audit row (which DOES embed traceId in `new_value.traceId`), and the acknowledge audit row records actor, timestamp, action, and result. The traceId correlation is provable via the `sparepart_alerts.trace_id` column (which connects back to the telemetry traceId). Document this architecture gap in the Dev Agent Record.
- **Acknowledge audit actor_name = "SYSTEM":** The `acknowledge()` method uses `auditLogWriter.recordSystem(...)` (not `record(user, ...)`) because the codebase pattern for alert mutations uses `recordSystem` with the actorId embedded in the JSON `new_value`. This means `audit_log.actor_name` = "SYSTEM" and `audit_log.actor_id` = UUID(0,0). The real actor's UUID is in `new_value.actorId`. This is a pre-existing architecture decision; the AC "records actor" is met via the JSON payload.
- **cancelActiveForAlert includes RATE_LIMITED:** The actual implementation cancels `PENDING`, `SENT`, and `RATE_LIMITED` jobs. The spec-5-5 design originally listed only `[PENDING, SENT]`; the code was extended to include `RATE_LIMITED` (V29). If the TECHNICIAN job was in `RATE_LIMITED` state, it will be cancelled too. Document the actual job status observed.
- **Post-window STAFF/LEADER gap proof is the strongest evidence:** The 15-minute wait is the most rigorous AC. The double barrier (alert not OPEN + job CANCELLED) means:
  1. `EscalationWorker.findSentJobsDueForEscalation` queries `status = SENT` — CANCELLED rows are excluded from results.
  2. `EscalationService.escalate()` checks `alert.getStatus() != OPEN` — ACKNOWLEDGED skips.
  Both barriers must fail for a STAFF job to appear. If one appears, it's a genuine regression.
- **Why the verify-pilot.ps1 ACKNOWLEDGEMENT RESULT section is critical:** It prints `observation: STAFF job SENT while alert is ACKNOWLEDGED` if it detects a STAFF/LEADER job that was sent after acknowledge. This is the automated regression marker. The story must check this output after the post-window wait.
- **Mobile sticky bottom bar gap:** Page-specification §3.6 requires the `AlertActionPanel` to become a sticky bottom bar on mobile with primary action always visible without scrolling. The current implementation (`alert-detail-page-content.tsx`) places the acknowledge button in the header section, not as a sticky bottom bar. This is a pre-existing UX deviation from page-spec. The AC "mobile alert view supports acknowledge path" is still satisfied (the acknowledge action IS reachable on mobile), but the sticky bar requirement is noted as a page-spec gap. Document the actual mobile behavior.
- **Verify-pilot.ps1 ACKNOWLEDGEMENT RESULT is informational only:** The ACKNOWLEDGEMENT RESULT section (lines 453-474) prints `[INFO]` lines, not `[PASS]` or `[FAIL]`. It does not affect the exit code. The regression marker is a human-readable observation, not an automated check. The dev agent must independently verify the SQL evidence.
- **Threshold publish after resolve:** After resolving the existing alert, re-publishing the threshold fixture (counting=900) produces `countingDelta=0` (counter already 900). However, the `SparepartLifetimeEvaluator` computes `consumed = floorMod(900 - 0, 65536) = 900`, `consumedPct = 900 * 100 / 1000 = 90.00% >= 90%` → fires. The dedup check (`existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot`) returns false (no non-RESOLVED alert) → new OPEN alert created. This is confirmed by the 4-2 edge-case matrix: "Existing RESOLVED alert, threshold re-crossed → New OPEN alert created."
- **Notification dedup risk:** The `NotificationRoutingService` listens to `AlertOpenedEvent` via `@TransactionalEventListener(AFTER_COMMIT)`. If the old alert's `AlertOpenedEvent` was already consumed (days ago), no duplicate event fires. The fresh alert's `AlertOpenedEvent` will fire once, and `NotificationRoutingService` will insert a TECHNICIAN job. The `UNIQUE(alert_id, escalation_level)` constraint prevents duplicates. No risk of duplicate TECHNICIAN jobs from the hygiene→republish cycle.
- **Escalation drift from 7-5:** The 7-5 Dev Agent Record documented that by 05:34 UTC (about 15 min after the threshold publish at 05:18:25), the escalation worker created a STAFF job and set TECHNICIAN to ESCALATED. By now (days later), the old alert may have escalated through the full chain (LEADER, SPV, MANAGER, end of chain). The pre-hygiene state assessment must capture this for documentation in 7-7.

## Verification

**Commands (all live on the local stack, Windows PowerShell 5.1 `powershell.exe`):**
- Pre-pipeline: `docker compose --env-file syncro/.env -f syncro/infra/docker-compose.yml ps` — confirm postgres, redis, influxdb, emqx are running.
- `powershell -NoProfile -File syncro/scripts/seed-pilot.ps1` — expected: preflight PASS, canonical summary, exit 0.
- Temp launcher (env from `syncro/.env`, console captured to log file) + `curl http://localhost:8080/api/v1/health` — expected: 200.
- Pre-hygiene state assessment SQL (record verbatim).
- Hygiene: `curl -X POST -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/alerts/<alertId>/resolve-override` (if OPEN) or `/resolve` (if ACKNOWLEDGED) — expected: 204.
- Post-hygiene SQL: `SELECT count(*) FROM sparepart_alerts WHERE status <> 'RESOLVED';` — expected: 0.
- `powershell -NoProfile -File syncro/scripts/publish-jbf19-threshold.ps1` — expected: login PASS, publish accepted, messageId `pilot-jbf19-threshold-900` verbatim.
- Backend console capture: `Select-String -Path <log> -Pattern 'Alert created for installation'` — expected: one line with traceId, installationId, threshold 90%, consumed 90.00%.
- `powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 -ExpectCounting 900` — expected: ALERT PASS (exactly one non-RESOLVED alert, snapshot 90.00, traceId printed), NOTIFICATION section (TECHNICIAN job status printed), ACKNOWLEDGEMENT RESULT (alert status OPEN, job timeline).
- Acknowledge: `curl -X POST -H "Authorization: Bearer <token>" -H "Content-Type: application/json" -d '{"reason": "Acknowledged via pilot validation"}' http://localhost:8080/api/v1/alerts/<alertId>/acknowledge` — expected: 204 (no body). Authenticate as technician.gm1@syncro.dev (get token via `POST /api/v1/auth/login`).
- Post-acknowledge SQL: `SELECT id, status, status_reason, updated_at FROM sparepart_alerts WHERE id = '<alertId>';` — expected: `ACKNOWLEDGED`, status_reason set, updated_at matches acknowledge.
- Post-acknowledge jobs SQL: `SELECT escalation_level, status, updated_at, next_attempt_at FROM notification_jobs WHERE alert_id = '<alertId>' ORDER BY escalation_level;` — expected: single row `TECHNICIAN | CANCELLED | <ack-time> | <null>`.
- Post-acknowledge audit SQL: `SELECT action, new_value, created_at FROM audit_log WHERE entity_id = '<alertId>' AND entity_type = 'ALERT' ORDER BY created_at;` — expected rows: CREATE (new_value has traceId), UPDATE (new_value has actorId, transition, status, reason, escalationCancelledCount), and if hygiene applied, an UPDATE for the resolve.
- Post-window (>15 min from TECHNICIAN sentAt): re-run the jobs SQL — expected: same single row (no STAFF/LEADER).
- Verify-pilot after post-window: `powershell -NoProfile -File syncro/scripts/verify-pilot.ps1 -ExpectCounting 900` — ALERT section shows `exactly one non-RESOLVED alert` (the acknowledged alert is still non-RESOLVED). Wait — the ACKNOWLEDGED alert is non-RESOLVED (ACKNOWLEDGED != RESOLVED). So verify-pilot's ALERT section will report it. The ACKNOWLEDGEMENT RESULT section will show `alert status: ACKNOWLEDGED` and the TECHNICIAN CANCELLED job. Confirm no `observation: STAFF job SENT while alert is ACKNOWLEDGED` line.
- API timeline: `curl -H "Authorization: Bearer <token>" http://localhost:8080/api/v1/alerts/<alertId>/notifications` — expected 200 with `items` containing single TECHNICIAN CANCELLED job.
- UI (desktop browser): login as `technician.gm1@syncro.dev` → `/dashboard/alerts/<alertId>` — expected: status badge ACKNOWLEDGED, EscalationTimeline shows TECHNICIAN CANCELLED "Stopped by acknowledgement", Action Panel shows Resolve only (no Acknowledge button).
- UI (mobile playwright): viewport 390×844, login as `technician.gm1@syncro.dev`, navigate to `/dashboard/alerts/<alertId>` — expected: acknowledge path works (button → confirm → success → ACKNOWLEDGED). Note whether sticky bottom bar is present (page-spec gap).
- `git status --short` — expected: this story file + `_bmad-output/implementation-artifacts/sprint-status.yaml`.

## Dev Agent Record

### Agent Model Used

- DeepSeek (b-ai/deepseek-v4-flash), opencode CLI, running the `bmad-dev-story` workflow for story 7-6.

### Debug Log References

- Backend console (INFO evidence markers) captured live to `C:\Users\Dell\AppData\Local\Temp\opencode\backend-pilot.log` (transient, outside repo, NOT committed). Key lines:
  - `13:38:36.045+07 INFO c.s.t.a.MqttTelemetryIngestHandler : mqtt_telemetry_accepted traceId=4ce97ea9-e492-4d29-bd2d-2f8a93189931 topic=factory/GM1/BF-08410/telemetry`
  - `13:38:37.206+07 INFO c.s.a.application.SparepartAlertService : [traceId=4ce97ea9-e492-4d29-bd2d-2f8a93189931] Alert created for installation 591f669e-69f4-46a5-839f-e68c5c3aa730 threshold 90% consumed 90.00%`
  - `13:56:50.957+07 INFO MqttTelemetryIngestHandler : mqtt_telemetry_accepted traceId=c627edda-b2cb-4063-bc73-6dace71799c6 topic=factory/GM1/BF-08410/telemetry`
  - `13:56:52.013+07 INFO SparepartAlertService : [traceId=c627edda-b2cb-4063-bc73-6dace71799c6] Alert created for installation 591f669e-... threshold 90% consumed 90.00%`
  - Startup: `mqtt_subscription_request topic=factory/+/+/telemetry qos=1`; `Started SyncroBackendApplication in 26.84 seconds` (13:27:24+07); Tomcat on port 8080.
- Playwright snapshots/screenshots (transient, `.playwright-mcp/` gitignored): desktop alert detail (ACKNOWLEDGED + "Stopped by acknowledgement"), mobile 390x844 pre/post acknowledge. Screenshot PNGs at repo root were deleted during cleanup (temp evidence only).
- Local dev DB via in-container `psql` (pgAdmin not used; psql is the documented equivalent evidence tool).

### Completion Notes List

**Environment / preflight (AC 7.6-1 preamble):**
- Infra stack up (docker compose `--env-file syncro/.env`): postgres, redis, influxdb, emqx, waha, pgadmin all running (healthy).
- `seed-pilot.ps1` exit 0: Flyway preflight PASS (31 migrations), all `INSERT 0 0`, canonical summary matches incl. tripwire `Electric · PLC · Wecon · LX5`, 3 users, LEADER/STAFF/TECHNICIAN responsibilities = 1 each.
- Backend restarted under a temp launcher (loads `syncro/.env` via the `start-backend.ps1` regex idiom, then `mvnw -f syncro/apps/backend/pom.xml spring-boot:run`, stdout+stderr → `backend-pilot.log`). Health `GET /api/v1/health` → 200.
- **Blocker resolved with user approval:** initial backend boot FAILED with `FlywayValidateException: Migration checksum mismatch for migration version 31` (applied `948189777` vs resolved `148573209`). Root cause: commit `f4eeafa` (7-5 review) edited only the V31 `--` comment lines; the SQL is byte-identical. The `flyway/flyway:13.3.0` repair was a no-op (different checksum algorithm than the backend's flyway-core-11.14.1). Fix (user-approved "run flyway repair"): `flyway/flyway:11.14.1` with the migrations dir mounted (`-v ...db\migration:/flyway/sql:ro`) ran `repair`, realigning `flyway_schema_history` V31 checksum to `148573209`; backend then booted clean. No source file, no data wipe — only the schema-history checksum record was updated.
- Web app: production build serving on `http://localhost:3001` (`next start`), login verified. Detail route is `/dashboard/alerts/{id}` (rewrite covers only `/alerts`, not `/alerts/:path*`).

**State assessment + clean slate (pre-hygiene):**
- Pre-hygiene non-RESOLVED alerts: `3ce1b48c-a7bc-49a1-a716-615eea3fab09` (OPEN, trace `ebfeecc74-...`, created 05:18:26Z) — the 7-5 leftover, fully escalated: TECHNICIAN ESCALATED (sent 05:18:39Z), STAFF ESCALATED (sent 05:34:15Z), LEADER EXHAUSTED (never sent, 05:53:21Z). Confirms the 7-5 escalation-drift documentation.
- Hygiene: `POST /api/v1/alerts/3ce1b48c-.../resolve-override` as SUPER_ADMIN `admin@syncro.dev` (login OK, role SUPER_ADMIN) with body `{"reason":"Clean slate for 7-6 validation"}` → **204**. Post-hygiene: zero non-RESOLVED alerts. Audit row recorded: UPDATE `{"transition":"OPEN→RESOLVED(override)","reason":"Clean slate for 7-6 validation","status":"RESOLVED","actorId":"bf68c46f-..."}`.

**Threshold publish + alert creation:**
- `publish-jbf19-threshold.ps1` (run 1): EMQX login PASS, publish accepted; messageId `pilot-jbf19-threshold-900` verbatim, counting 900, refreshed timestamp `2026-08-22T06:38:35Z`, topic `factory/GM1/BF-08410/telemetry`.
- New alert **A = `08338a5a-03f2-48a6-98dc-f333f835c22c`**, status OPEN, `consumed_percentage_snapshot=90.00`, `threshold_percentage=90`, traceId `4ce97ea9-e492-4d29-bd2d-2f8a93189931` (SQL query 4). Backend markers captured (see Debug Log).
- TECHNICIAN notification job created; observed status transition PENDING (06:38:37Z) → SENT (06:38:56Z, `sent_at`).

**Acknowledge (responsible user path) + verification:**
- Login `technician.gm1@syncro.dev` / `syncro-pilot-dev` → VIEWER role, user id `2b67b210-61a6-4184-9454-47443c416e84` (GM1 plant assignment).
- `POST /api/v1/alerts/08338a5a-.../acknowledge` body `{"reason":"Acknowledged by technician during pilot validation (7-6)"}` → **204** at 06:40:54Z (13:40:54 local). Well before the escalation window (TECHNICIAN sentAt 06:38:56Z + 15 min = 06:53:56Z).
- **AC 7.6-1:** alert → `ACKNOWLEDGED`, `status_reason = 'Acknowledged by technician during pilot validation (7-6)'`, `updated_at = 06:40:54.723Z` (matches acknowledge). SQL (5) captured.
- **AC 7.6-2 (a):** `notification_jobs` for A → exactly one row TECHNICIAN `CANCELLED`, `next_attempt_at` NULL, `updated_at = 06:40:54.724Z`, `sent_at = 06:38:56.406Z`. SQL (6) captured.
- **AC 7.6-4:** `audit_log` for A: (1) CREATE (SYSTEM, `new_value.traceId = 4ce97ea9-...`, 06:38:37Z); (2) UPDATE (SYSTEM actor_name, `new_value` = `{"transition":"OPEN→ACKNOWLEDGED","actorId":"2b67b210-...","reason":"Acknowledged by technician during pilot validation (7-6)","status":"ACKNOWLEDGED","escalationCancelledCount":1}`, 06:40:54Z). `audit_log.actor_name` = `SYSTEM` (recordSystem pattern); the real actor UUID is in `new_value.actorId` — recorded as the known architecture deviation. No traceId on the acknowledge row (known gap, see warnings).
- `verify-pilot.ps1 -ExpectCounting 900` (run 1) → RESULT **PASS**, exit 0: ALERT section `exactly one non-RESOLVED alert: status=ACKNOWLEDGED ... trace_id=4ce97ea9-...`; NOTIFICATION section `TECHNICIAN CANCELLED`; ACKNOWLEDGEMENT RESULT `alert status: ACKNOWLEDGED`, `job timeline: level=TECHNICIAN status=CANCELLED`. No `observation: STAFF job SENT while alert is ACKNOWLEDGED`.

**Escalation timeline (AC 7.6-3):**
- `GET /api/v1/alerts/08338a5a-.../notifications` (as technician.gm1) → **200**, `total:1`: single TECHNICIAN job `status=CANCELLED`, `sentAt=2026-08-22T06:38:56Z`, `updatedAt=06:40:54Z`, `nextAttemptAt=null`, traceId `4ce97ea9-...`, attempt 1 `SENT` at 06:38:57Z with the WAHA `true_6281234567801@c.us_...` response detail (message WAS delivered to WhatsApp before cancellation).
- UI (desktop, logged in as technician.gm1, `/dashboard/alerts/08338a5a-...`): status badge **Acknowledged** ("Escalation is paused"), EscalationTimeline shows `TECHNICIAN ... Stopped` + `Stopped by acknowledgement at Aug 22, 2026, 1:38 PM`, Notification History row `TECHNICIAN / CANCELLED / 0-3 attempts / 4ce97ea9…`, Action Panel shows only **Resolve** (no Acknowledge button), Audit Evidence lists UPDATE + CREATE rows. See warnings for the timeline timestamp nuance (renders sentAt 1:38 PM, not updatedAt 1:40 PM).

**Post-window proof (AC 7.6-2 b, the hardest AC):**
- Waited past the escalation window. Post-window SQL at **06:55:42Z** (>15 min after TECHNICIAN sentAt 06:38:56Z; last possible STAFF dispatch = 06:53:56Z; EscalationWorker polls every 60s): `notification_jobs` for A → exactly one row (TECHNICIAN CANCELLED); `count(*) WHERE escalation_level <> 'TECHNICIAN'` → **0**. No STAFF/LEADER/SPV/MANAGER job ever created. Double barrier held: job CANCELLED excluded by `findSentJobsDueForEscalation` (`status=SENT` filter) + alert non-OPEN guarded `escalate()`.
- NOTE: alert A was resolved via the UI by technician.gm1 at **06:52:46Z** (audit-recorded `{"transition":"ACKNOWLEDGED→RESOLVED","reason":"","status":"RESOLVED","actorId":"2b67b210-..."}`) — a user action during the run, not my automation. It did NOT invalidate the ACs: AC 7.6-1 evidence was captured at 06:40:54Z; AC 7.6-2b evidence was captured at 06:55:42Z on the notification_jobs (clean); the alert was non-OPEN the entire window.

**Audit evidence chain (AC 7.6-4):**
- Alert A audit rows (ordered): CREATE (SYSTEM, `new_value.traceId`, 06:38:37Z) → UPDATE acknowledge (06:40:54Z, actorId/transition/status/reason/escalationCancelledCount) → UPDATE resolve (06:52:46Z, user action). traceId correlation: telemetry marker `4ce97ea9-...` → `sparepart_alerts.trace_id` → CREATE audit `new_value.traceId`.
- Hygiene alert `3ce1b48c-...` audit rows: CREATE (06:38:26Z wait 05:18:26Z) + UPDATE resolve-override (06:38:11Z) — recorded for 7-7.

**Mobile acknowledge proof (AC 7.6-5):**
- Playwright mobile viewport 390×844, logged in as `technician.gm1@syncro.dev` (after resolving A, re-published to create a fresh OPEN alert **B = `edd852ef-bcb2-4b34-8fd0-5a81b565d20d`**, traceId `c627edda-b2cb-4063-bc73-6dace71799c6`, TECHNICIAN job SENT 06:56:58Z; dedup guard was clear because A was RESOLVED).
- Mobile alert detail `/dashboard/alerts/edd852ef-...`: status badge **Open**; **Acknowledge button visible in the header** (page-spec §3.6 sticky bottom bar is NOT implemented — documented deviation; the action IS reachable on mobile without scrolling to it in the header).
- Tap **Acknowledge this alert** → immediate API call (NO confirm dialog — see warnings) → `toast.success("Alert acknowledged.")` → status badge flipped to **Acknowledged**, button became **Resolve this alert**, `Last updated 13:57:43`.
- SQL/audit for B: status `ACKNOWLEDGED`, updated_at 06:57:43Z; TECHNICIAN job `CANCELLED` (sentAt 06:56:58Z, updatedAt 06:57:43Z, next_attempt_at NULL); audit UPDATE `{"transition":"OPEN→ACKNOWLEDGED","actorId":"2b67b210-...","reason":"","status":"ACKNOWLEDGED","escalationCancelledCount":1}` (no reason — the mobile UI acknowledge sends no reason body) + CREATE (`new_value.traceId = c627edda-...`).
- Mobile post-reload: badge **Acknowledged**, EscalationTimeline `TECHNICIAN ... Stopped`, Notification History `CANCELLED`, Audit Evidence UPDATE + CREATE.
- `verify-pilot.ps1 -ExpectCounting 900` (run 2, after mobile acknowledge) → RESULT **PASS**, exit 0: exactly one non-RESOLVED alert = B (ACKNOWLEDGED, 90.00, trace c627edda-...), TECHNICIAN CANCELLED, ACKNOWLEDGEMENT RESULT clean. No regression observation.

**Cleanup:**
- Removed temp screenshots from the repo root; launcher/logs remain in `C:\Users\Dell\AppData\Local\Temp\opencode\` (outside repo, transient). `git status --short` → only `_bmad-output/implementation-artifacts/spec-7-6-validate-acknowledgement-stops-escalation.md` + `_bmad-output/implementation-artifacts/sprint-status.yaml`. Backend left running (same code/env as before; health 200).

**Deviations / notes (cross-ref warnings):**
1. Acknowledge audit has no traceId (recordSystem architecture gap) — AC satisfied via CREATE row + `sparepart_alerts.trace_id`.
2. Timeline "Stopped by acknowledgement at" renders `job.sentAt` (1:38 PM) not updatedAt (1:40 PM); `alert-notification-history.tsx` maps `job.sentAt ?? updatedAt ?? createdAt` for non-ESCALATED steps.
3. No confirm dialog on Acknowledge (one-click + toast).
4. Mobile sticky bottom bar (page-spec §3.6) absent; button in header, reachable.
5. Alert A resolved mid-run by user (technician.gm1, 06:52:46Z) — audit-recorded, ACs unaffected.
6. `RATE_LIMITED` job state not observed (job was SENT); `cancelActiveForAlert` handles PENDING/SENT/RATE_LIMITED per code.

### File List

- `_bmad-output/implementation-artifacts/spec-7-6-validate-acknowledgement-stops-escalation.md` — this story file (tasks checked, Dev Agent Record filled, status → done, warnings + followup_review_recommended set).
- `_bmad-output/implementation-artifacts/sprint-status.yaml` — `7-6-validate-acknowledgement-stops-escalation: in-progress` → `done`.
- `C:\Users\Dell\AppData\Local\Temp\opencode\launch-backend-pilot.ps1` / `backend-pilot.log` / `backend-pilot-err.log` — transient temp launcher + captured console (NOT committed).
- `.playwright-mcp/` — transient playwright snapshots/screenshots (gitignored, NOT committed).
- No source files under `syncro/` were modified. (DB V31 checksum record was realigned via `flyway repair` with explicit user approval; no source/data change.)

</intent-contract>