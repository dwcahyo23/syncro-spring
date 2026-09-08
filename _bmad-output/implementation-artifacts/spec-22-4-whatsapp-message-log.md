---
title: 'Story 22-4: WhatsApp Message Log (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-08'
status: 'done'
baseline_revision: '0ea399d'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-22-context.md'
  - '_bmad-output/implementation-artifacts/spec-22-1-webhook-config-delivery-log.md'
warnings: [oversized]
deferred:
  - summary: >-
      Catch-around-the-upsert stops exception propagation but a DB-level violation (FK/length/anchor race)
      still marks the dispatch transaction rollback-only, so attempt+job writes roll back after the send
      landed; full isolation needs a savepoint or REQUIRES_NEW around the log write.
    evidence: |-
      Postgres semantics: a statement error inside a tx poisons it regardless of the Java catch. Rare in
      practice — log columns mirror source column widths and FK targets come from the same job row — but
      the duplicate-send window is narrowed, not closed.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java
    severity: medium
  - summary: >-
      Second circuit-open dispatch on one job reuses attempt_number (markCircuitOpen never increments),
      so the attempt save hits uq_notification_attempts_job_attempt and aborts the tx before the log hook.
    evidence: |-
      Pre-existing Epic 5 markCircuitOpen semantics surfaced by 22-4's review; fixing it changes
      notification_attempts contract behavior — out of this story's additive scope.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobEntity.java
    severity: medium
  - summary: >-
      No row claim (FOR UPDATE SKIP LOCKED / lease) on the notification sweep, so two instances can
      double-send and race the message-log anchor.
    evidence: |-
      Same posture deferred in 22-1 (webhook worker); single-instance deployment today; needs a
      project-wide decision covering both workers.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java
    severity: low
  - summary: >-
      "No WAHA secret in any log line" is enforced by code posture only; no test asserts log-output absence.
    evidence: |-
      Column/response halves are tested (INT-002, UNIT-004/005, API-003); dispatch log lines carry only
      traceId/jobId. House precedent has no appender-capture tests either.
    location: >-
      syncro/apps/backend/src/test/java/com/syncro/notification/
    severity: low
---

<intent-contract>

## Intent

**Problem:** `whatsapp_message_logs` exists in V1 (blueprint I5) but is inbound-shaped (`waha_message_id NOT NULL UNIQUE`, `from_phone`, `received_at`) and nothing writes it; every WAHA send is only visible through `notification_jobs`/`notification_attempts`, so there is no per-message outbound evidence view (recipient, template, status, traceId) for escalation / 4-hour-ack / request sends (FR-180/181, NFR-P2-7).

**Approach:** Extend the same table additively (V20) with outbound columns and relax `waha_message_id` to nullable; hook one message-log upsert into the single WAHA send call site (`NotificationDispatchService.dispatch`, the only `WahaClient.send` caller — covers 100% of sends) inside the existing per-outcome transaction, writing masked recipient + derived template + status + attempt count + traceId + a SHA-256 reference of the rendered text (never the raw text/phone/secret); add a SUPER_ADMIN-only paged read endpoint mirroring 22-1's delivery reads. No new audit type, no frontend UI.

## Boundaries & Constraints

**Always:**
- Forward-only change; never edit V1..V19; V20 is additive (new columns + `waha_message_id DROP NOT NULL` widening + a unique index; no CHECK/enum drop-recreate needed since no new audit type)
- One message-log row per logical notification (per `notification_job_id`, the job's idempotency key already encodes target+template+recipient+event), upserted across dispatch retries — the AC's dedupe identity (target_type+target_id+template+recipient) is stored as queryable columns but the physical unique anchor is `notification_job_id` because the masked phone is lossy (two numbers sharing prefix+last-3 collide) and the raw phone must never be stored
- The log write rides the SAME `transactionTemplate` block as the attempt write in each of dispatch's three outcome branches (render-fail / success / failure+circuit-open); the rate-limited early-return makes no send and writes no row
- Recipient stored masked only via `NotificationHistoryDtos.maskPhone` (first3+last3); rendered text stored as `text_sha256` (MessageDigest hex, 22-3 precedent), never raw; no WAHA secret/API key in any column, log line, or response
- `template_name` derived for every job so distinct events never collide: alert jobs → `alert_notification` (`WahaTemplate.DEFAULT_KEY`); lifecycle/ack/sparepart pre-composed jobs → a stable per-event/step marker parsed from the idempotency-key prefix (`WORKORDER:{woId}:{event}:{userId}`, `WORKORDER_ACK:{woId}:{userId}`, `SPAREPART_REQUEST:{requestId}:{step}:{userId}`); store the raw `idempotency_key` too so any derivation is recoverable
- `target_type`/`target_id`: `ALERT`/`job.alertId` when alertId present, else parsed from the idempotency-key prefix; `trace_id` copied from `job.traceId`; `sent_at`/timestamps from the injected `Clock` (UTC `Instant`); `@Version` on the entity
- Reads are SUPER_ADMIN-only: service gate AND rego (`/api/v1/whatsapp-message-logs` added to `admin_only_paths` + `SYNCRO_AUTHZ_ENFORCED_PATHS`) AND parity test; paged newest-first by `sent_at`, filterable by status/target_type/work_order_id/trace_id; masked projections only
- DTO records with Bean Validation; error envelope via the notification module's existing handler; `Instant` ISO-8601 UTC; uppercase enum strings

**Block If:**
- A change to V1..V19, the `notification_jobs`/`notification_attempts` contract, or `WahaClient.Result` shape seems required → HALT (all 22-4 work is additive to the log table + a hook; the WAHA message id is deliberately NOT parsed from the 2xx body — per-attempt raw response already lives in `notification_attempts.response_detail`, and the log complements it)

**Never:**
- No frontend UI (evidence view is a later story; only the backend read endpoint ships here)
- No INBOUND webhook/WAHA-receive handling (the inbound columns stay unused; only OUTBOUND sends are logged this story)
- No new audit entity type / no `ck_audit_log_entity_type` change (message logs are system-generated evidence, not user mutations — same posture as 22-1 delivery logs)
- No parsing of the WAHA message id from the response; no storing raw phone or raw message text; no new dependencies, no Lombok/MapStruct; no `@Retry` (dispatch is already DB-driven)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Alert send 2xx | dispatch success | 1 OUTBOUND row SENT, masked recipient, template `alert_notification`, traceId, text_sha256, attempt_count 1 | — |
| Lifecycle DONE then CLOSED | same woId+user | two rows (distinct derived template_name), no collision | — |
| Retry 5xx→2xx | same job, 2 dispatches | one row updated FAILED→SENT, attempt_count 2, latest sent_at | — |
| Render fail | template render throws | row FAILED, text_sha256 null, reason recorded | — |
| Rate-limited | early return, no send | NO row written | — |
| Duplicate dispatch race | two writers, same job | unique index on notification_job_id → no duplicate row | DIV → treated as update (single-instance today; multi-instance deferred) |
| List logs | SUPER_ADMIN ?status=FAILED | page newest-first, masked, links notification_job_id | 403 for other roles |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `V1__orm_foundation_schema.sql:1834-1852` -- whatsapp_message_logs DDL (inbound-shaped; read-only reference)
- `notification/infrastructure/WhatsAppMessageLogEntity.java` + `WhatsAppMessageLogRepository.java:10` -- mapped inbound-only -- add outbound fields + @Version + findByNotificationJobId
- `notification/application/NotificationDispatchService.java:54-149` -- dispatch(); line 101 the ONLY WahaClient.send call; 3 outcome branches each in transactionTemplate -- hook log upsert here
- `notification/application/NotificationWorker.java:48` -- poll→dispatch (only caller)
- `notification/infrastructure/WahaClient.java:101,129,141,177` -- send + Result record + truncated detail (no id parse; leave as-is)
- `notification/infrastructure/NotificationJobEntity.java` -- recipientUserId/recipientPhone/alertId/idempotencyKey/traceId/messageBody/escalationLevel -- source of log fields
- `notification/infrastructure/NotificationAttemptEntity.java` -- per-attempt evidence the log complements (uq job+attempt)
- `notification/api/NotificationHistoryDtos.java:69-78` -- maskPhone (first3+last3) -- reuse for recipient_masked
- `notification/domain/WahaTemplate.java:13` -- DEFAULT_KEY alert_notification
- idempotency-key grammar: `WorkOrderNotificationRoutingService.java:137`, `WorkOrderAckWorker.java:163`, `SparepartRequestEscalationService.java:228`, `NotificationRoutingService`/`EscalationService` (alert)
- `integration/application/WebhookConfigService.java:231` (maskSecret) + `WebhookDispatchListener` saveIgnoreDuplicate + `WebhookController`/`WebhookDeliveryQueryService` -- 22-1 precedents to mirror for masking + read endpoint + selective swallow
- `authz/policy/authz.rego` admin_only_paths + `syncro/.env.example` enforced-paths tail + `PmAuthzEnforcementParityTest.java`
- Tests: `NotificationDispatchServiceTest.java` (add log repo mock), `NotificationJobCancelIntegrationTest.java` (Testcontainers pattern), `V1BaseSchemaMigrationTest.java:270` (table already expected)

## Tasks & Acceptance

**Execution:**
- `db/migration/V20__whatsapp_message_log_outbound.sql` -- create -- `ALTER COLUMN waha_message_id DROP NOT NULL`; add direction, notification_job_id (FK SET NULL) + UNIQUE, target_type, target_id, template_name, recipient_masked, status, attempt_count, trace_id, text_sha256, sent_at, version; index on status + trace_id; update V1BaseSchemaMigrationTest expected columns
- `notification/infrastructure/WhatsAppMessageLogEntity.java` + `WhatsAppMessageLogRepository.java` -- extend -- outbound fields + @Version + mutators (markSent/markFailed/bumpAttempt) + findByNotificationJobId
- `notification/application/WhatsAppMessageLogService.java` -- create -- upsertForDispatch(job, outcome, renderedTextOrNull, now): derive template_name + target_type/id from job (alertId else idempotency-key prefix), mask recipient, sha256 text, write/update the one row; best-effort within dispatch's tx
- `notification/application/NotificationDispatchService.java` -- extend -- call the log upsert in each of the 3 outcome branches inside the existing transactionTemplate; no call on rate-limited return
- `notification/api/WhatsAppMessageLogController.java` + DTOs + handler -- create -- `GET /api/v1/whatsapp-message-logs?status=&targetType=&workOrderId=&traceId=` paged newest-first, SUPER_ADMIN-only, masked projections
- `config`/`application.yml` -- none needed (no new tunables); confirm no hardcoded values
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest` -- extend -- `/api/v1/whatsapp-message-logs` in admin_only_paths + env; rego tests (SUPER_ADMIN allowed, MANAGER denied)
- Tests: `WhatsAppMessageLogServiceTest` (Mockito: template derivation per prefix, masking, sha256, upsert-vs-insert, alert vs lifecycle), `NotificationDispatchServiceTest` (extend: each outcome branch writes/updates the row; rate-limit writes none), `WhatsAppMessageLogControllerTest` (@WebMvcTest envelope incl. 403/401 + filters), `WhatsAppMessageLogIntegrationTest` (Testcontainers: V20 applies, dispatch→row, retry→same row updated, DONE+CLOSED distinct, unique job anchor, masked read)

**Acceptance Criteria:**
- Given any WAHA send is dispatched, when it completes (success, failure, or render-fail), then exactly one `whatsapp_message_logs` OUTBOUND row exists for that job carrying masked recipient, derived template, status, attempt count, traceId, and a text SHA-256 reference — and no column/log/response holds a raw phone, raw message text, or WAHA secret (AC1)
- Given a job is retried, when re-dispatched, then the same row is updated (attempt_count, status, sent_at) rather than duplicated; two distinct logical notifications (different event/recipient/target) never collide on the dedupe identity (AC2)
- Given a message-log read, when attempted, then SUPER_ADMIN-only (rego+service parity), newest-first paged with status/target/workorder/trace filters, and each row links back via notification_job_id for drill-down (AC3)

## Spec Change Log

- 2026-09-08 (review pass 1): Design Notes reconciled — the log write is best-effort (catch+warn; an evidence gap is tolerated, a duplicate send is not), resolving the "rides the SAME transactionTemplate" vs "best-effort" tension toward catch-around-the-upsert while keeping the write in-tx on success. Tasks bullets corrected: read surface is `WhatsAppMessageLogQueryService` (was missing from the task list); error envelope uses a new per-controller advice (house pattern, `WahaTemplateExceptionHandler` precedent) — the "existing handler" wording was wrong; V20 additionally widens `received_at` (DROP NOT NULL + DROP DEFAULT) and adds `logged_at NOT NULL` + ordering index. AC1's "text SHA-256 reference" reads as present-when-rendered (render-fail stores null per the matrix — the only sane reading, no text exists to hash). No code re-derivation needed; the implementation already embodies these resolutions.

## Review Triage Log

### 2026-09-08 — Review pass
- intent_gap: 0
- bad_spec: 1: (high 0, medium 1, low 0)
- patch: 11: (high 2, medium 4, low 5)
- defer: 4: (high 0, medium 2, low 2)
- reject: 5
- addressed_findings:
  - `[medium]` `[bad_spec]` Spec internal contradictions (AC1 vs matrix on render-fail hash; "existing handler" vs "create handler"; QueryService missing from Tasks; "rides SAME tx" vs "best-effort"; V20 bullet omitted received_at widening) → resolved via Spec Change Log + Design Notes/Tasks amendments above; code already followed the only defensible reading, no loopback.
  - `[high]` `[patch]` Mandatory log write inside dispatch tx could roll back attempt+job AFTER the WAHA send landed → infinite duplicate-send loop → upsert wrapped in catch+log.warn (best-effort), new unit test proves SENT persists when log write throws.
  - `[high]` `[patch]` maskPhone returns ≤6-char inputs unmasked → raw phone in recipient_masked/API → collapse to "***" when masked equals input (UNIT-004b).
  - `[medium]` `[patch]` DESC sort puts null sent_at FAILED rows first (spec's own ?status=FAILED matrix row broken) → JPQL `sentAt desc nulls last, loggedAt desc, id desc` + new `logged_at NOT NULL` column (never-sent rows now carry a timestamp); INT-006 asserts failed-last.
  - `[medium]` `[patch]` V20 dropped NOT NULL but kept received_at DEFAULT NOW() (header comment false) → DROP DEFAULT added + schema-test assertion.
  - `[medium]` `[patch]` INT-006 status/targetType filter assertions vacuous (all-SENT/all-WORK_ORDER dataset) → mixed dataset with 503-stubbed FAILED row + SPAREPART_REQUEST row; filters now discriminate.
  - `[medium]` `[patch]` Read not scoped to direction → findLogs filters OUTBOUND; INBOUND seed row asserted excluded.
  - `[low]` `[patch]` No index for the list ordering → composite `(sent_at DESC NULLS LAST, logged_at DESC, id DESC)` index in V20.
  - `[low]` `[patch]` Unreachable handlers in WhatsAppMessageLogExceptionHandler (409 version-conflict, 400 missing-param on a read-only all-optional-param surface) → removed.
  - `[low]` `[patch]` .env.example story-comment block missing 22-4 line → added.
  - `[low]` `[patch]` AD-9 deferral note lacked literal `ponytail:` token → prefixed.
  - `[low]` `[patch]` Circuit-open branch's log hook unverified → NotificationDispatchServiceTest asserts upsertForDispatch(FAILED) on that branch.

## Design Notes

- The V1 table is shared inbound+outbound per blueprint I5; `direction` defaults OUTBOUND and the inbound columns (from_phone/received_at/payload) stay null for send rows. `waha_message_id` is relaxed to nullable because outbound rows have no parsed WAHA id (deliberately not parsed — the raw per-attempt response already lives in `notification_attempts.response_detail`, which the log complements, not replaces).
- Dedupe anchor = `notification_job_id` (unique), not the 4-part key: the masked phone is lossy (collision risk) and the raw phone must never be stored, so the job — whose own idempotency key already encodes target+template+recipient+event — is the collision-free logical-notification identity. The 4 AC components are still stored as columns for filtering/display.
- `template_name` is derived (never null) so lifecycle events sharing woId+user don't collapse: DONE vs CLOSED get distinct markers from the idempotency-key event segment. `ponytail: prefix-parsing mapper is the de-facto AD-9 discriminator today; if the real target_type/target_id columns ever land on notification_jobs, read them directly instead of parsing`.
- Log write is best-effort: the upsert rides the same `transactionTemplate` block as the attempt write (both are dispatch evidence, commit together on success), but the call is wrapped in catch+log.warn so an evidence failure never breaks dispatch — a missing log row is tolerable, a duplicate WhatsApp send is not (review 22-4 P1). A DB-level violation inside the catch still poisons the tx (Postgres semantics); full isolation needs a savepoint — deferred. Single-instance deployment assumed (mirrors 22-1's deferred multi-instance row-claim risk).
- `logged_at NOT NULL` (server Clock at row creation) is the ordering fallback for never-sent rows whose `sent_at` is null; the list sort is `sent_at DESC NULLS LAST, logged_at DESC, id DESC` so FAILED rows trail rather than lead (Postgres DESC puts NULLs first).

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*WhatsApp*,NotificationDispatchServiceTest,NotificationWorkerTest,V1BaseSchemaMigrationTest,PmAuthzEnforcementParityTest"` -- expected: green incl. Testcontainers V20
- `syncro/authz/run-opa-test.ps1` -- expected: all policy tests PASS

## Auto Run Result

Status: done

**Summary:** Every WAHA send now writes exactly one `whatsapp_message_logs` OUTBOUND row (masked recipient, derived template/target, status, attempt count, traceId, rendered-text SHA-256, logged_at/sent_at) hooked into all three outcome branches of the single `NotificationDispatchService.dispatch` call site, best-effort (catch+warn — an evidence failure never breaks dispatch or duplicates a send). V20 widens the V1 inbound-shaped table additively (nullable waha_message_id/received_at + DROP DEFAULT, direction, notification_job_id UNIQUE dedupe anchor, target/template/status/trace/text-hash columns, logged_at, ordering index). SUPER_ADMIN-only paged read endpoint with discriminating status/targetType/workOrderId/traceId filters, direction-scoped to OUTBOUND, newest-first with NULLS LAST. No new audit type, no frontend.

**Files changed (21):**
- `db/migration/V20__whatsapp_message_log_outbound.sql` (new) — additive widening + outbound columns + unique anchor + indexes
- `notification/domain/WhatsAppMessageLogStatus.java`, `WhatsAppMessageDirection.java` (new) — uppercase enums
- `notification/infrastructure/WhatsAppMessageLogEntity.java` + `WhatsAppMessageLogRepository.java` — outbound fields, @Version, forOutbound factory, markSent/markFailed/bumpAttempt, findByNotificationJobId, direction-scoped findLogs with NULLS LAST ordering
- `notification/application/WhatsAppMessageLogService.java` (new) — upsertForDispatch: template/target derivation from alertId-else-idempotency-prefix, maskRecipient (*** collapse for short phones), SHA-256 text reference
- `notification/application/NotificationDispatchService.java` — best-effort recordMessageLog hook in all 3 outcome branches; rate-limited return writes nothing
- `notification/api/WhatsAppMessageLogController.java` + `WhatsAppMessageLogDtos.java` + `WhatsAppMessageLogExceptionHandler.java` (new) — GET /api/v1/whatsapp-message-logs, masked projections, house envelope
- `notification/application/WhatsAppMessageLogQueryService.java` (new) — SUPER_ADMIN gate + paged reads
- `authz.rego` + `authz_test.rego` + `.env.example` + `PmAuthzEnforcementParityTest` — admin_only_paths + env + 5 policy cases + parity guard
- Tests: WhatsAppMessageLogServiceTest (11), WhatsAppMessageLogControllerTest (5), WhatsAppMessageLogIntegrationTest (7, Testcontainers), NotificationDispatchServiceTest extended (10), V1BaseSchemaMigrationTest (37, V20 assertions)

**Review findings breakdown:** 11 patches applied (2 high, 4 medium, 5 low); 1 bad_spec resolved as spec-doc amendments (internal contradictions; code already followed the only defensible reading — no loopback); 4 deferred (Postgres aborted-tx nuance on the best-effort catch, pre-existing markCircuitOpen attempt-number reuse, multi-instance row claim, log-line secret-absence test); 5 rejected (stale uncommitted-context claim, free-text filter length caps, reused-container row accumulation = house pattern, sort-echo string, spec bookkeeping emptiness = workflow-owned).

**Follow-up review recommendation:** true — patched counts: high 2, medium 4, low 5; score = 3×4 + 5 = 17 ≥ 5.

**Verification performed:** independent re-run `mvn test -Dtest=*WhatsApp*,NotificationDispatchServiceTest,NotificationWorkerTest,V1BaseSchemaMigrationTest,PmAuthzEnforcementParityTest` → **74/74 green** (Service 11, Controller 5, Integration 7, Dispatch 10, Worker 3, V1BaseSchema 37, parity 1); `IntegrationAndAuthTailEntityConventionIntegrationTest` 5/5 (inbound mapping preserved); `run-opa-test.ps1` → **550/550 PASS**. Matrix test audit: all 8 I/O rows covered by passing tests (duplicate-race row covered by the pre-check update path + unique-anchor DB test; true concurrent-insert posture deferred).

**Residual risks:** DB-level log-write violations still poison the dispatch tx despite the catch (deferred, savepoint fix conflicts with the spec's same-tx Always); second circuit-open dispatch hits the pre-existing attempt-number unique collision (deferred, Epic 5 semantics); openapi.json not regenerated (no frontend consumption this story); Testcontainers reused-container port flake on wide combined selectors (documented since 21-1).
