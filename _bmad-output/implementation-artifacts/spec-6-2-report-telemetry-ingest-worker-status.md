---
baseline_commit: 4a226b4aab155ecb0d896f88c697ff625b2c65ac
---

# Story 6.2: Report Telemetry Ingest Worker Status

Status: ready-for-dev

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN,
I want to see telemetry ingest worker status,
So that I can know whether machine telemetry can enter Syncro.

## Acceptance Criteria

1. **Given** telemetry ingest worker is configured **When** health status is requested **Then** backend reports ingest worker `running`/`stopped`/`degraded` state [Source: _bmad-output/planning-artifacts/epics.md#Story-6.2]
2. **Given** telemetry ingest worker status is requested **Then** status includes MQTT subscription state where available [Source: _bmad-output/planning-artifacts/epics.md#Story-6.2]
3. **Given** telemetry ingest worker status is requested **Then** status includes last accepted telemetry timestamp [Source: _bmad-output/planning-artifacts/epics.md#Story-6.2]
4. **Given** telemetry has not been accepted for the stale threshold **When** ingest worker status is requested **Then** the stale telemetry condition is reported with reason and timestamp [Source: _bmad-output/planning-artifacts/epics.md#Story-6.2]
5. **Given** an ingest worker status request **When** the caller is not SUPER_ADMIN **Then** worker status is not returned (403); SUPER_ADMIN gets 200 [Source: _bmad-output/planning-artifacts/epics.md#Story-6.2, _bmad-output/planning-artifacts/epics.md#NFR-006]

## Tasks / Subtasks

- [ ] Task 1: Track last accepted telemetry in the ingest path (AC: 3, 4)
  - [ ] New file: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryIngestTracker.java` — `@Component` with injected `Clock`; holds `volatile Instant lastAcceptedAt` and `AtomicLong acceptedCount`. Methods: `recordAccepted()` (sets `lastAcceptedAt = Instant.now(clock)`, increments count), `recordRejected()` (increments a rejected counter — only if trivial; ACs do NOT require rejection metrics, so `recordRejected` is OPTIONAL and may be omitted to hold scope), accessors `lastAcceptedAt()` and `acceptedCount()`. Reset is not required — counters and timestamp are in-memory and naturally reset on restart.
  - [ ] Modify `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` — inject `TelemetryIngestTracker`; in the `Accepted` branch of `handleMessage` call `tracker.recordAccepted()` BEFORE `persistenceService.persist(...)`. Do NOT move the call into persistence — "accepted" is the validation-accept point (matches the existing `mqtt_telemetry_accepted` log semantics). Do NOT log the timestamp (existing logs already carry traceId/topic).
  - [ ] Update any test that constructs `MqttTelemetryIngestHandler` with the old 4-arg constructor: `MqttTelemetryIngestHandlerTest.java` (and `MqttTelemetryIngestAtddScaffoldTest.java` if it constructs the handler) — pass a real `TelemetryIngestTracker` built with the same fixed `Clock` and add an assertion that `recordAccepted` fires on an accepted message.

- [ ] Task 2: Define the ingest worker state model (AC: 1, 4)
  - [ ] New file: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerState.java` — enum `RUNNING`, `STOPPED`, `DEGRADED`. Each value carries a `statusLabel` and `statusSeverity` per the architecture operational-status severity set (`architecture.md:656-663` → INFO/SUCCESS/WARNING/CRITICAL/NEUTRAL): `RUNNING → "Running"/"SUCCESS"`, `STOPPED → "Stopped"/"CRITICAL"`, `DEGRADED → "Degraded"/"WARNING"`. Do NOT depend on `com.syncro.health.DependencyHealthSupport` — this enum lives in the telemetry module to respect AR-014 module boundaries; the severity strings are plain constants matching the shared taxonomy.
  - [ ] New file: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerStatus.java` — response record with fields: `IngestWorkerState status`, `String statusLabel`, `String statusSeverity`, `String statusReason`, `String timestamp` (ISO `Instant.now(clock).toString()`), `String mqttState` (UNKNOWN/SUBSCRIBED/FAILED, from `MqttConnectionStatus.State` name), `String lastAcceptedAt` (ISO or null), `String staleSince` (ISO or null), `int queueDepth`, `int queueCapacity`, `long acceptedCount`. (JSON shape: `status` serializes to the enum name, e.g. `"RUNNING"` — matches AC 1.)

- [ ] Task 3: Assemble ingest worker status (AC: 1, 2, 3, 4)
  - [ ] New file: `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/IngestWorkerStatusService.java` — `@Service`. Inject: `MqttConnectionStatus` (`com.syncro.telemetry.infrastructure`), `TelemetryIngestTracker`, `QueueChannel telemetryIngestQueue` (bean from `TelemetryIngestQueueConfig.java:14`), `TelemetryProperties`, `Clock`. Method `IngestWorkerStatus status()`.
  - [ ] State derivation (document in code): read `mqttState = mqttStatus.state()` and `lastAcceptedAt = tracker.lastAcceptedAt()`.
    - `UNKNOWN` → `STOPPED`, reason `"MQTT not subscribed"` (adapter not connected/subscribed yet).
    - `FAILED` → `DEGRADED`, reason `"MQTT connection failed"` (+ `mqttStatus.lastError()` when non-null; this endpoint is SUPER_ADMIN-protected, so descriptive broker text is acceptable — note: NOT the public actuator endpoint).
    - `SUBSCRIBED` → if `lastAcceptedAt == null` → `RUNNING` (subscribed, awaiting first telemetry); else if `now - lastAcceptedAt <= staleThreshold` → `RUNNING`; else → `DEGRADED` with reason `"No telemetry accepted since <lastAcceptedAt>"` and `staleSince = lastAcceptedAt.plus(staleThreshold)`.
  - [ ] Read `staleThreshold` from `TelemetryProperties.ingest().staleThreshold()` (added in Task 6); `queueDepth = telemetryIngestQueue.getQueueSize()`, `queueCapacity = properties.ingest().queueCapacity()` (or `getQueueSize() + getRemainingCapacity()` for live capacity). `timestamp = Instant.now(clock).toString()`.

- [ ] Task 4: Expose ingest worker status to SUPER_ADMIN only (AC: 5)
  - [ ] New file: `syncro/apps/backend/src/main/java/com/syncro/telemetry/api/IngestWorkerStatusController.java` — `@RestController @RequestMapping("/api/v1/telemetry/ingest")`, `@Tag(name = "telemetry-ingest")`. `@GetMapping("/status")` returns `IngestWorkerStatus`. Follow the `TelemetryQuarantineController.java:44-50` SUPER_ADMIN guard pattern EXACTLY: `@AuthenticationPrincipal AuthenticatedUser user` + `if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) throw new ResponseStatusException(HttpStatus.FORBIDDEN);`. Add `@Operation(operationId = "getTelemetryIngestWorkerStatus", summary = "Telemetry ingest worker status")` + `@ApiResponses` (200 / 401 / 403). Do NOT add to `/actuator/health` — that endpoint is public (`permitAll`) and AC 5 requires SUPER_ADMIN-only visibility.
  - [ ] No `SecurityConfig` change required — `/api/v1/**` is already authenticated (`SecurityConfig.java:36`); the role guard is inline in the controller (existing project pattern).

- [ ] Task 5: Add configurable stale threshold (AC: 4)
  - [ ] Modify `syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java` — add `@DefaultValue("PT5M") Duration staleThreshold` to the `Ingest` record with a compact-constructor validation (`null/zero/negative` → `IllegalArgumentException`, mirroring the existing `queueCapacity`/`workerThreads` guards). Default 5 minutes aligns with the freshness `ONLINE_THRESHOLD` (`TelemetryFreshnessCalculator.java:12`). `application.yml` needs no change (`@DefaultValue` covers absence), but a `# stale threshold ...` comment under `syncro.telemetry.ingest` may be added for discoverability — optional.

- [ ] Task 6: Write tests (AC: 1-5)
  - [ ] New: `TelemetryIngestTrackerTest.java` (`com.syncro.telemetry.application`) — fixed `Clock`; `recordAccepted()` sets `lastAcceptedAt` to the fixed instant and increments `acceptedCount`; initial state is `null`/`0`; multiple records keep the LATEST timestamp.
  - [ ] New: `IngestWorkerStatusServiceTest.java` — Mockito-mock `MqttConnectionStatus`, `QueueChannel`, real `TelemetryIngestTracker` (fixed clock) + real `TelemetryProperties` (staleThreshold PT5M). State matrix: UNKNOWN→STOPPED; FAILED→DEGRADED with reason containing `lastError`; SUBSCRIBED+recent→RUNNING; SUBSCRIBED+null lastAccepted→RUNNING; SUBSCRIBED+stale(>5min)→DEGRADED with `staleSince` and reason containing the last accepted timestamp; queue depth/capacity surfaced; `timestamp` present.
  - [ ] New: `IngestWorkerStatusControllerTest.java` (`com.syncro.telemetry.api`) — `@WebMvcTest(IngestWorkerStatusController.class)` + `@Import({SecurityConfig.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})`, `@MockitoBean JwtTokenService` + `@MockitoBean IngestWorkerStatusService`. Reuse the `user(ApplicationRole)` / `auth(...)` helper pattern from `TelemetryQuarantineControllerTest.java:112-121`. Cases: SUPER_ADMIN→200 with `$.status`, `$.statusLabel`, `$.statusSeverity`, `$.timestamp`, `$.mqttState`, `$.lastAcceptedAt`; MANAGE→403; VIEWER→403; unauthenticated→401.
  - [ ] Optional integration-style: extend the existing minimal-context `ActuatorHealthIntegrationTest` pattern with a small `@SpringBootTest` (or `@WebMvcTest`) proving `GET /api/v1/telemetry/ingest/status` returns the enriched contract for a mocked service — only if the minimal-context approach stays green in CI; otherwise rely on the controller + service unit tests (they cover AC 1-5).

## Dev Notes

- **Backend-only story.** Frontend System Health page (`system-health-page.tsx`) currently renders the MQTT card from `components.mqtt` and has no ingest-worker card yet; the page-specs "Workers" section (Telemetry Ingest HealthCard + metrics) and the aggregated `/api/v1/health/summary` endpoint belong to Story 6.4. This story delivers the protected backend status endpoint. Note this variance in dev notes. [Source: page-specifications.md:448-453, spec-6-1 dev notes]
- **Module boundary (AR-014):** keep ALL new ingest-worker code inside the telemetry module (`com.syncro.telemetry.application` / `.api`). Do NOT create `com.syncro.health` classes that reach into telemetry internals. The state enum carries its own label/severity constants (same strings as the shared taxonomy) instead of depending on `DependencyHealthSupport`. [Source: architecture.md AR-014, epics.md#AR-014]
- **Visibility constraint:** `/actuator/health` is `permitAll` + `show-details: always` for k8s liveness (story 6.1/5-9). Worker state must NEVER be added there — AC 5 requires SUPER_ADMIN-only. The protected endpoint is the correct home. [Source: SecurityConfig.java:35, spec-6-1 review decision]
- **Operational status contract:** response reuses the architecture field set `status`/`statusLabel`/`statusReason`/`statusSeverity`/`timestamp` (`architecture.md:643-663`), extended with worker-specific fields (`mqttState`, `lastAcceptedAt`, `staleSince`, `queueDepth`, `queueCapacity`, `acceptedCount`). `statusReason` is descriptive (protected endpoint) — unlike the sanitized public health reasons from the 6-1 review, raw broker `lastError` text is acceptable here because only SUPER_ADMIN can reach it.
- **Existing state sources (reuse, do not reinvent):** `MqttConnectionStatus.State` (`UNKNOWN`/`SUBSCRIBED`/`FAILED`, `lastError()`, `lastChange()`) in `MqttConnectionStatus.java:14-23`; `QueueChannel.getQueueSize()`/`getRemainingCapacity()` in `TelemetryIngestQueueConfig.java:21-29`; `TelemetryProperties.ingest().queueCapacity()`/`workerThreads()`. Do NOT add a second MQTT state tracker.
- **Worker "running" semantics:** the ingest pipeline is message-driven (`IntegrationFlow.from(adapter).channel(queue).handle(handler)` in `MqttSubscriptionConfig.java:53-61`), so "running" is derived from subscription state + telemetry freshness, not a thread liveness check. `MqttHealthIndicator` maps `UNKNOWN → DOWN` (FIXED contract, test `MqttHealthIndicatorTest.java:21-23`); the worker state may map `UNKNOWN → STOPPED` independently — do NOT change `MqttHealthIndicator`.
- **Staleness baseline:** `lastAcceptedAt` is in-memory (lost on restart → `null` → RUNNING awaiting first telemetry). This is acceptable: a restart restarts the accept clock. If durable staleness across restarts is later required, a Redis key would be the follow-up (defer — do NOT build now).
- **Known limitation to note (do not fix here):** per DW-14, `MqttConnectionStatus` stays `SUBSCRIBED` during a mid-session broker outage (Spring Integration MQTT 7.x publishes no disconnect event). The worker status therefore relies on the stale-telemetry signal (DEGRADED after `staleThreshold`) to surface such outages — which is exactly what AC 4 covers. Mention this in the story completion notes.
- **Testing standards:** follow the project's JUnit 5 + AssertJ + Mockito patterns; `Clock.fixed` for every timestamp assertion; `@WebMvcTest` controllers with the `user(...)`/`auth(...)` helper; never mock what can be a real bean cheaply (tracker with fixed clock).
- **Regression risks:**
  - `MqttTelemetryIngestHandler` constructor changes → update BOTH `MqttTelemetryIngestHandlerTest.java` and `MqttTelemetryIngestAtddScaffoldTest.java` if it constructs the handler; do not break the `enrichAssignsTraceId...`/log-assertion tests.
  - `TelemetryProperties.Ingest` record gains a field → any test constructing `new Ingest(...)` or `TelemetryProperties` positionally must be updated. Grep for `TelemetryProperties` usages in tests before building.
  - Do NOT break `MqttHealthIndicator` (UNKNOWN→DOWN) or the readiness group exclusion (`application.yml` `group.readiness.exclude: wahaCircuitBreaker`).
  - `MqttConnectionStatus` unchanged — only read (`state()`, `lastError()`).
- **What NOT to build:** no Flyway migration (in-memory status), no `/actuator/health` changes, no frontend changes, no `/api/v1/health/summary` (Story 6.4), no data-quality/rejection metrics (Story 6.7), no per-machine freshness aggregation (page-specs 4.5 → later story).

### Project Structure Notes

- New code lives in existing telemetry module packages (`application`, `api`) — no new top-level module; `health/` bounded context is intentionally NOT used for this story to honor AR-014 (the 6-1 indicators that cross modules already sit in their owning module: `mqtt` in `telemetry.infrastructure`, `waha` in `notification.infrastructure`).
- Endpoint path `/api/v1/telemetry/ingest/status` is telemetry-scoped; Story 6.4's `/api/v1/health/summary` will aggregate across modules.
- Severity strings duplicated as constants in `IngestWorkerState` (SUCCESS/CRITICAL/WARNING) intentionally — cross-module reuse of `DependencyHealthSupport` would violate AR-014.

### References

- Epic & story definition: [_bmad-output/planning-artifacts/epics.md#Story-6.2] (lines 975-987) + NFR-006 (health visibility to SUPER_ADMIN)
- Architecture System Health + worker status: [_bmad-output/planning-artifacts/architecture.md] (System Health bounded context ~943, 1251; operational status contract 643-663; AR-014 module boundaries)
- Page specifications Workers/Queue sections: [_bmad-output/planning-artifacts/page-specifications.md:448-453] (worker metrics) + [page-specifications.md] 4.7 Queue Status (~474-480)
- Ingest pipeline: [syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfig.java:53-61] + [MqttTelemetryIngestHandler.java:44-74] + [TelemetryIngestQueueConfig.java:14-30]
- MQTT state: [syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttConnectionStatus.java:14-23] + [MqttHealthIndicator.java] (do not break)
- Freshness thresholds: [syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryFreshnessCalculator.java:12-13]
- Config: [syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java] (Ingest record)
- SUPER_ADMIN guard pattern: [syncro/apps/backend/src/main/java/com/syncro/telemetry/api/TelemetryQuarantineController.java:44-50] + test helper [TelemetryQuarantineControllerTest.java:112-121]
- Previous story lineage: [spec-6-1-expose-dependency-health-checks.md] (Actuator health, DependencyHealthSupport, review sanitization decision, DW-14/DW-46..48 deferred work)
- Contract change discipline: [_bmad-output/planning-artifacts/architecture.md] "Contract, Seed, and Time Patterns" (~line 855)

## Dev Agent Record

### Agent Model Used

{{agent_model_name_version}}

### Debug Log References

### Completion Notes List

### File List
