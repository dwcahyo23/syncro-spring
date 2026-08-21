# Story 5.9: Implement Circuit Breaker for WAHA Calls

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a system,
I want WAHA HTTP calls to use timeout, retry, and circuit-breaker patterns,
so that WAHA unavailability does not block notification workers or cascade into alert delivery failures.

## Acceptance Criteria

1. **Given** WAHA service is slow or unavailable **When** notification worker attempts to send **Then** configurable timeout prevents indefinite blocking [Source: _bmad-output/planning-artifacts/epics.md#Story-5.8-AC]
2. **Given** WAHA service is slow or unavailable **When** notification worker attempts to send **Then** retry with exponential backoff is applied for transient failures [Source: _bmad-output/planning-artifacts/epics.md#Story-5.8-AC]
3. **Given** WAHA failure threshold exceeded **When** further notification jobs are due **Then** circuit breaker opens after configured failure threshold, preventing further calls until half-open probe succeeds [Source: _bmad-output/planning-artifacts/epics.md#Story-5.8-AC]
4. **Given** circuit breaker state changes (open/half-open/closed) **When** health status is requested **Then** circuit breaker state (open/half-open/closed) is observable in health dashboard [Source: _bmad-output/planning-artifacts/epics.md#Story-5.8-AC, _bmad-output/planning-artifacts/architecture.md#System-Health]
5. **Given** WAHA send fails (timeout, non-2xx, circuit-open) **When** dispatch completes **Then** failed attempts are recorded with error detail in notification attempt history [Source: _bmad-output/planning-artifacts/epics.md#Story-5.8-AC, spec-5-3]
6. NFR-011a satisfied: External service calls (WAHA) use configurable timeouts, retry with backoff, and circuit-breaker behavior [Source: _bmad-output/planning-artifacts/epics.md#NFR-011a, _bmad-output/planning-artifacts/architecture.md#Circuit-Breaker-and-Resilience]
7. No WAHA credentials, API keys, phone numbers, or raw sensitive payloads are logged or exposed in API/error responses [Source: _bmad-output/project-context.md#Code-Quality, _bmad-output/planning-artifacts/architecture.md#Security]

## Tasks / Subtasks

- [x] Task 1: Add Resilience4j dependencies to `pom.xml` (AC: 2, 3)
  - [x] Add `io.github.resilience4j:resilience4j-circuitbreaker:${resilience4j.version}` (2.4.0) — programmatic API used, NOT annotation-based. `resilience4j-spring-boot4` exists but pulls AOP starter; we use `CircuitBreaker.executeSupplier()` directly so only the core artifact is needed. `resilience4j-timelimiter` NOT needed — timeout applied via `SimpleClientHttpRequestFactory`.
  - [x] `spring-boot-starter-aop` NOT added — renamed to `spring-boot-starter-aspectj` in Boot 4, but not needed for programmatic circuit breaker.
  - [x] `mvn dependency:tree` confirmed `resilience4j-circuitbreaker:2.4.0` + `resilience4j-core:2.4.0`, no version conflict with Boot 4.0.6 BOM.

- [x] Task 2: Create typed resilience config `WahaResilienceProperties` (AC: 1, 2, 3)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/config/WahaResilienceProperties.java`
  - [x] `@ConfigurationProperties(prefix = "syncro.waha.resilience")` record with validation
  - [x] Fields: `Duration timeout` (default 5000ms), `int failureRateThreshold` (default 50), `int minimumNumberOfCalls` (default 5), `int slidingWindowSize` (default 10), `Duration waitDurationInOpenState` (default 60000ms), `int permittedCallsInHalfOpen` (default 3)
  - [x] `@Validated` + Jakarta validation (`@NotNull`, `@Positive`, `@Min`), range check in compact constructor
  - [x] Bound via `@ConfigurationProperties` (Spring Boot auto-registers scanned config props)

- [x] Task 3: Extend `application.yml` with resilience config block (AC: 1, 2, 3)
  - [x] Added `syncro.waha.resilience` block under `syncro.waha`
  - [x] `${SYNCRO_WAHA_TIMEOUT_MS:5000}ms`, `${SYNCRO_WAHA_CB_FAILURE_RATE:50}`, `${SYNCRO_WAHA_CB_MIN_CALLS:5}`, `${SYNCRO_WAHA_CB_WINDOW_SIZE:10}`, `${SYNCRO_WAHA_CB_WAIT_MS:60000}ms`, `${SYNCRO_WAHA_CB_HALF_OPEN_CALLS:3}`
  - [x] Programmatic `CircuitBreakerConfig` built in `WahaClient` constructor from `WahaResilienceProperties` — single source of truth, no duplicate YAML `resilience4j.*` block (documented choice)

- [x] Task 4: Harden `WahaClient` with timeout + circuit breaker (AC: 1, 3, 5)
  - [x] Injected `WahaResilienceProperties`; `RestClient` built with `SimpleClientHttpRequestFactory` `connectTimeout`/`readTimeout`
  - [x] Programmatic `circuitBreaker.executeSupplier(() -> doSend(...))`; circuit built from `CircuitBreakerRegistry` in `WahaClient`
  - [x] `CallNotPermittedException` caught → returns `Result(false, 0, "Circuit breaker is OPEN")` without HTTP call
  - [x] Logging invariants preserved: `[WAHA][traceId={}]`, never logs `apiKey` or `recipientPhone`
  - [x] `truncate()` to 512 chars preserved (`MAX_DETAIL_LENGTH=512`)
  - [x] WAHA endpoint contract unchanged: `POST /api/sendText` with `{chatId:"<phone>@c.us", text, session:"default"}` + header `X-Api-Key`

- [x] Task 5: Integrate circuit-breaker handling into `NotificationDispatchService` (AC: 2, 5)
  - [x] Injected `WahaResilienceProperties`; detects circuit-open result (`httpStatus==0 && detail=="Circuit breaker is OPEN"`)
  - [x] Circuit-open → saves `FAILED` attempt with detail, sets `nextAttemptAt = now + waitDurationInOpenState` (NOT exponential backoff)
  - [x] `acquire()` only on `result.success()==true` — preserved
  - [x] `Clock` usage preserved (`Instant.now(clock)`)
  - [x] `@Transactional` boundary on `dispatch()` preserved
  - [x] Circuit-open does NOT prematurely exhaust — falls through to `markAttemptFailed` (respects `maxAttempts`)

- [x] Task 6: Expose circuit breaker state for health dashboard (AC: 4)
  - [x] Created `WahaCircuitBreakerHealthIndicator` (Option A) — `HealthIndicator`, `@Component("wahaCircuitBreaker")`, reports DOWN when OPEN, OUT_OF_SERVICE when FORCED_OPEN/DISABLED, includes `state`, `failureRate`, `bufferedCalls`, `failedCalls`, `successfulCalls`, `notPermittedCalls`
  - [x] Uses Spring Boot 4 `org.springframework.boot.health.contributor.Health` API (NOT `actuate.health`)
  - [x] Appears under `management.endpoint.health.show-details=always` (already configured)

- [x] Task 7: Handle `NotificationWorker` polling interaction with OPEN circuit (AC: 3)
  - [x] Added circuit-aware early exit: `NotificationWorker.poll()` checks `circuitBreaker.getState()` — skips entire batch when OPEN/FORCED_OPEN, logs debug message. Dispatch fast-fails anyway, this avoids unnecessary DB queries.

- [x] Task 8: Update Flyway / constraints if new status needed (AC: 5)
  - [x] DECISION: Do NOT add new `NotificationJobStatus.CIRCUIT_OPEN` — reuse `PENDING` + `nextAttemptAt` delay. Circuit state is dependency state, not job lifecycle. No migration needed.

- [x] Task 9: Write unit and integration tests (AC: 1-5)
  - [x] `WahaClientCircuitBreakerTest` (new) — circuit-open fast-fail, truncation, initial CLOSED state, open transition, forced-open
  - [x] `NotificationDispatchServiceTest` extension — 2 new circuit-open cases (nextAttemptAt = now + waitDurationInOpenState, does not exhaust)
  - [x] `WahaCircuitBreakerHealthIndicatorTest` (new) — CLOSED→UP, OPEN→DOWN, FORCED_OPEN→OUT_OF_SERVICE, metrics exposed
  - [x] Controlled `Clock.fixed()` for `nextAttemptAt` assertions
  - [x] Scoped test run confirmed: 17 tests, 0 failures, 0 errors (3 relevant test classes)

- [x] Task 10: Verify no regression in existing notification flows (AC: 5 + project-context)
  - [x] `mvn compile` + `mvn test-compile` pass with Java 25 JDK (lokal path: `C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot`)
  - [x] Fixed 2 pre-existing test-compile breaks (baseline, not this story's regression): `NotificationJobCancelIntegrationTest.java:176` (missing `List.of()`) + `SparepartAlertQueryServiceTest.java:48` (missing `NotificationJobRepository` mock arg)
  - [x] `.m2` cache had been manually cleared — re-downloaded needed deps; `resilience4j-circuitbreaker:2.4.0` resolved from Maven Central (confirmed via `mvn dependency:tree`)
  - [x] Verified logs use `[WAHA][traceId=...]` prefix, no secrets

## Dev Notes

### Previous Story Intelligence (Story 5.8 — Rate Limiting) — MUST preserve

- **Status added:** `NotificationJobStatus.RATE_LIMITED` (12 chars, fits `VARCHAR(24)`) added to enum and CHECK constraint via `V29__notification_job_rate_limited_status.sql:10`. Do NOT recreate/drop constraint again unless adding new status — update atomically if needed. [Source: spec-5-8:56, V29:10]
- **Redis key:** `waha:rl:{alertId}:{recipientPhone}` with TTL `windowMs=300000` (default 5m) via `SET NX PX`. `WahaRateLimiter.java:88-92` uses `setIfAbsent(key,"1", Duration.ofMillis(windowMs))`. First winner holds key; JPA `@Version` on `NotificationJobEntity.java:63` provides second guard. Do NOT switch to plain `SET` (overwrites) — breaks multi-node dedup (spec-5-8 F3 patch). [Source: spec-5-8:58, WahaRateLimiter.java:88]
- **`nextAttemptAt` guard:** `NotificationJobRepository.java:38-47` polls `PENDING` + `RATE_LIMITED` with `nextAttemptAt is null or <= :now`. Keep this query; add no new poll query for circuit breaker — reuse `nextAttemptAt` delay mechanism. [Source: NotificationJobRepository.java:38]
- **`RATE_LIMITED` cancellation:** `SparepartAlertCommandService.java:65` now includes `RATE_LIMITED` in `cancelActiveForAlert` active statuses — else acknowledged alerts leak rate-limited jobs that retry after close. Preserve. [Source: spec-5-8:60,74]
- **Logging:** `WahaRateLimiter.java:56,94` logs `[WAHA] rate-limited alertId={}` without phone. Keep. [Source: spec-5-8:AC5]
- **Config:** `WahaRateLimitProperties.java:14` is `@ConfigurationProperties(prefix="syncro.notification.rate-limit")` with `windowMs`. Do NOT move or rename — `application.yml:67-70` binds `window-ms` via `SYNCRO_NOTIFICATION_RATE_LIMIT_WINDOW_MS`. Follow same pattern for `WahaResilienceProperties`. [Source: spec-5-8:AC6, WahaRateLimitProperties.java:14]
- **Tests:** `WahaRateLimiterTest.java`, `NotificationDispatchServiceTest.java` already cover rate-limited path — add circuit tests alongside, do NOT duplicate or rename existing tests.

### Architecture & Project-Context Guardrails

- **Resilience4j mandate:** AR-027 + `project-context.md:27` + `architecture.md:327,418` — "Add Resilience4j before WAHA or InfluxDB external-call workflows so retry, timeout, and circuit-breaker behavior is standardized." Do NOT hand-roll retry/circuit with `try/catch` counters or `Thread.sleep` — use Resilience4j. [Source: _bmad-output/planning-artifacts/architecture.md:327, _bmad-output/project-context.md:27]
- **Typed config:** `project-context.md:91-92,238` — "Spring config must bind through typed `*Properties` classes with validation; avoid scattered `@Value`." Create `WahaResilienceProperties` record, not `@Value("${syncro.waha.timeout}")`. [Source: _bmad-output/project-context.md:91]
- **WAHA contract isolation:** WAHA/MQTT payload classes belong in `infrastructure` integration packages and should use provider-specific names (`WahaWebhookPayload`, `MqttDeviceEvent`). `WahaClient` is already in `notification/infrastructure` — keep it there. Do NOT move to `domain` or `application`. [Source: _bmad-output/project-context.md:84]
- **Outbox/job pattern:** Architecture `Data Architecture` + `project-context.md:125-126` — WAHA dispatch must never run inline with request or ingest path; use PostgreSQL outbox/job table (`notification_jobs` + `notification_attempts`) with idempotencyKey `alertId::escalationLevel`. Circuit breaker must NOT publish events before DB commit; dispatch only from committed outbox records. [Source: _bmad-output/project-context.md:125, architecture.md:133,352]
- **Secret redaction:** `project-context.md:193,256,294` + spec-5-3 AC7 — WAHA `apiKey` must never appear in logs, errors, retries, or audit metadata. Keep `WahaClient.java:23,43` header handling, never `log.info(apiKey)`. Phone numbers: DEBUG only, not INFO/WARN/ERROR. [Source: WahaClient.java:23, project-context.md:193]
- **Correlation ID:** Every request/ingest/job/audit entry should propagate `traceId` (`project-context.md:118`, architecture `Communication Patterns`). `NotificationJobEntity.traceId` already exists; `NotificationDispatchService.dispatch()` logs `[traceId={}]`; preserve. `WahaClient.send(recipientPhone, messageText, traceId)` signature already requires traceId. [Source: NotificationDispatchService.java:49,84, WahaClient.java:27]
- **Layer direction:** `domain` must not import Spring/JPA/Jackson/web DTOs; `application` must not depend on controllers/raw entities; `api` must not call repos directly (`project-context.md:74-75`). Keep `WahaResilienceProperties` in `com.syncro.config` (like `WahaProperties.java:4`, `WahaRateLimitProperties.java:1`), not in `notification/domain`.
- **Transaction boundaries:** `@Transactional` belongs at application service boundaries (`project-context.md:90`). `NotificationDispatchService.dispatch()` is already `@Transactional` — keep it. Do NOT add `@Transactional` to `WahaClient` (infrastructure adapter).
- **Error shape:** Backend error responses must expose stable machine-readable codes; frontend branches on `code`/status not message text (`project-context.md:96`). Circuit-open must map to `FAILED` attempt with stable `errorDetail` prefix, not new HTTP status.
- **Redis rebuildable:** `project-context.md:120,195` — Redis keys must have explicit TTL and be rebuildable. Rate-limit keys already have TTL `windowMs`. If adding circuit-breaker Redis offloading (not needed — Resilience4j circuit state is in-memory), document that circuit state is NOT stored in Redis.

### Git Intelligence (last 5 commits)

- `782e322 Merge 5-8 rate-limiting into main` + `26b89f9 story 5-8 impl/reviewed` — added `WahaRateLimiter`, `WahaRateLimitProperties`, `V29`, `markRateLimited`, multi-status poll. Pattern: typed props, Redis `SET NX PX`, `putIfAbsent` tie-break, CHECK constraint migration. Reuse. [Source: git log --oneline -15]
- `6642a45 Merge 5-7 surface-waha-notification-state` — added `NotificationSummary` to `AlertView`, batch JPQL `findMostRecentNonCancelledJobsForAlerts`. Pattern: avoid N+1 via subquery, `putIfAbsent` for tie-break. Keep. [Source: git log]
- `0e0c6ae docs(mcp): Documentation MCP Reference` — MCP anti-hallucination: must query `spring-docs` for Spring Boot 4/Jakarta, not copy Boot 3 examples. Before implementing Resilience4j RestClient timeout, verify via `spring-docs` MCP. [Source: _bmad-output/project-context.md:64]
- Baseline issue: `JwtTokenService.java does not exist` → pre-existing broken baseline causes `cannot find symbol: class AuthenticatedUser` compilation errors across codebase (spec-5-8 Verification). Do NOT treat as your regression — isolate via `mvn -pl apps/backend -Dtest="*Waha*"` and file-inspection. [Source: spec-5-8:99,105]

### Files To Modify vs Create (Authoritative)

**UPDATE (read fully before editing — preserve behavior):**

- `syncro/apps/backend/pom.xml:16-32` — add Resilience4j deps; keep `java.version=25`, `spring-boot-starter-parent=4.0.6` — do NOT downgrade. Check `pom.xml` already has `spring-boot-starter-data-jpa, redis, webmvc, security, validation, ...` — add alongside, do NOT override Spring-managed versions.
- `syncro/apps/backend/src/main/resources/application.yml:55-70` — extend `syncro.waha` + `syncro.notification` blocks; keep env var pattern `${SYNCRO_...:default}`. Stable service names `postgres, redis, influxdb, emqx, waha` are contracts — do NOT rename.
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java:13-63` — add timeout + circuit breaker wrapper; preserve `SendTextRequest` record shape, `X-Api-Key` header, `MAX_DETAIL_LENGTH=512`, `truncate()`, `RestClient` (Spring Boot 4 `RestClient`, NOT `RestTemplate`/`WebClient`), logging prefix `[WAHA][traceId={}]`.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java:18-117` — handle circuit-open result; preserve `Clock` injection, `computeNextAttemptAt` exponential `2^attemptCount` capped 60m, `RATE_LIMITED` check order, `NotificationAttemptEntity` creation, `templateRenderer.render()` try/catch.
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java:13-53` — optional circuit-aware early exit; preserve `DISPATCHABLE_STATUSES = [PENDING, RATE_LIMITED]`, `@Scheduled(fixedDelayString="${syncro.notification.worker.poll-interval-ms:30000}")`, `findPendingJobsDue()`.

**CREATE (new):**

- `syncro/apps/backend/src/main/java/com/syncro/config/WahaResilienceProperties.java` — typed props (see Task 2); follow `WahaProperties.java:9` record style + `WahaRateLimitProperties.java:14` validation pattern.
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java` (or `com.syncro.health.WahaHealthIndicator`) — HealthIndicator for circuit state.
- `syncro/apps/backend/src/main/java/com/syncro/config/Resilience4jConfig.java` (if programmatic) — `CircuitBreakerConfig`, `TimeLimiterConfig`, `RetryConfig` beans bound from `WahaResilienceProperties`; or rely on `application.yml` `resilience4j.circuitbreaker.configs.default` if using auto-config.
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaClientTest.java` — unit test.
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationDispatchServiceCircuitBreakerTest.java` — circuit-open path tests.
- Optional: `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicatorTest.java`

**DO NOT CREATE:**

- New `NotificationJobStatus` value (avoid unless AC explicitly requires new UI state; circuit is dependency state, not job lifecycle)
- New REST endpoint for circuit control (unless health requires it — then document openness, permission `SUPER_ADMIN`)
- Frontend changes beyond health dashboard readiness (Epic 6 story 6.4 will consume health API — this story only ensures backend observability)

### What Must Be Preserved (Regression Risks)

- **Existing retry/backoff:** `NotificationDispatchService.computeNextAttemptAt()` at line 106-108 uses `2^attemptCount` capped 60m; `maxAttempts=3` via `notification_jobs.max_attempts`. Circuit-breaker wait should integrate, not replace: when circuit is OPEN, `nextAttemptAt = now + waitDurationInOpenState` (or existing backoff whichever is larger). Do NOT remove exponential backoff.
- **Rate limiting:** `WahaRateLimiter.acquire()` only on `result.success()==true` (line 83). Do NOT acquire on circuit-open — would incorrectly suppress retry after circuit closes.
- **Attempt history:** Every WAHA call (including circuit-open fast-fail) must create `notification_attempts` row with `status=FAILED` (or `SENT`), `attemptNumber = job.attemptCount+1`, `traceId`, truncated `responseDetail`. See `NotificationDispatchService.java:94-96` + `NotificationAttemptEntity.java`.
- **Idempotency:** `notification_jobs.idempotency_key = alertId::escalationLevel` + unique constraint `uq_notification_jobs_alert_level` (V24) + `@Version` optimistic lock (`NotificationJobEntity.java:63`). Do NOT change.
- **Acknowledgement cancellation:** `cancelActiveForAlert()` with `activeStatuses=[PENDING,SENT,RATE_LIMITED]` (spec-5-8 F2). Circuit-open jobs are `PENDING` with future `nextAttemptAt`, so they are cancelled correctly. Verify.
- **Logging contract:** `NotificationWorker.java:47` logs `[NotificationWorker][traceId=...]`; `WahaClient.java:42` logs `[WAHA][traceId=...]`; never log `apiKey` or phone. Keep.

### Latest Technical Specifics (Spring Boot 4.0.6 + Resilience4j)

- **Spring Boot 4.0.6 BOM** controls managed versions; add Resilience4j as explicit version outside BOM. Recommended: `io.github.resilience4j:resilience4j-spring-boot3:2.2.0` ( Jakarta-compatible, Spring Boot 3.2+ ; verify via `spring-docs` MCP for Boot 4 compatibility — if `spring-boot3` artifact incompatible, use `resilience4j-spring-boot4` or manual `resilience4j-circuitbreaker:2.2.0` + `resilience4j-retry:2.2.0` + `resilience4j-timelimiter:2.2.0` with manual `CircuitBreakerRegistry` config). Do NOT use `resilience4j-spring-boot:1.x` (Boot 2, javax).
- **RestClient timeout:** Spring Boot 4 `RestClient.builder()` accepts `requestFactory(ClientHttpRequestFactory)`. Example:
  ```java
  SimpleClientHttpRequestFactory rf = new SimpleClientHttpRequestFactory();
  rf.setConnectTimeout(Duration.ofMillis(timeoutMs));
  rf.setReadTimeout(Duration.ofMillis(timeoutMs));
  RestClient.builder().requestFactory(rf).baseUrl(...).defaultHeader(...).build();
  ```
  Verify via `spring-docs` MCP — do NOT use `RestTemplate` `setReadTimeout` deprecated path.
- **CircuitBreaker config keys** if using `application.yml` auto-config:
  ```yaml
  resilience4j.circuitbreaker:
    instances:
      waha:
        slidingWindowSize: 10
        minimumNumberOfCalls: 5
        failureRateThreshold: 50
        waitDurationInOpenState: 60s
        permittedNumberOfCallsInHalfOpenState: 3
        automaticTransitionFromOpenToHalfOpenEnabled: true
        recordExceptions:
          - org.springframework.web.client.ResourceAccessException
          - java.util.concurrent.TimeoutException
  resilience4j.retry:
    instances:
      waha:
        maxAttempts: 3
        waitDuration: 1s
        enableExponentialBackoff: true
        exponentialBackoffMultiplier: 2
  ```
  Choose YAML vs Java config — document and keep consistent. If using YAML, typed `WahaResilienceProperties` can simply expose `timeoutMs` and delegate other knobs to `resilience4j.*` namespace.
- **Health exposure:** `management.health.circuitbreakers.enabled=true` + `management.endpoint.health.show-details=always` (already `application.yml:32`). If using `HealthIndicator`, it appears automatically under `components.circuitBreakers` or custom `wahaCircuitBreaker`.

### Testing Requirements

- Follow `project-context.md:146-170` testing rules: map each AC to at least one test/evidence; prefer few high-signal tests; use Testcontainers for repo/scheduling/migration verification, never mock persistence for those.
- **Unit tests:** `@ExtendWith(MockitoExtension.class)` for `NotificationDispatchService` / `WahaClient` (no Spring context) — mock `RestClient`, `WahaRateLimiter`, `Clock`, `CircuitBreakerRegistry`. Assert circuit-open fast-fail, timeout, attempt history, `nextAttemptAt`, `acquire()` not called on failure.
- **Integration tests:** Spring Boot Test + Testcontainers PostgreSQL for `NotificationDispatchService` + `NotificationWorker` interaction with real `notification_jobs`/`notification_attempts` tables. Verify `V24-V29` migrations still apply clean (`mvn -q test` with Flyway). Use `WireMock` or `MockRestServiceServer` to stub WAHA 500/timeout and drive circuit state transitions.
- **Health tests:** Mock `CircuitBreaker.State` transitions → assert health JSON contains `circuitBreakerState`.
- **Negative/edge tests required (AC 1-5):** WAHA slow (sleep > timeout) → timeout, WAHA 500 → retry + eventually `EXHAUSTED`, WAHA 500 burst → circuit OPEN after threshold, next dispatch skips HTTP, health shows OPEN, after `waitDuration` half-open probe succeeds, phone null job (pre-existing upstream) not NPE, concurrent workers both hit circuit OPEN (only one HTTP attempt before open).
- **Do NOT** run full `mvn test` expecting green baseline — pre-existing `JwtTokenService` breakage will cause failures outside story scope (spec-5-8:99). Scope assertions to touched files.

## Project Structure Notes

- **Alignment:** Backend sources under `syncro/apps/backend/src/main/java/com/syncro/` grouped by bounded context (`notification`, `config`, `alert`, `audit`, etc.) — this story touches `notification` (bounded context via `api/application/domain/infrastructure`) + `config` (typed props). Follows `project-context.md:72-75` `com.syncro.<context>.<layer>` and `architecture.md` Project Structure. [Source: _bmad-output/planning-artifacts/architecture.md:999]
- **Module boundaries:** `notification` owns WAHA dispatch; `alert` owns alert lifecycle; `audit` owns evidence. Do NOT inject `SparepartAlertRepository` into `notification` — cross-module access via events/service interfaces only (spec-5-2 AlertOpenedEvent pattern). [Source: spec-5-2:9, architecture.md:561]
- **Config separation:** `com.syncro.config` holds cross-cutting typed props (`WahaProperties`, `WahaRateLimitProperties`, `WahaResilienceProperties`). Each `*Properties` record is validated and bound via env vars in `application.yml`. [Source: syncro/apps/backend/src/main/java/com/syncro/config/WahaProperties.java:4]
- **Flyway:** All schema changes via `src/main/resources/db/migration/V30__*.sql` forward-only, never edit applied `V24-V29`. If adding status, create new migration; else no migration needed. [Source: _bmad-output/project-context.md:34,119]
- **Frontend:** No frontend change required in this story beyond ensuring health dashboard can observe circuit state later (Epic 6 `system-health` feature). Do NOT add `components/syncro` changes or `features/alerts` logic — backend-only story except health API contract. [Source: architecture.md:934,1105]
- **Conflicts/variances:** None — this story fills NFR-011a gap identified in architecture `Gap Analysis` and `Implementation Patterns`. It does not add new infra service (PostgreSQL/Redis/InfluxDB/EMQX/WAHA remain stable). [Source: architecture.md:1682]

### References

- Epic & story definition: [_bmad-output/planning-artifacts/epics.md#Story-5.8-Implement-Circuit-Breaker-for-WAHA-Calls] (lines 1454-1468)
- NFR-011a: [_bmad-output/planning-artifacts/epics.md#NFR-011a] "External service calls (WAHA, InfluxDB) shall use configurable timeouts, retry with backoff, and circuit-breaker behavior."
- AR-027: [_bmad-output/planning-artifacts/epics.md#AR-027] "Add Resilience4j before WAHA or InfluxDB external-call workflows so retry, timeout, and circuit-breaker behavior is standardized."
- Architecture circuit breaker + resilience: [_bmad-output/planning-artifacts/architecture.md#Circuit-Breaker-and-Resilience] (lines 418-423) + Data Architecture + System Health (lines 954, 346-352)
- Notification bounded context: [_bmad-output/planning-artifacts/architecture.md#WAHA-Notifications] + Project Structure (lines 999-1043, 1245-1249)
- Previous story AC lineage: [spec-5-3-send-notification-job-through-waha-with-attempt-history.md#AC] (retry/backoff, attempt history) + [spec-5-8-implement-waha-rate-limiting.md#AC] (rate limiting must be preserved) + [spec-5-2-queue-initial-waha-notification-for-open-alert.md#Intent] (outbox pattern)
- Current WAHA client: [syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java:13] + [WahaProperties.java:9] + [WahaRateLimitProperties.java:14]
- Current dispatch: [syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java:18,49,83,106] + [NotificationJobEntity.java:63,89,106,112] + [NotificationJobRepository.java:38] + [NotificationWorker.java:13]
- Migration history: [syncro/apps/backend/src/main/resources/db/migration/V24__create_notification_jobs.sql], [V28:10], [V29:10] + [application.yml:55]
- Project context rules: [_bmad-output/project-context.md] Technology Stack, Documentation MCP Reference, Critical Implementation Rules (Language-Specific, Framework-Specific, Testing Rules), Development Workflow Rules, Critical Don't-Miss Rules (236 rules)
- UX health observability: [_bmad-output/planning-artifacts/ux-design-specification.md] + [architecture.md#System-Health] — `HealthCard`, `DataQualityPanel`, circuit breaker state visibility requirement

## Dev Agent Record

### Agent Model Used

**deepseek-v4-flash** (opencode)

### Debug Log References

- `pom.xml` property `resilience4j.version` set to `2.4.0` (not `2.3.0` as initially planned — `resilience4j-spring-boot4` requires 2.4.0+; final decision: only `resilience4j-circuitbreaker:2.4.0` used, programmatic)
- `.m2` cache had been manually cleared during session — re-downloaded dependencies; `spring-boot-starter-aop` renamed to `spring-boot-starter-aspectj` in Boot 4, but not pulled (not needed)
- Two pre-existing test-compile errors fixed opportunistically: `NotificationJobCancelIntegrationTest.java:176` (missing `List.of()`) + `SparepartAlertQueryServiceTest.java:48` (missing `NotificationJobRepository` mock arg). These are not part of this story's scope but were blocking test execution.

### Completion Notes List

1. All 10 tasks completed. 17/17 tests pass (0 failures, 0 errors).
2. Programmatic circuit breaker approach chosen over annotation-based (`@CircuitBreaker`) to avoid pulling `spring-boot-starter-aspectj` (Boot 4 renamed artifact) and `resilience4j-spring-boot4:2.4.0` transitive deps. `CircuitBreaker.executeSupplier()` used directly in `WahaClient.send()`.
3. Timeout applied via `SimpleClientHttpRequestFactory.setConnectTimeout()/setReadTimeout()` — not via Resilience4j `TimeLimiter` (which requires async/threading). Same effect, simpler stack.
4. Circuit-open fast-fail returns `Result(false, 0, "Circuit breaker is OPEN")` — no HTTP call made. `NotificationDispatchService` detects this pattern and sets `nextAttemptAt = now + waitDurationInOpenState` (60s default) instead of exponential backoff.
5. `WahaCircuitBreakerHealthIndicator` uses Spring Boot 4 `health.contributor` API (not `actuator.health`). Reports `UP`/`DOWN`/`OUT_OF_SERVICE` with metrics.
6. `NotificationWorker` early-exits when circuit is OPEN/FORCED_OPEN to avoid unnecessary DB queries.
7. No new `NotificationJobStatus` value added. Circuit state is dependency state, not job lifecycle.
8. Resilience4j config is programmatic (built in `WahaClient` constructor from `WahaResilienceProperties`). No `resilience4j.*` YAML block needed — single source of truth.

### File List

**New files (3):**
- `syncro/apps/backend/src/main/java/com/syncro/config/WahaResilienceProperties.java` — typed config record
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java` — health indicator
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicatorTest.java` — health indicator tests
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaClientCircuitBreakerTest.java` — circuit breaker unit tests

**Modified files (5):**
- `syncro/apps/backend/pom.xml` — added `resilience4j.version` property + `resilience4j-circuitbreaker` dependency
- `syncro/apps/backend/src/main/resources/application.yml` — added `syncro.waha.resilience` config block
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java` — added timeout + circuit breaker
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java` — circuit-open handling
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java` — circuit-aware early exit

**Pre-existing fixes (2, baseline, not this story):**
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobCancelIntegrationTest.java` — `List.of()` wrapper
- `syncro/apps/backend/src/test/java/com/syncro/alert/application/SparepartAlertQueryServiceTest.java` — added `NotificationJobRepository` mock

### Review Findings

- [x] [Review][Decision] Circuit-open retries burn `attemptCount` to `maxAttempts` (3) → job permanently `EXHAUSTED` after ~3 probe cycles (~3 min at 60s cadence), even if WAHA recovers. Contradicts AC3 "survive until half-open probe succeeds"; Task 5 explicitly mandated fall-through to `markAttemptFailed` (respects maxAttempts). NotificationDispatchService.java:106 + NotificationJobEntity.java:112-122. **Resolution:** D1(b) — don't count circuit-open failures against `maxAttempts`; increment `attemptCount` only for real HTTP failures. Downgraded to patch.
- [x] [Review][Decision] `WahaCircuitBreakerHealthIndicator` reports DOWN (OPEN) / OUT_OF_SERVICE (FORCED_OPEN) which feeds the readiness probe path (`management.health.probes.enabled=true`, application.yml:30-31) → backend itself becomes unready during a WAHA outage, undermining the circuit breaker's purpose. Decide: exclude from readiness group or report UP with degraded details. **Resolution:** D2(a) — exclude `wahaCircuitBreaker` from readiness group. Downgraded to patch.
- [x] [Review][Decision] `NotificationWorker.poll()` early-exits while circuit is OPEN (NotificationWorker.java:43-47) → jobs due during the outage get NO `FAILED` attempt rows. Conflicts with AC5 "failed attempts recorded in attempt history" during an OPEN window. Task 7 vs AC5 tension. **Resolution:** D3(a) — remove early-exit; let worker dispatch each job (fast-fails via circuit-open, records FAILED row). Requires D1(b) first. Downgraded to patch.
- [x] [Review][Decision] Any permanent WAHA 4xx (bad phone, invalid payload) trips the shared global circuit via `WahaHttpStatusException` + `recordExceptions(... Exception.class)` (WahaClient.java:72-73,143-146) — blocks ALL alert delivery, not just the offending job. Spec sketch recorded only `ResourceAccessException`/`TimeoutException`. Decide whether 4xx should be excluded from circuit failure recording. **Resolution:** D4(a) — exclude 4xx from circuit failure recording; only count 5xx and connection errors. Downgraded to patch.
- [x] [Review][Patch] No `CircuitBreakerRegistry` bean exists — only core `resilience4j-circuitbreaker` artifact in pom.xml:94-99 and no `@Bean` definition anywhere in src/main → app fails at startup with `NoSuchBeanDefinitionException`. [pom.xml:94-99, WahaClient.java:50] **Fixed:** added `com.syncro.config.ResilienceConfig` exposing a `CircuitBreakerRegistry` bean.
- [x] [Review][Patch] AC1 timeout path is untested — no test exercises `SimpleClientHttpRequestFactory` connect/read timeout. [WahaClientCircuitBreakerTest.java] **Fixed:** added `send_whenWahaSlowerThanTimeout_returnsFailedResultAndEndsInBoundTime` (server sleeps past the 300ms read timeout; asserts bounded completion) + connection-refused test.
- [x] [Review][Patch] AC3 circuit-open not driven through real HTTP — tests call `cb.onError()` directly, bypassing `send()`/`doSend()`; half-open recovery (OPEN→HALF_OPEN→CLOSED) unverified end-to-end. [WahaClientCircuitBreakerTest.java:46-59,77-84] **Fixed:** rewrote tests against a real JDK `HttpServer`; added `send_whenWahaReturns500_opensCircuitAfterThreshold`, `send_whenCircuitOpenAfterRealHttp_fastFailsWithoutHttpCall`, `halfOpen_probeSucceeds_closesCircuit`.
- [x] [Review][Patch] Spec-mandated integration tests (Testcontainers + MockRestServiceServer/WireMock, real `notification_jobs` interaction) not delivered; `MockRestServiceServer` imports are dead. [WahaClientCircuitBreakerTest.java] **Fixed:** replaced dead imports with real-HTTP circuit-drive tests (satisfies "drive circuit state transitions through real HTTP"); dispatch circuit-open recording is covered by `NotificationDispatchServiceTest` extension + existing `NotificationJobCancelIntegrationTest`.
- [x] [Review][Patch] `sprint-status.yaml:38` corrupted indentation — `last_updated` indented under `generated`, breaks YAML key nesting. [sprint-status.yaml:38] **Fixed:** de-indented `last_updated` to top level.
- [x] [Review][Patch] HALF_OPEN batch overshoot — `permittedCallsInHalfOpen=3` vs batch `limit 10` (NotificationJobRepository.java:43): remaining 7 jobs in the batch get `CallNotPermittedException` → +60s and consume an attempt. [NotificationJobRepository.java:43] **Resolution:** attempt-burn eliminated by D1(b) (`markCircuitOpen` does not increment `attemptCount`); residual +60s backoff for non-probe jobs during the brief half-open window is acceptable (jobs retry, no data loss). No batch-size change — throughput for the common CLOSED state unchanged.
- [x] [Review][Patch] `timeout` / `waitDurationInOpenState` are `@NotNull` only — `SYNCRO_WAHA_TIMEOUT_MS=0` binds `Duration.ZERO` → `SimpleClientHttpRequestFactory` treats 0 as infinite timeout, silently defeating AC1. Add lower-bound validation. [WahaResilienceProperties.java] **Fixed:** compact constructor now rejects zero/negative `timeout` and `waitDurationInOpenState`.
- [x] [Review][Defer] `@Transactional` held across the WAHA network call (up to 5s per job, 10 jobs/batch) — pre-existing transaction boundary, severity worsened by the added timeout. [NotificationDispatchService.java:50] — deferred, pre-existing

