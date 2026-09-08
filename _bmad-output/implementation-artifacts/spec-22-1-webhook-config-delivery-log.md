---
title: 'Story 22-1: Webhook Config & Delivery Log (redesigned 2026-08-31)'
type: 'feature'
created: '2026-09-06'
status: 'done'
baseline_revision: '5d20e32'
review_loop_iteration: 0
followup_review_recommended: true
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-22-context.md'
  - '_bmad-output/implementation-artifacts/spec-22-2-auth-login-audit-phone-verification.md'
warnings: []
deferred:
  - summary: >-
      X-Syncro-Signature has no timestamp/nonce, so a captured delivery is replayable indefinitely;
      Stripe/GitHub-style signed-timestamp scheme not adopted this story.
    evidence: |-
      Intent contract fixes the header as hex(HmacSHA256(secret, body)) over a static body; replay
      resistance requires a contract change with subscriber coordination — a later story decision.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/integration/application/WebhookDispatchService.java
    severity: medium
  - summary: >-
      Single shared "webhook" circuit breaker across all configs: one dead endpoint starves healthy
      subscribers via markCircuitOpen retries.
    evidence: |-
      WebhookHttpClient registers one named breaker (WahaClient precedent). Per-config breaker names
      isolate blast radius; spec Design Notes only deferred per-config concurrency, not CB isolation.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/integration/infrastructure/WebhookHttpClient.java
    severity: medium
  - summary: >-
      Worker sweep has no row claim (FOR UPDATE SKIP LOCKED / lease), so two backend instances can
      dispatch the same delivery concurrently; @Version collision lands in the worker's generic catch.
    evidence: |-
      NotificationWorker precedent is also single-instance; horizontal-scale posture is absent from the
      intent contract entirely — needs a project-wide decision covering both workers.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/integration/application/WebhookDeliveryWorker.java
    severity: medium
  - summary: >-
      Delivery payload carries no event timestamp or X-Syncro-Delivery-Id header, so subscribers cannot
      dedupe or order retried deliveries.
    evidence: |-
      Enqueued body has eventType, entity id, status, traceId only; retry/duplicate discrimination is a
      subscriber-side contract addition beyond this story's scope.
    location: >-
      syncro/apps/backend/src/main/java/com/syncro/integration/application/WebhookDispatchService.java
    severity: low
  - summary: >-
      "Secret never written to logs" is enforced by code posture only; no test asserts log-output absence.
    evidence: |-
      Audit and response-body halves are tested (SVC-008/009, INT-010, DSP-007); a logback appender
      assertion would pin the log half. House precedent has no such tests either.
    location: >-
      syncro/apps/backend/src/test/java/com/syncro/integration/
    severity: low
---

<intent-contract>

## Intent

**Problem:** `webhook_configs` and `webhook_delivery_logs` exist in V1 (blueprint I4) with mapped entities, but `com.syncro.integration` has no api/application layer — external systems cannot subscribe to Syncro events and no delivery is observable, so the integration evidence layer is missing (NFR-P2-1).

**Approach:** Mirror the Epic 5 notification outbox exactly: SUPER_ADMIN config CRUD (name, OUTBOUND direction, event types, endpoint, HMAC secret, active flag); a `@TransactionalEventListener(AFTER_COMMIT)` listener matches `WorkOrderLifecycleEvent`/`AlertOpenedEvent` against active OUTBOUND configs and enqueues one PENDING `webhook_delivery_logs` row per match (idempotency key, never inline); a `@Scheduled` worker sweeps due rows and dispatches via a RestClient + circuit-breaker client (WahaClient pattern — retry is DB-driven via `next_retry_at` backoff, not library `@Retry`), signing payloads with HMAC-SHA256; delivery reads are SUPER_ADMIN-only. V19 adds `trace_id`, `max_attempts`, `idempotency_key`, `version` to delivery logs + `version` to configs + `WEBHOOK_CONFIG` audit type.

## Boundaries & Constraints

**Always:**
- Forward-only change; never edit V1..V18; V19 is additive (columns + audit CHECK drop/recreate preserving every existing value incl. 21-2/21-3's five compliance types, V13/V14/V17 pattern)
- Dispatch NEVER runs inline with the request/ingest path: enqueue rides the event listener's REQUIRES_NEW transaction after commit; the worker owns HTTP calls; no MQTT/WAHA side effect before DB commit
- Delivery status machine uses all five V1 CHECK values: 2xx → DELIVERED; 4xx → FAILED (terminal, non-retryable); 5xx/timeout/circuit-open → RETRYING with exponential backoff `min(2^attemptCount, 60)` minutes (NotificationDispatchService precedent); attemptCount >= maxAttempts → DLQ (terminal, next_retry_at null); circuit-open skips do NOT increment attemptCount (NotificationJobEntity.markCircuitOpen precedent)
- `hmac_secret` stored in the V1 plaintext column; masked in every API projection (return last 4 only) and never written to logs, audit values, or delivery `response_body`; payload signed per-config with `X-Syncro-Signature: hex(HmacSHA256(secret, body))` (JwtTokenService Mac precedent)
- Config mutations: SUPER_ADMIN-only service gate AND rego (`/api/v1/webhooks/**` added to `admin_only_paths` + `SYNCRO_AUTHZ_ENFORCED_PATHS`) AND immutable audit row (type WEBHOOK_CONFIG, previous/new values, secret masked in both)
- Idempotency: `idempotency_key` unique = `configId + eventType + eventKey` (woId/alertId); duplicate enqueue caught via DataIntegrityViolationException → ignored silently (saveIgnoreDuplicate precedent)
- `trace_id` VARCHAR(64) copied from the source event's traceId onto every delivery row; `@Version` on both entities; `Instant` UTC via injected Clock; response body truncated to 512 (WahaClient MAX_DETAIL_LENGTH precedent)
- DTO records with Bean Validation; OUTBOUND configs require non-blank endpoint_url + hmac_secret + non-empty event_types (service-level, V1 columns are nullable); error envelope per-module advice

**Block If:**
- A change to V1..V18 or an existing API contract seems required → HALT (all 22-1 work is additive)

**Never:**
- No frontend UI (evidence views are a later story)
- No INBOUND webhook handling (direction enum exists; only OUTBOUND is dispatched)
- No DELETE config endpoint (21-1 no-delete precedent; deactivate via is_active=false); no Resilience4j @Retry annotation (DB-driven sweep only); no new dependencies, no Lombok/MapStruct; no encryption-at-rest for the secret (V1 column posture)

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Create config | SUPER_ADMIN, valid OUTBOUND | 201 + masked-secret view + CREATE audit | 400 blank endpoint/secret/eventTypes; 409 duplicate name |
| Non-admin mutates | MANAGER_MAINTENANCE | 403 (service + rego) | — |
| WO closed | active config subscribes CLOSED | 1 PENDING row (traceId from event) | duplicate event → ignored |
| No matching config | event type unsubscribed | no row | — |
| Dispatch 2xx | worker sweep | DELIVERED + response_code + latency | — |
| Dispatch 4xx | endpoint rejects | FAILED terminal, no retry | — |
| Dispatch 5xx | endpoint down | RETRYING + next_retry_at backoff, attempt++ | — |
| Exhausted | attempt >= max_attempts | DLQ, next_retry_at null | — |
| Circuit open | CB tripped | row untouched (no attempt++), retried later | — |
| List deliveries | SUPER_ADMIN ?status=DLQ | page newest-first, no secret in body | 403 for other roles |
| Unauthenticated | no JWT | 401 AUTHENTICATION_REQUIRED | SecurityConfig |

</intent-contract>

## Code Map

- `V1__orm_foundation_schema.sql:1798-1832` -- webhook tables DDL + retry-sweep index (read-only reference)
- `integration/infrastructure/db/WebhookConfigEntity.java:36-44` -- eventTypes JSONB + hmacSecret -- add @Version + mutators
- `integration/infrastructure/db/WebhookDeliveryLogEntity.java:133-142` -- recordAttempt -- extend for traceId/maxAttempts/version
- `notification/infrastructure/NotificationJobEntity.java:107-166` -- markSent/markExhausted/markAttemptFailed/markCircuitOpen state mutators to mirror
- `notification/application/WorkOrderNotificationRoutingService.java:63-147` -- AFTER_COMMIT enqueue + idempotency + saveIgnoreDuplicate pattern
- `notification/application/NotificationWorker.java:36-54` -- @Scheduled sweep worker pattern
- `notification/application/NotificationDispatchService.java:112-179` -- backoff + TransactionTemplate + truncation pattern
- `notification/infrastructure/WahaClient.java:53-162` -- RestClient + CB + Result record + 5xx-throws/4xx-returns semantics
- `maintenance/application/WorkOrderLifecycleEvent.java:17-21` + `notification/domain/AlertOpenedEvent.java` -- event sources
- `config/WahaResilienceProperties.java:19-86` -- typed properties template for syncro.webhook.*
- `audit/domain/AuditEntityType.java` -- add WEBHOOK_CONFIG (V19 CHECK)
- `authz/policy/authz.rego:559-563` -- admin_only_paths set; `.env.example` enforced-paths tail
- Tests: `NotificationWorkerTest.java:26`, `WahaClientCircuitBreakerTest.java:30-52` (real HttpServer), `NotificationJobCancelIntegrationTest.java:34-52`, `NotificationWorkerStatusControllerTest.java:32-63`

## Tasks & Acceptance

**Execution:**
- `db/migration/V19__webhook_delivery_evidence.sql` -- create -- add `trace_id VARCHAR(64)`, `max_attempts INT NOT NULL DEFAULT 3`, `idempotency_key VARCHAR(255)` + UNIQUE, `version BIGINT DEFAULT 0` to webhook_delivery_logs; `version` to webhook_configs; drop/recreate ck_audit_log_entity_type with WEBHOOK_CONFIG (preserve all prior); update V1BaseSchemaMigrationTest
- `integration/infrastructure/db/WebhookConfigEntity.java` + `WebhookDeliveryLogEntity.java` + repositories -- extend -- @Version, mutators (markDelivered/markFailed/markRetrying/markDlq/markCircuitOpen), findDueDeliveries sweep (status in PENDING/RETRYING and next_retry_at null-or-due, limit 10, idx already exists), findByDirectionAndActiveTrue
- `integration/application/WebhookConfigService.java` -- create -- SUPER_ADMIN CRUD (create/list/get/update incl. secret rotation + toggle active), masked views, outbound validation, audit with masked prev/new
- `integration/application/WebhookDispatchListener.java` -- create -- @TransactionalEventListener(AFTER_COMMIT, REQUIRES_NEW) on WorkOrderLifecycleEvent + AlertOpenedEvent; match event_types; enqueue PENDING rows with traceId + idempotency key; saveIgnoreDuplicate
- `integration/infrastructure/WebhookHttpClient.java` -- create -- RestClient + timeouts from WebhookProperties + CircuitBreaker from registry; post(url, jsonBody, signatureHeader) → Result(success, status, detail); 5xx/timeout throw (trips CB), 4xx returns failed
- `integration/application/WebhookDeliveryWorker.java` + `WebhookDispatchService.java` -- create -- @Scheduled poll (own interval property), per-row dispatch, HMAC sign, recordAttempt with latency/backoff/DLQ, circuit-open no-increment
- `integration/api/WebhookController.java` + `IntegrationDtos.java` + `IntegrationExceptionHandler.java` -- create -- `/api/v1/webhooks` CRUD + `/api/v1/webhooks/{id}/deliveries` + `/api/v1/webhook-deliveries?status=`; masked secret; house envelope
- `config/WebhookProperties.java` + `application.yml` + `.env.example` -- create/extend -- syncro.webhook.{worker.poll-interval-ms, max-attempts, backoff-base-seconds, resilience.timeout}; enforced-paths += webhooks
- `authz.rego` + `authz_test.rego` + `PmAuthzEnforcementParityTest` -- extend -- `/api/v1/webhooks/**` in admin_only_paths + env; rego tests (SUPER_ADMIN allowed, MANAGER denied)
- Tests: `WebhookConfigServiceTest` (Mockito: validation, masking, audit, duplicate name), `WebhookDispatchListenerTest` (match/no-match/idempotent-dup), `WebhookDispatchServiceTest` (fixed Clock: 2xx/4xx/5xx/exhaust/CB-open backoff matrix), `WebhookHttpClientCircuitBreakerTest` (real HttpServer, mirror WahaClientCircuitBreakerTest), `WebhookControllerTest` (@WebMvcTest envelope incl. 403/401 code strings), `WebhookDeliveryIntegrationTest` (Testcontainers: V19 applies, enqueue-on-event after commit, sweep dispatch vs stub server, DLQ, idempotency unique)

**Acceptance Criteria:**
- Given an active OUTBOUND config subscribed to an event type, when that event commits, then exactly one PENDING delivery row exists with the event's traceId and idempotency key; duplicate events add nothing (AC1)
- Given the worker dispatches, when the endpoint returns 2xx/4xx/5xx, then status becomes DELIVERED/FAILED/RETRYING respectively with response code, latency, attempt count, and backoff next_retry_at; exhaustion lands in DLQ; circuit-open skips don't consume attempts (AC2)
- Given any config mutation or delivery read, when attempted, then SUPER_ADMIN-only (rego+service parity), mutations audit with masked secret, and no response/log/audit ever carries the secret or a full payload signature (AC3)

## Spec Change Log

- 2026-09-08 (review pass 1): Design Notes secret-rotation bullet corrected — signing happens at dispatch time with the CURRENT secret, so pre-rotation queued payloads are signed with the new secret (subscriber not yet rotated → 4xx terminal FAILED). Triggered by blind-hunter finding; implementation was correct, the note described the opposite.

## Review Triage Log

### 2026-09-08 — Review pass
- intent_gap: 0
- bad_spec: 1: (high 0, medium 1, low 0)
- patch: 17: (high 1, medium 8, low 8)
- defer: 5: (high 0, medium 3, low 2)
- reject: 10
- addressed_findings:
  - `[medium]` `[bad_spec]` Design Notes secret-rotation note described the opposite of implemented behavior → corrected in Spec Change Log above (no code change needed).
  - `[high]` `[patch]` Listener duplicate-enqueue poisoned the REQUIRES_NEW fan-out transaction (sibling configs lost rows; UnexpectedRollbackException could reach the business caller) → existsByIdempotencyKey pre-check + per-row REQUIRES_NEW TransactionTemplate; selective DIV swallow (constraint-name/SQLState 23505 only); INT-004 rewritten to assert the real double-publish surface without a tolerated catch.
  - `[medium]` `[patch]` Missing house 409 VERSION_CONFLICT mapping for @Version conflicts → IntegrationExceptionHandler handler + WebhookControllerTest API-015.
  - `[medium]` `[patch]` eventTypes element length unbounded vs VARCHAR(100) → @Size(max=100) on create+update.
  - `[medium]` `[patch]` saveIgnoreDuplicate swallowed every integrity violation → selective swallow, others logged+rethrown (LST-005/006/007).
  - `[medium]` `[patch]` Deactivated config kept receiving POSTs from queued rows → dispatch DLQs with reason "config deactivated" (DSP-011).
  - `[medium]` `[patch]` Delivery-read queries never executed against a real DB → INT-012 (order + status filter) and INT-013 (size clamp/default/negative-page) through the real query service.
  - `[medium]` `[patch]` AlertOpenedEvent registration never exercised through the event bus → INT-011 publishes via ApplicationEventPublisher in a committed transaction.
  - `[medium]` `[patch]` Worker per-row failure isolation unverified → WebhookDeliveryWorkerTest WKR-001/002.
  - `[medium]` `[patch]` .env.example omitted SYNCRO_WEBHOOK_* vars → documented all knobs.
  - `[low]` `[patch]` Malformed JSON escaped house envelope → HttpMessageNotReadableException → 400 VALIDATION_ERROR.
  - `[low]` `[patch]` No endpoint URL validation → absolute http(s) URI with host required.
  - `[low]` `[patch]` 3xx/other statuses retried → retryable now status==0 || >=500; others terminal FAILED (DSP-012).
  - `[low]` `[patch]` Dead code (throwValidation, setActive, redundant findAll(Pageable)) → removed.
  - `[low]` `[patch]` Misleading latency comment + wrong V19 provenance credit → fixed.
  - `[low]` `[patch]` Pagination echoed raw negative page → normalized echo.
  - `[low]` `[patch]` maskSecret leaked short secrets → @Size(min=16) on hmacSecret (create+update).
  - `[low]` `[patch]` GET /webhooks/{id}/deliveries 200+empty for unknown config → 404.

## Design Notes

- FAILED (4xx) is terminal by design: a client error won't heal on retry; the config is fixed and a new event re-delivers. RETRYING covers 5xx/timeout/CB-open only. DLQ = attempts exhausted.
- FK delivery_logs→configs is ON DELETE CASCADE (V1): acceptable because configs are never deleted (no DELETE endpoint); deactivation preserves history.
- `ponytail: worker batch limit 10 + fixed poll mirrors NotificationWorker; per-config concurrency or priority queues only if a real subscriber needs ordering guarantees`.
- Secret rotation = PATCH with new secret; signing happens at dispatch time with the CURRENT secret, so payloads queued before rotation are signed with the new secret and a subscriber that has not rotated yet rejects them (4xx → terminal FAILED). Documented limitation, no dual-key window this story.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=*Webhook*"` -- expected: green incl. Testcontainers V19 (the spec's original `*Webhook*,*Integration*` combined selector pulls ~40 unrelated suites into one fork and trips the documented Testcontainers port-reuse flake carried since 21-1/22-2; story suites verified standalone)
- `mvn -f syncro/apps/backend/pom.xml test "-Dtest=PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest"` -- expected: parity + schema green
- `syncro/authz/run-opa-test.ps1` -- expected: all policy tests PASS

## Auto Run Result

Status: done

**Summary:** Story 22-1 implemented end-to-end: V19 additive migration (trace_id, max_attempts, idempotency_key UNIQUE, version on delivery logs; version on configs; WEBHOOK_CONFIG audit CHECK), SUPER_ADMIN webhook config CRUD with masked-secret views + immutable audit, AFTER_COMMIT idempotent enqueue listener (WorkOrderLifecycleEvent + AlertOpenedEvent), @Scheduled delivery worker with HMAC-SHA256-signed dispatch through a circuit-breaker RestClient client, full five-state delivery machine (PENDING/DELIVERED/FAILED/RETRYING/DLQ + circuit-open no-increment), delivery-read endpoints, rego admin_only_paths + enforced-paths wiring, and 79 tests across 7 suites.

**Files changed (30):**
- `syncro/apps/backend/src/main/resources/db/migration/V19__webhook_delivery_evidence.sql` — new additive migration
- `integration/infrastructure/db/` — WebhookConfigEntity/WebhookDeliveryLogEntity (+@Version, state mutators), repositories (+existsByIdempotencyKey, findDueDeliveries, paged reads)
- `integration/application/` — WebhookConfigService, WebhookDispatchListener, WebhookDispatchService, WebhookDeliveryWorker, WebhookDeliveryQueryService (new)
- `integration/infrastructure/WebhookHttpClient.java` — new RestClient + CB client (WahaClient pattern)
- `integration/api/` — WebhookController, IntegrationDtos, IntegrationExceptionHandler (new)
- `config/WebhookProperties.java` + `application.yml` + `syncro/.env.example` — syncro.webhook.* typed config + env docs
- `audit/domain/AuditEntityType.java` — +WEBHOOK_CONFIG
- `authz/policy/authz.rego` + `authz_test.rego` — webhook paths admin-only + 11 policy cases
- Tests: WebhookConfigServiceTest, WebhookDispatchListenerTest, WebhookDispatchServiceTest, WebhookHttpClientCircuitBreakerTest, WebhookControllerTest, WebhookDeliveryWorkerTest, WebhookDeliveryIntegrationTest; PmAuthzEnforcementParityTest extended

**Review findings breakdown:** 17 patches applied (1 high, 8 medium, 8 low), 1 bad_spec doc correction (Design Notes rotation note), 5 items deferred (signature replay, shared CB blast radius, multi-instance row claim, payload dedupe fields, log-absence test), 10 rejected as noise/out-of-intent.

**Follow-up review recommendation:** true — patched counts: high 1, medium 8, low 8; score = 3×8 + 8 = 32 ≥ 5.

**Verification performed:** `mvn test -Dtest=*Webhook*` → 79/79 green (Controller 15, ConfigService 15, DeliveryIntegration 13, DeliveryWorker 2, DispatchListener 13, DispatchService 12, HttpClientCB 9); `PmAuthzEnforcementParityTest,V1BaseSchemaMigrationTest` → 37/37 green; `run-opa-test.ps1` → 545/545 PASS. Matrix test audit: all 11 I/O rows covered by passing tests.

**Residual risks:** Testcontainers port-reuse flake on wide combined selectors (documented, carried since 21-1/22-2); `AuditLogAtddGapIntegrationScaffoldTest.plantDeleteNullsAuditPlantId` fails identically at baseline 5d20e32 (pre-existing RED ATDD scaffold, unrelated); openapi.json not regenerated for new endpoints (frontend consumption story posture, same as 22-2); OPA tests not wired into `mvn test`/CI (pre-existing deferred-work gap).
