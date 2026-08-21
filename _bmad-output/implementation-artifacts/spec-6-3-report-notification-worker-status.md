---
baseline_commit: 65d513b307700b38f0dfc4e29e390c920c5d8fd3
---

# Story 6.3: Report Notification Worker Status

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN,
I want to see notification worker status,
So that I can know whether WAHA alert escalation can run.

## Acceptance Criteria

1. **Given** notification worker is configured **When** health status is requested **Then** backend reports notification worker `RUNNING`/`STOPPED`/`DEGRADED` state [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3]
2. **Given** notification worker status is requested **Then** status includes pending job count where available [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3]
3. **Given** notification worker status is requested **Then** status includes recent failed notification count or latest failure reason where available [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3]
4. **Given** notification worker status is requested **Then** status includes last successful WAHA send timestamp where available [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3]
5. **Given** a notification worker status request **When** the caller is not SUPER_ADMIN **Then** worker status is not returned (403); SUPER_ADMIN gets 200 [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3, _bmad-output/planning-artifacts/epics.md#NFR-006]

## Tasks / Subtasks

- [x] Task 1: Track notification worker poll liveness (AC: 1)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerTracker.java` — `@Component` with injected `Clock`; holds `volatile Instant lastPollAt`. Method `synchronized void recordPoll()` sets `lastPollAt` only when `lastPollAt == null || now.isAfter(lastPollAt)` (monotonic guard — mirrors `TelemetryIngestTracker.java:28-34` review fix from 6-2, prevents a stale write from regressing the timestamp). Accessor `Instant lastPollAt()`. In-memory by design (resets on restart), observability only.
  - [x] Decision (do NOT build): a poll counter / processed-job counter is optional and out of scope — ACs require pending/failed counts from the DB (Task 3), not an in-memory processed counter.

- [x] Task 2: Modify `NotificationWorker` to record poll (AC: 1)
  - [x] Modify `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java` — inject `NotificationWorkerTracker`; call `tracker.recordPoll()` at the START of `poll()` (before the empty-check early return, so a worker that polls and finds nothing is still alive). Do NOT wrap in try/catch — `recordPoll()` cannot throw.
  - [x] Update any test constructing `NotificationWorker` with the old 3-arg constructor — currently only `NotificationWorkerTest` is planned new (see Task 6); there is no existing `NotificationWorkerTest`. Grep for `new NotificationWorker(` in `src/test` to confirm before changing constructors.
  - [x] Scope decision (documented, do NOT implement): `EscalationWorker.poll()` is a separate secondary worker. AC 6.3 and page-spec §4.3 describe ONE "Notification Worker" card oriented around WAHA dispatch (pending jobs / last send). Track the dispatch `NotificationWorker` only. EscalationWorker liveness is out of scope for 6-3.

- [x] Task 3: Define the notification worker state model (AC: 1)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerState.java` — enum `RUNNING`, `STOPPED`, `DEGRADED`. Each value carries `statusLabel` + `statusSeverity` per the architecture operational-status severity set (`architecture.md:656-663`): `RUNNING → "Running"/"SUCCESS"`, `STOPPED → "Stopped"/"CRITICAL"`, `DEGRADED → "Degraded"/"WARNING"`. Do NOT depend on `com.syncro.health.DependencyHealthSupport` — this enum lives in the notification module to respect AR-014 module boundaries; severity strings are plain constants matching the shared taxonomy (same as `IngestWorkerState.java` did for telemetry).
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatus.java` — response record with fields: `NotificationWorkerState status`, `String statusLabel`, `String statusSeverity`, `String statusReason`, `String timestamp` (ISO `Instant.now(clock).toString()`), `String lastPollAt` (ISO or null), `String staleSince` (ISO or null), `long pendingJobCount`, `long recentFailedCount`, `String lastFailureReason` (latest FAILED attempt `responseDetail`, or null), `String lastSuccessfulSendAt` (ISO of latest SENT attempt `attemptedAt`, or null), `String circuitBreakerState` (WAHA circuit state name e.g. `CLOSED`/`OPEN`, or null). JSON `status` serializes to the enum name (`RUNNING`) — matches AC 1.

- [x] Task 4: Assemble notification worker status (AC: 1, 2, 3, 4)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatusService.java` — `@Service`. Inject: `NotificationWorkerTracker`, `NotificationJobRepository`, `NotificationAttemptRepository`, `WahaClient` (for circuit state), `NotificationProperties`, `Clock`. Method `NotificationWorkerStatus status()`.
  - [x] State derivation (no live connection event stream exists for a `@Scheduled` poller — state is inferred):
    - `lastPollAt == null` → `STOPPED`, reason `"Worker never polled"`.
    - WAHA circuit breaker OPEN / FORCED_OPEN / DISABLED (from `wahaClient.getCircuitBreaker().getState()`) → `DEGRADED`, reason `"WAHA circuit breaker is " + state` (surface this as the primary degraded signal — dispatch cannot succeed while the circuit is OPEN).
    - `elapsed = Duration.between(lastPollAt, now)`; if `elapsed.compareTo(staleThreshold) > 0` → `DEGRADED`, reason `"Worker last polled at " + lastPollAt`, `staleSince = lastPollAt.plus(staleThreshold)`.
    - Otherwise → `RUNNING`, reason null.
    - Boundary semantics (mirror 6-2 review decision `IngestWorkerStatusService.java:69`): stale only when `elapsed > staleThreshold` (exact equality stays RUNNING). Negative elapsed (clock moved backward, NTP adjustment) → treat as fresh (RUNNING) — same guard style as 6-2.
    - Degraded-signal precedence: circuit breaker check BEFORE stale-poll check, so an OPEN circuit is reported even while the poll loop is alive.
  - [x] Metrics (DB-backed, `where available` → null-safe):
    - `pendingJobCount` = `notificationJobRepository.countByStatusIn(List.of(PENDING, RATE_LIMITED))` — jobs still waiting for dispatch.
    - `recentFailedCount` = `notificationAttemptRepository.countByStatusAndAttemptedAtAfter("FAILED", now.minus(failedWindow))` — FAILED WAHA attempts within the configured window (default PT1H).
    - `lastFailureReason` = `notificationAttemptRepository.findTopByStatusOrderByAttemptedAtDesc("FAILED")` → its `responseDetail` (truncate to 512 to match entity column width), or null.
    - `lastSuccessfulSendAt` = `notificationAttemptRepository.findTopByStatusOrderByAttemptedAtDesc("SENT")` → its `attemptedAt`, or null. Use the append-only attempts table (source of truth for every send) — NOT `notification_jobs.sentAt`, because a SENT job later becomes ESCALATED and would be missed.
    - `circuitBreakerState` = `wahaClient.getCircuitBreaker().getState().name()`.
  - [x] Do NOT add `/actuator/health` exposure — worker state is SUPER_ADMIN-only (AC 5); `/actuator/health` stays `permitAll` for k8s liveness (`SecurityConfig.java:35`). Endpoint lives under `/api/v1/...` (Task 5).

- [x] Task 5: Expose the status endpoint (AC: 5)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/notification/api/NotificationWorkerStatusController.java` — `@Tag(name = "notification-worker")`, `@RestController`, `@RequestMapping("/api/v1/notification/worker")`, `GET /status`. Inline `ApplicationRole.SUPER_ADMIN` guard throwing `ResponseStatusException(HttpStatus.FORBIDDEN)` when `user.applicationRole() != ApplicationRole.SUPER_ADMIN` — same pattern as `IngestWorkerStatusController.java:42-46` and `TelemetryQuarantineController`. OpenAPI `@Operation(operationId = "getNotificationWorkerStatus")` with 200/401/403 `@ApiResponse`s. Class-level javadoc MUST note: deliberately NOT exposed via `/actuator/health` (unauthenticated k8s liveness) because worker state is SUPER_ADMIN-only per NFR-006.

- [x] Task 6: Config + repository queries (AC: 2, 3, 4)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/config/NotificationProperties.java` — `@ConfigurationProperties(prefix = "syncro.notification")` record with nested `Worker worker`. Fields: `@DefaultValue("PT5M") Duration staleThreshold` (worker considered DEGRADED when last poll older than this) and `@DefaultValue("PT1H") Duration failedWindow` (window for recent-failed count). Canonical-constructor validation: both must be non-null, non-zero, non-negative (throw `IllegalArgumentException` with the property key, mirroring `TelemetryProperties.java:27-30`). No-arg convenience constructor with defaults (mirror `WahaResilienceProperties.java:77-86`).
  - [x] Modify `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` — add `long countByStatusIn(java.util.Collection<NotificationJobStatus> statuses);`.
  - [x] Modify `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationAttemptRepository.java` — add `long countByStatusAndAttemptedAtAfter(String status, Instant attemptedAt);` and `Optional<NotificationAttemptEntity> findTopByStatusOrderByAttemptedAtDesc(String status);`.
  - [x] Modify `syncro/apps/backend/src/main/resources/application.yml` — under `syncro.notification.worker` add `stale-threshold: ${SYNCRO_NOTIFICATION_WORKER_STALE_THRESHOLD:PT5M}` and `failed-window: ${SYNCRO_NOTIFICATION_FAILED_WINDOW:PT1H}` with the same comment style as the existing `poll-interval-ms` entry.

- [x] Task 7: Tests (AC: 1, 2, 3, 4, 5)
  - [x] New file: `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerTrackerTest.java` — fixed `Clock` (mirror `TelemetryIngestTrackerTest.java:12-15`): initial `lastPollAt()` is null; `recordPoll()` sets it to now; later clock `recordPoll()` keeps the latest timestamp; a stale (earlier) write does not regress `lastPollAt`.
  - [x] New file: `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerStatusServiceTest.java` — `@ExtendWith(MockitoExtension.class)` with `@Mock` repository/attempt/WahaClient and a real `NotificationWorkerTracker(FIXED_CLOCK)` (mirror `IngestWorkerStatusServiceTest.java`). Cases:
    - never polled → `STOPPED`, label `"Stopped"`, severity `"CRITICAL"`, reason `"Worker never polled"`.
    - recent poll + circuit CLOSED → `RUNNING`.
    - poll older than stale threshold → `DEGRADED`, reason contains `"Worker last polled at"`, `staleSince` = `lastPollAt + threshold`.
    - circuit OPEN → `DEGRADED`, reason contains `"WAHA circuit breaker is"`, regardless of a fresh poll (precedence).
    - exact-equality boundary: `lastPollAt = now - staleThreshold` → still `RUNNING` (stale only when `>`).
    - negative elapsed (clock moved back) → `RUNNING`, not DEGRADED.
    - surfaces `pendingJobCount`, `recentFailedCount`, `lastFailureReason`, `lastSuccessfulSendAt`, `circuitBreakerState`, and `timestamp` (fixed now ISO string).
    - repository queries receive the expected args (`countByStatusIn` with PENDING+RATE_LIMITED; `countByStatusAndAttemptedAtAfter("FAILED", now - failedWindow)`; `findTopByStatusOrderByAttemptedAtDesc("SENT")` / `("FAILED")`).
  - [x] New file: `syncro/apps/backend/src/test/java/com/syncro/notification/api/NotificationWorkerStatusControllerTest.java` — `@WebMvcTest(NotificationWorkerStatusController.class)` + `@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})`, `@MockitoBean NotificationWorkerStatusService` + `JwtTokenService` (mirror `IngestWorkerStatusControllerTest.java`). Cases: SUPER_ADMIN → 200 + `status` field; MANAGE → 403; VIEWER → 403; unauthenticated → 401.
  - [x] New file: `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerTest.java` — verify `recordPoll()` is invoked on `poll()` when jobs are empty AND when jobs are present (the empty-early-return must not skip the record). Mock `NotificationJobRepository` + `NotificationDispatchService`; real tracker with fixed clock. Verify `dispatchService.dispatch` still called per job (no behavioral regression).

## Dev Notes

- **Follow story 6.2 exactly as the structural template:** this story mirrors `spec-6-2-report-telemetry-ingest-worker-status.md` (tracker + state enum + status record + status service + controller + tests). Reuse the SAME patterns: `TelemetryIngestTracker`, `IngestWorkerState`, `IngestWorkerStatus`, `IngestWorkerStatusService`, `IngestWorkerStatusController`, and their tests. Do not reinvent a parallel style.
- **Module boundary (AR-014):** all new code lives in the notification module (`com.syncro.notification.application` / `.api`). `NotificationWorkerState` carries its own label/severity constants (same strings as the shared taxonomy) instead of depending on `com.syncro.health` — exactly as `IngestWorkerState.java` does for telemetry. [Source: architecture.md AR-014, epics.md#AR-014]
- **Visibility constraint:** `/actuator/health` is `permitAll` + `show-details: always` for k8s liveness (`SecurityConfig.java:35`, story 6.1/5-9). Worker state MUST NEVER be added there — AC 5 requires SUPER_ADMIN-only. The protected `/api/v1/notification/worker/status` endpoint is the correct home.
- **Operational status contract:** response reuses the architecture field set `status`/`statusLabel`/`statusReason`/`statusSeverity`/`timestamp` (`architecture.md:643-663`), extended with worker observability fields. `statusReason` is descriptive (protected endpoint) — unlike the sanitized public health reasons from the 6-1 review, raw failure text (`responseDetail`) is acceptable here because only SUPER_ADMIN can reach it.
- **Worker "running" semantics:** the notification path is a `@Scheduled(fixedDelay)` poller (`NotificationWorker.java:33`), so there is NO live connection-event stream to inspect (unlike MQTT). "Running" is inferred from `lastPollAt` freshness + WAHA circuit state. Never attempt a thread-liveness check.
- **Config validation:** add `NotificationProperties` with `staleThreshold` (default PT5M) and `failedWindow` (default PT1H). Both validated positive in the canonical constructor. Note: `syncro.notification.worker.poll-interval-ms` already exists in `application.yml:84` and is read by the `@Scheduled` annotation — do NOT move or rename it; the new record binds only the two new keys.
- **Data-source guidance (reuse, do not reinvent):**
  - Pending = jobs `PENDING` + `RATE_LIMITED` (dispatchable). Do NOT use `findPendingJobsDue(...)` for the count — it is capped at 10 rows; add `countByStatusIn`.
  - Last successful WAHA send = latest `notification_attempts` row with status `SENT` (`attemptedAt`) — the append-only attempts table is authoritative. Do NOT read `notification_jobs.sentAt` (a job becomes ESCALATED after SENT and would be missed).
  - Recent failed count = FAILED attempts within `failedWindow`. Latest failure reason = most recent FAILED attempt's `responseDetail`.
  - Circuit state = `WahaClient.getCircuitBreaker().getState()` — already exposed (`WahaClient.java:115`, used by `WahaCircuitBreakerHealthIndicator.java:35`). Do NOT add a second circuit state tracker.
- **Frontend is OUT of scope for 6-3:** the SUPER_ADMIN health dashboard (dependency + worker cards) is story 6.4. Backend endpoint + tests only. Do NOT touch `apps/web`.
- **Testing standards:** backend unit tests use JUnit 5 + Mockito (`@ExtendWith(MockitoExtension.class)`) and `@WebMvcTest` + `@MockitoBean` for controllers; `Clock` is always injected (`Clock.fixed`) — never `Clock.systemUTC()` in tests. Run `mvn -q test` from `syncro/apps/backend`. Baseline suite before this story: 645 tests, 2 failures + 134 errors pre-existing — do NOT introduce new regressions (compare against the same baseline).

### Project Structure Notes

- Alignment with the unified project structure: new production classes go in `syncro/apps/backend/src/main/java/com/syncro/notification/{application,api}` and `com/syncro/config`; tests in `syncro/apps/backend/src/test/java/com/syncro/notification/{application,api}`. Controllers in `api`, domain/state/application logic in `application`, JPA entities/repositories in `infrastructure` (AR-014 module layout, same as `spec-6-2`).
- No package/folder conflicts detected. No frontend or migration changes needed.

### References

- [Source: _bmad-output/planning-artifacts/epics.md#Story-6.3] — Story statement + AC 1–5.
- [Source: _bmad-output/planning-artifacts/epics.md#NFR-006] — health checks make telemetry + notification path failures visible to SUPER_ADMIN.
- [Source: _bmad-output/planning-artifacts/architecture.md#Operational-status-responses] — status/statusLabel/statusReason/statusSeverity/timestamp contract + severity set (lines 643-663).
- [Source: _bmad-output/planning-artifacts/page-specifications.md#4.3] — "Notification Worker: Status (running/stopped), jobs processed (last 1h), pending jobs, last send timestamp".
- [Source: _bmad-output/implementation-artifacts/spec-6-2-report-telemetry-ingest-worker-status.md] — full structural template; review findings to carry forward (monotonic tracker write, `>` stale boundary, negative-elapsed guard, AR-014, no actuator exposure).
- [Source: syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryIngestTracker.java:28-34] — synchronized monotonic `recordAccepted` pattern.
- [Source: syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerStatusService.java:45-103] — state-derivation service template.
- [Source: syncro/apps/backend/src/main/java/com/syncro/telemetry/api/IngestWorkerStatusController.java:35-47] — SUPER_ADMIN guard + OpenAPI + "not in actuator" javadoc pattern.
- [Source: syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java:33-52] — poll loop to instrument.
- [Source: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationAttemptRepository.java] — attempts repo to extend (status stored as String `"SENT"`/`"FAILED"`).
- [Source: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java:38-47] — pending-jobs query (capped at 10; add count).
- [Source: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java:115] — `getCircuitBreaker()` accessor.
- [Source: syncro/apps/backend/src/main/java/com/syncro/config/WahaResilienceProperties.java:77-86] — convenience-constructor default pattern for `NotificationProperties`.

## Dev Agent Record

### Agent Model Used

opencode — openagentic/deepseek-v4-flash-free

### Debug Log References

- Backend build/test: `syncro/apps/backend` via `mvnw.cmd test`

### Completion Notes List

- Implemented `NotificationWorkerStatusService` state derivation: STOPPED (never polled) → DEGRADED (circuit OPEN/FORCED_OPEN/DISABLED takes precedence, then stale poll > PT5M) → RUNNING. Boundary: stale only when elapsed > threshold; negative elapsed treated as fresh.
- Metrics are DB-backed: pending = `countByStatusIn(PENDING, RATE_LIMITED)`; recentFailed = FAILED attempts within PT1H; lastFailureReason + lastSuccessfulSendAt from the append-only attempts table (NOT `notification_jobs.sentAt`, which misses jobs that later become ESCALATED).
- Endpoint `GET /api/v1/notification/worker/status` uses inline SUPER_ADMIN guard, deliberately NOT exposed via `/actuator/health` (NFR-006). AR-014 respected: no `com.syncro.health` dependency in the notification module.
- 17 new tests passing; full suite shows no new regressions (pre-existing Docker/UnnecessaryStubbing failures unchanged).

### File List

- [new] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerTracker.java`
- [new] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerState.java`
- [new] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatus.java`
- [new] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerStatusService.java`
- [new] `syncro/apps/backend/src/main/java/com/syncro/notification/api/NotificationWorkerStatusController.java`
- [new] `syncro/apps/backend/src/main/java/com/syncro/config/NotificationProperties.java`
- [modified] `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorker.java` — inject `NotificationWorkerTracker`; `recordPoll()` at start of `poll()`
- [modified] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` — added `countByStatusIn`
- [modified] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationAttemptRepository.java` — added `countByStatusAndAttemptedAtAfter`, `findTopByStatusOrderByAttemptedAtDesc`
- [modified] `syncro/apps/backend/src/main/resources/application.yml` — added `syncro.notification.worker.stale-threshold`, `failed-window`
- [new] `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerTrackerTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerStatusServiceTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/notification/api/NotificationWorkerStatusControllerTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationWorkerTest.java`

## Change Log

- 2026-08-21: Implemented story 6.3 — notification worker status endpoint `GET /api/v1/notification/worker/status` (SUPER_ADMIN only). 17 new tests added and passing (4 tracker, 7 service, 4 controller, 2 worker). Full suite: no new regressions vs. baseline — remaining failures/errors are pre-existing (`WahaRateLimiterTest`/`SparepartLifetimeEvaluatorTest` UnnecessaryStubbing + Docker/Testcontainers integration tests requiring a Docker environment).
- 2026-08-21: Scope decision confirmed — `EscalationWorker` liveness is out of scope for 6.3 (page-spec §4.3 describes ONE Notification Worker card oriented around WAHA dispatch).
- 2026-08-21: Config defaults confirmed with user — `stale-threshold` PT5M, `failed-window` PT1H.
