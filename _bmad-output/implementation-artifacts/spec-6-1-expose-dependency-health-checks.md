---
baseline_commit: f13ba18558c05de390e02bb9d5731a474f0b83b3
---

# Story 6.1: Expose Dependency Health Checks

Status: review

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As a SUPER_ADMIN,
I want Syncro backend to check critical platform dependencies,
so that I can tell whether the system can ingest telemetry and send notifications.

## Acceptance Criteria

1. **Given** backend is running **When** health checks execute **Then** backend reports PostgreSQL connectivity [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]
2. **Given** backend is running **When** health checks execute **Then** backend reports InfluxDB connectivity [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]
3. **Given** backend is running **When** health checks execute **Then** backend reports Redis connectivity [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]
4. **Given** backend is running **When** health checks execute **Then** backend reports MQTT broker connectivity [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]
5. **Given** backend is running **When** health checks execute **Then** backend reports WAHA availability [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]
6. **Given** any dependency health is evaluated **When** its result is returned **Then** each health result includes `status`, `statusLabel`, `statusReason`, `statusSeverity`, `timestamp`, and `traceId` where applicable [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC, _bmad-output/planning-artifacts/architecture.md#Operational-status-responses]
7. **Given** a dependency is unreachable or times out **When** its health is checked **Then** the failure is reported as `DOWN`/`OUT_OF_SERVICE` with a reason and the backend does not crash [Source: _bmad-output/planning-artifacts/epics.md#Story-6.1-AC]

## Tasks / Subtasks

- [x] Task 1: Create `InfluxDbHealthIndicator` for InfluxDB v3 connectivity (AC: 2, 6, 7)
  - [x] New file: `syncro/apps/backend/src/main/java/com/syncro/health/InfluxDbHealthIndicator.java` (or `com.syncro.telemetry.infrastructure.InfluxDbHealthIndicator`) — implement `org.springframework.boot.health.contributor.HealthIndicator`, `@Component("influxdb")` so Actuator key is `components.influxdb` (frontend `useActuatorHealthQuery.ts:22` expects `influxdb`).
  - [x] Inject `InfluxDBClient` (bean from `InfluxDbConfig.java:11`) and `Clock`; on `health()` perform a bounded ping — prefer `client.ping()` if available in `influxdb3-java:1.10.0`, otherwise fallback to an HTTP `GET ${influx.url}/ping` (or `/health`) via `RestClient` with a 2s timeout. Return `Health.up()` with details on success; `Health.down(e)` with `statusReason`/`statusSeverity`/`timestamp` on any exception. Never throw — catch `Exception` and map to `DOWN`.
  - [x] Verify artifact via `spring-docs` MCP before implementing (Boot 4 `Health` API is `org.springframework.boot.health.contributor.*`, not `actuator.health`).

- [x] Task 2: Verify and align existing dependency indicators (AC: 1, 3, 4, 5, 6, 7)
  - [x] PostgreSQL: confirm Spring Boot auto-configured `DataSourceHealthIndicator` is active as `components.db` (requires `spring-boot-starter-data-jpa` + `spring-boot-starter-actuator` — already present). No code change unless a custom indicator is needed to add `statusLabel`/`statusSeverity`/`timestamp`. If auto `db` lacks the AC 6 enrichment, wrap or replace with a thin `DbHealthIndicator` that delegates to `DataSource` but enriches details (see Task 4 pattern).
  - [x] Redis: confirm auto `RedisHealthIndicator` as `components.redis` (requires `spring-boot-starter-data-redis` + actuator — already present). Same enrichment note as above.
  - [x] MQTT: inspect `MqttHealthIndicator.java:1-28` — today component key resolves to `mqtt` (bean `mqttHealthIndicator` → stripped suffix → `mqtt`), which matches frontend `components?.mqtt` (`types/index.ts:18`). Preserve this resolution; enrich `health()` to include `statusLabel`/`statusSeverity`/`timestamp`/`statusReason` (`lastError`) so the Actuator details carry AC 6 fields. Reuse existing `MqttConnectionStatus.java:11` (`State` UNKNOWN/SUBSCRIBED/FAILED, `lastError`, `lastChange`). Do NOT break the `FIXED` contract on `UNKNOWN → DOWN` (see `MqttHealthIndicatorTest.java:21-23`).
  - [x] WAHA: reuse `WahaCircuitBreakerHealthIndicator.java:19` (`@Component("wahaCircuitBreaker")` → `components.wahaCircuitBreaker`). Frontend catch-all `[key: string]` displays it. Verify it already reports `DOWN` when OPEN, `OUT_OF_SERVICE` when FORCED_OPEN/DISABLED, and exposes `state`, `failureRate`, `bufferedCalls`, `failedCalls`, `successfulCalls`, `notPermittedCalls`. Enrich it to also carry `statusLabel`/`statusSeverity`/`timestamp` (via `Clock`) on the same detail map so AC 6 passes for WAHA. Preserve readiness-group exclusion: `application.yml:group.readiness.exclude: wahaCircuitBreaker` must stay — WAHA unavailability must not flip `/ready` to DOWN [Story 5-9 decision D2(a)].

- [x] Task 3: Normalize Actuator health contract for all five dependencies (AC: 6)
  - [x] Introduce a shared enrichment helper (e.g., `HealthDetails` or `DependencyHealthSupport`) that maps raw `Health.Status` (`UP`/`DOWN`/`OUT_OF_SERVICE`/`UNKNOWN`) to canonical `statusLabel`/`statusSeverity` per architecture severity set (`architecture.md:656-663` → INFO/SUCCESS/WARNING/CRITICAL/NEUTRAL): `UP→SUCCESS`, `DOWN→CRITICAL`, `OUT_OF_SERVICE→WARNING`, `UNKNOWN→NEUTRAL`. Populate `timestamp` (ISO `Instant.now(clock)`) and where available `traceId`/`statusReason` (the last error or circuit state). Keep mapping pure-func and reusable across the five indicators; do NOT hardcode label strings in each indicator.
  - [x] Guarantee Actuator payload shape: with `management.endpoint.health.show-details=always` (already `application.yml:32`) each `components.<dep>` now has `{ status, details: { statusLabel, statusReason?, statusSeverity, timestamp, traceId? } }` satisfying AC 6 without a new REST DTO. Document in dev notes that additional `/api/v1/health/summary` aggregation for page-spec `GET /api/v1/health/summary` (Operations Overview Health Summary, System Health) is deferred to Story 6.4 — this story's scope is the Actuator dependency keys `db`, `redis`, `mqtt`, `influxdb`, `wahaCircuitBreaker`.

- [x] Task 4: Wire `health/` bounded context packaging (AC: 1-5, project-context)
  - [x] Place any new indicators that belong to `health` in `com.syncro.health` per `architecture.md:1251` (`Backend: health/`). InfluxDB can stay in `telemetry.infrastructure` if domain ownership is clearer, but the story's authoritative health package is `com.syncro.health` (see `architecture.md:943` System Health → `health/`, `apps/web/src/features/system-health/`). Do not create a `health` package in the web app — frontend already has `features/system-health` (committed 8/14-8/18).
  - [x] Do NOT move `WahaCircuitBreakerHealthIndicator` or `MqttHealthIndicator` to `domain`; both are `@Component` infrastructure adapters (health integration package per project-context).

- [x] Task 5: Verify Actuator and Security exposure (AC: 7 + project-context)
  - [x] `application.yml:27` already `include: health,info` and `SecurityConfig.java:35` `permitAll()` for `/actuator/health` and `/api/v1/health`. Preserve. Do NOT expose `metrics/env/beans` — project-context requires actuator exposure minimal.
  - [x] Existing `HealthController.java:11` (`GET /api/v1/health` → `{status, service, timestamp}`) is the legacy unauthenticated liveness endpoint; keep it. Do NOT overload it with dependency details (that lives in `/actuator/health` for this story). The richer `/api/v1/health/summary` is deferred to 6.4.
  - [x] Confirm `management.endpoint.health.group.readiness.exclude: wahaCircuitBreaker` from 5-9 fix remains; keep `diskSpace` and `ping` as defaults (frontend types include them but story does not require changes).

- [x] Task 6: Make dependency failures non-fatal (AC: 7)
  - [x] Each `HealthIndicator.health()` must catch `Exception` and return `Health.down(e)` (or `outOfService()`) with truncated reason — never propagate. Verify the pattern already in `WahaCircuitBreakerHealthIndicator.java:34-47` and `InfluxDbHealthIndicator` new code uses it. InfluxDB/Redis/DB auto indicators already do this.

- [x] Task 7: Write tests (AC: 1-7)
  - [x] New: `InfluxDbHealthIndicatorTest.java` — mock `InfluxDBClient` returning success → `UP` with details `statusLabel`/`statusSeverity`/`timestamp`; mock throwing `IOException` → `DOWN` with `statusReason` truncated and correct severity; verify `timestamp` present.
  - [x] Update-or-keep: `MqttHealthIndicatorTest.java:14-51` already covers SUBSCRIBED→UP, FAILED→DOWN with `lastError`; add (or extend) assertions for enriched `statusLabel`/`statusSeverity`/`timestamp`.
  - [x] Keep: `WahaCircuitBreakerHealthIndicatorTest.java` — CLOSED→UP, OPEN→DOWN, FORCED_OPEN→OUT_OF_SERVICE, metrics exposed; add assertions for enriched label/severity/timestamp if not already.
  - [x] New integration-style: `ActuatorHealthIntegrationTest.java` (or extend existing health slice) with `@SpringBootTest` + `MockMvc` or `TestRestTemplate` hitting `GET /actuator/health` and asserting `components` contains keys `db`, `redis`, `mqtt`, `influxdb`, `wahaCircuitBreaker`, each with `status` and `details.statusSeverity`/`details.timestamp` (AC 6) and that a simulated InfluxDB/Redis failure still returns `200` with a `DOWN` subcomponent rather than a `500` crash (AC 7). Use `MockBean` to stub `InfluxDBClient`/mqtt status as in `MqttHealthIndicatorTest`.

## Dev Notes

### Previous Story Intelligence (Story 5-9 — Circuit Breaker) — MUST preserve

- **Waha resilience:** `ResilienceConfig.java:8` provides `CircuitBreakerRegistry` bean (`ofDefaults()`). `WahaClient.java:48` builds `CircuitBreakerConfig` from `WahaResilienceProperties.java:21` (typed `@ConfigurationProperties(prefix = "syncro.waha.resilience")`) and uses `CircuitBreaker.executeSupplier(() -> doSend(...))`; `CallNotPermittedException` fast-fails to `Result(false, 0, "Circuit breaker is OPEN")`; `WahaCircuitBreakerHealthIndicator.java:19` is `@Component("wahaCircuitBreaker")` using Spring Boot 4 `org.springframework.boot.health.contributor.Health` (not `actuator.health`). `application.yml` has `syncro.waha.resilience` block (`timeout: 5000ms`, `failure-rate-threshold: 50`, `minimum-number-of-calls: 5`, `sliding-window-size: 10`, `wait-duration-in-open-state: 60000ms`, `permitted-calls-in-half-open: 3`) and `ResilienceConfig` + `management.endpoint.health.group.readiness.include/exclude`. `WahaClientCircuitBreakerTest.java` was rewritten with real JDK `HttpServer` (not `MockRestServiceServer` — `RestClient` builds its own `SimpleClientHttpRequestFactory` so mock server cannot bind). Keep this wiring; do NOT add a second `CircuitBreakerRegistry` or move config to `resilience4j.*` YAML. [Source: spec-5-9:231-238, ResilienceConfig.java:8, WahaClient.java:48, WahaCircuitBreakerHealthIndicator.java:19, application.yml:group.readiness]
- **Health readiness exclusion:** `WahaCircuitBreakerHealthIndicator` reports `DOWN` when OPEN; per story 5-9 decision D2(a) the readiness group explicitly EXCLUDES `wahaCircuitBreaker` (`management.endpoint.health.group.readiness.include: "*", exclude: "wahaCircuitBreaker"`) so WAHA outage does NOT flip `/actuator/health/readiness` to DOWN. Preserve this group config — Story 6.1 must not re-include `wahaCircuitBreaker` in readiness. [Source: spec-5-9 D2(a), application.yml:group.readiness, WahaCircuitBreakerHealthIndicator.java:34-47]
- **Secret redaction:** WAHA `apiKey` never appears in logs/errors; phone numbers are DEBUG only; health details must not log secrets. Keep `WahaClient.java:57-60` header handling and `WahaCircuitBreakerHealthIndicator` metric-only payload. [Source: project-context.md, WahaClient.java:57]
- **Duration validation:** `WahaResilienceProperties.java:60` compact constructor rejects zero/negative `timeout` and `waitDurationInOpenState`. Add analogous validation if Story 6.1 introduces new `Duration` config. [Source: WahaResilienceProperties.java:60]
- **Tests:** `WahaClientCircuitBreakerTest.java` now has 11 real-HTTP tests (initial state, 500 opens, 400 does-not-open, forced-open, connection-refused, timeout-bounded, half-open probe). Keep passing. [Source: spec-5-9 Review Findings]

### Architecture & Project-Context Guardrails

- **Bounded-context placement:** `architecture.md:1251` and `architecture.md:943` define `System Health` as backend `health/` — this story owns `com.syncro.health` for the new indicator/service; frontend is `apps/web/src/features/system-health/` (already committed 8/14-8/18 with `system-health-page.tsx:91-141`, `useActuatorHealthQuery.ts:9-29`, `types/index.ts:1-23`). Do NOT create `src/components/syncro/health-card.tsx` in this story — it exists only as an architecture placeholder. The existing page already renders `components.db`, `components.redis`, `components.mqtt`, `components.influxdb` plus `wahaCircuitBreaker` catch-all. Keep actuator keys stable. [Source: architecture.md:1251, architecture.md:943, system-health-page.tsx:91-141, types/index.ts:1-23]
- **Status response contract:** `architecture.md:643-663` canonical operational-status response shape is `{ status, statusLabel, statusReason, statusSeverity, timestamp, allowedActions }` with severities `INFO | SUCCESS | WARNING | CRITICAL | NEUTRAL`. AC 6 extends this to include `traceId` where applicable. Map `Health.Status` → `statusSeverity`/`statusLabel` uniformly via the helper in Task 3; do not invent new severity strings. [Source: architecture.md:643-663]
- **Health exposure medium:** `architecture.md:464` — "Health checks exposed through Spring Actuator plus custom dependency checks." For 6.1 the Actuator is the authoritative transport (`/actuator/health`). The enriched `/api/v1/health/summary` (`page-specifications.md:64` visible on Operations Overview) is deferred to Story 6.4 — do not create it here (avoid scope creep into dashboard). Keep `HealthController.java:11` (`GET /api/v1/health` → `{status, service, timestamp}`) as the legacy liveness probe; it stays `permitAll()` in `SecurityConfig.java:35`. [Source: architecture.md:464, page-specifications.md:64, HealthController.java:11, SecurityConfig.java:35]
- **Security:** `project-context.md:40,114` — actuator exposure must stay minimal (`include: health,info`), `show-details: always` is already set. Do NOT expose `metrics/env/beans/mappings` publicly. `/actuator/health` is `permitAll()` for k8s probes; dependency checks themselves must not require SUPER_ADMIN in 6.1 (visibility restriction for worker telemetry/staleness is 6.2/6.3). [Source: SecurityConfig.java:35, application.yml:27]
- **Layer direction:** `domain` must not import Spring/JPA/Jackson/web DTOs; `application` must not depend on controllers/raw entities. Health indicators belong in `health` (or `telemetry/infrastructure` for MQTT/InfluxDB) as Spring `@Component`s — not in `domain`. [Source: project-context.md#Layer-direction]
- **Failure isolation:** `HealthIndicator.health()` must never throw — wrap every external call (`DataSource`, Redis `PING`, MQTT status read, InfluxDB ping, circuit-breaker metrics) in `try/catch (Exception)` and return `Health.down()`/`outOfService()` with `withDetail("error", truncate(msg))`. The backend process must stay `UP` at the top level even when a sub-component is `DOWN` (aggregate status is derived; no crash). [Source: epics.md AC 7]
- **Typed config:** `project-context.md` — prefer typed `*Properties` with validation over scattered `@Value`. Follow `WahaResilienceProperties.java:21` / `WahaProperties.java` / `InfluxProperties.java:9` record style if any timing/threshold config is needed for InfluxDB ping. [Source: WahaResilienceProperties.java:21, InfluxProperties.java:9]
- **Correlation ID:** every request/ingest/job/audit/health change should propagate `traceId` where available (`project-context.md`). If a health check has a trace context, put `traceId` in details; otherwise omit — do not fabricate. [Source: architecture.md:Communication-Patterns]

### Git Intelligence (last 5 commits)

- `f13ba18 fix(5-9): apply code review patches — circuit-open no longer burns attempts, readiness group exclusion, 4xx excluded from circuit, real-HTTP tests, Registry bean, duration validation` — adds `ResilienceConfig`, rewrites `WahaClientCircuitBreakerTest` with `HttpServer`, adds `markCircuitOpen` on `NotificationJobEntity`, removes worker early-exit. Pattern: typed props, in-memory circuit, programmatic CB, JDK `HttpServer` for resilience testing. Reuse. [Source: git log --oneline -8]
- `71de230 feat(5-9): implement circuit breaker and timeout for WAHA calls` — introduces `WahaResilienceProperties`, `WahaCircuitBreakerHealthIndicator`, `SimpleClientHttpRequestFactory` timeouts, `NotificationDispatchService` circuit-open branching. Pattern: Spring Boot 4 `health.contributor` API. Keep. [Source: git log]
- `782e322 Merge 5-8 rate-limiting into main` / `26b89f9 story 5-8` — typed `WahaRateLimitProperties`, Redis `SET NX PX`, `V29` CHECK migration. `NotificationJobRepository.java:38` poll query `DISPATCHABLE_STATUSES` handling preserved. Do not regress. [Source: git log]
- Baseline note from spec-5-9: `JwtTokenService.java` missing symbol is pre-existing broken baseline in older branches — isolate with scoped `mvn -Dtest="*Health*"` and do not treat as your regression. [Source: spec-5-9 Dev Notes]

### Files To Modify vs Create (Authoritative)

**UPDATE (read fully before editing — preserve behavior):**

- `syncro/apps/backend/src/main/resources/application.yml:23-32` — verify `management.endpoints.web.exposure.include: health,info`, `management.endpoint.health.probes.enabled: true`, `show-details: always`, and `group.readiness.exclude: wahaCircuitBreaker`. Do NOT add new `resilience4j.*` YAML; readiness group must keep the 5-9 exclusion.
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttHealthIndicator.java:8` — enrich `health()` to carry `statusLabel`/`statusSeverity`/`timestamp` (`Clock`) and `statusReason`. Keep `@Component` (implicit `mqttHealthIndicator` → actuator key `mqtt`) and do NOT rename to `@Component("mqtt")` unless frontend key mismatch is proven (current stripping suffix already resolves to `mqtt` — verify before renaming).
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java:20-48` — enrich to carry `statusLabel`/`statusSeverity`/`timestamp` alongside existing `state`, `failureRate`, `bufferedCalls`, `failedCalls`, `successfulCalls`, `notPermittedCalls`. Keep `@Component("wahaCircuitBreaker")`, `health.contributor` import, and readiness exclusion.
- `syncro/apps/backend/src/main/java/com/syncro/api/HealthController.java:11` — leave as-is (`GET /api/v1/health` liveness `{status, service, timestamp}`). Do NOT overload this endpoint with dependency details; dependency data lives in `/actuator/health` for this story.
- `syncro/apps/backend/src/main/java/com/syncro/config/SecurityConfig.java:35` — keep `permitAll()` for `/actuator/health` and `/api/v1/health`; do NOT broaden actuator exposure.
- `syncro/apps/web/src/features/system-health/types/index.ts:12-23` — do NOT modify in this story (frontend consumption is already `components.db`/`redis`/`mqtt`/`influxdb` + catch-all `wahaCircuitBreaker`). Only update if actuator key naming is proven wrong (then document).

**CREATE (new):**

- `syncro/apps/backend/src/main/java/com/syncro/health/InfluxDbHealthIndicator.java` — `@Component("influxdb")`, `HealthIndicator`, `Clock`, `InfluxDBClient`, bounded 2s ping/`client.ping()` with `Health.up().withDetail("statusLabel", ...)...withDetail("timestamp", ...)`.
- `syncro/apps/backend/src/main/java/com/syncro/health/DependencyHealthSupport.java` (or `HealthDetails.java`) — shared `status → {statusLabel, statusSeverity}` mapper and helpers (`withTimestamp`, `withTraceId`, `truncate`) reused by all five indicators.
- `syncro/apps/backend/src/test/java/com/syncro/health/InfluxDbHealthIndicatorTest.java` — `UP`/`DOWN`/`statusSeverity`/`timestamp` assertions with mocked `InfluxDBClient`.
- `syncro/apps/backend/src/test/java/com/syncro/health/ActuatorHealthIntegrationTest.java` — slice or `@SpringBootTest` asserting `GET /actuator/health` returns `components` keys `db`, `redis`, `mqtt`, `influxdb`, `wahaCircuitBreaker` and that a stubbed failure still returns HTTP 200 with the subcomponent `DOWN` (AC 7). Scope with `MockitoBean` for `InfluxDBClient`/redis as needed; do NOT require InfluxDB/Redis containers for this test.

**DO NOT CREATE:**

- New `/api/v1/health/summary` or `/api/v1/health/dependencies` REST endpoint — that enriched aggregate API belongs to Story 6.4 (health dashboard). This story's deliverable is the enriched Actuator components `db`/`redis`/`mqtt`/`influxdb`/`wahaCircuitBreaker` with AC 6 details. If a summary controller is added, it must be behind `SUPER_ADMIN` and deferred.
- New Flyway migration — health checks are runtime-only; no DB schema change.
- Frontend changes — `system-health-page.tsx` + `useActuatorHealthQuery.ts` are already wired; any backend key rename must remain backward-compatible with `types/index.ts`.

### What Must Be Preserved (Regression Risks)

- **Actuator contract for frontend:** The page at `syncro/apps/web/src/features/system-health/components/system-health-page.tsx:91-141` reads `actuatorHealth.data.components.db/.redis/.mqtt/.influxdb` and the catch-all `wahaCircuitBreaker`. Changing a component key (e.g., renaming `mqtt` or `influxdb`) breaks the dashboard cards — update both sides atomically if a rename is required and document in the story. [Source: system-health-page.tsx:98-124, types/index.ts:15-22]
- **Readiness isolation:** `WahaCircuitBreakerHealthIndicator` must stay excluded from the readiness group (`application.yml` `group.readiness.exclude: wahaCircuitBreaker`) — Story 6.1 must not regress 5-9's D2(a) isolation. [Source: spec-5-9 D2]
- **Existing indicators:** `MqttConnectionStatus.java:11` event model (`UNKNOWN→SUBSCRIBED`/`FAILED` with `lastError`/`lastChange`) and `MqttHealthIndicatorTest.java:21-51` expectations (`UNKNOWN→DOWN`, `SUBSCRIBED→UP`, `FAILED→DOWN` with `lastError`, recovery clears `lastError`) must still pass after enrichment. Do not reset `lastError` semantics. [Source: MqttHealthIndicatorTest.java:21-51]
- **Circuit breaker state as WAHA availability:** `WahaCircuitBreakerHealthIndicatorTest.java` expects CLOSED→UP, OPEN→DOWN, FORCED_OPEN→OUT_OF_SERVICE with metrics `failureRate`/`bufferedCalls`/`failedCalls`/`successfulCalls`/`notPermittedCalls`. Preserve and enrich, do not replace. [Source: WahaCircuitBreakerHealthIndicatorTest.java]
- **Security posture:** `SecurityConfig.java:35` `permitAll()` for `/actuator/health` is intentional for k8s liveness; do not broaden to `/actuator/*` or add new authenticated endpoints without explicit super-admin guard. [Source: SecurityConfig.java:35]
- **Time control:** use injected `Clock` (from `TimeConfig.java:10` `Clock.systemUTC()`) for every `timestamp` so tests can assert with `Clock.fixed()`. Do not call `Instant.now()` directly in indicators.

### Latest Technical Specifics (Spring Boot 4.0.6 + InfluxDB v3)

- **Spring Boot 4.0.6 `HealthIndicator` API:** `org.springframework.boot.health.contributor.Health` (`Health.up()`, `Health.down()`, `Health.outOfService()`, `.withDetail(k, v)`, `.build()`). Check is via `org.springframework.boot.health.contributor.Status` (`UP`, `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN`). The actuator auto-discovers every `HealthIndicator`/`HealthContributor` bean; the component key is the bean name with suffix `HealthIndicator`/`HealthContributor` stripped (`mqttHealthIndicator` → `mqtt`, `influxDbHealthIndicator` → `influxDb` — so use `@Component("influxdb")` explicitly to force lowercase `influxdb`). Verify via `spring-docs` MCP before coding. [Source: project-context.md:Documentation MCP Reference]
- **InfluxDB v3 ping:** `influxdb3-java:1.10.0` `InfluxDBClient` has no standardized Spring health indicator. Verify the client API first: try `client.ping()` (returns `boolean` or throws); if absent, fall back to `RestClient` `GET ${influxProperties.url()}/ping` (or `/health`) with the Influx token header and a 2s `SimpleClientHttpRequestFactory` timeout. The health check itself must have a 2s overall timeout and must never propagate — `catch (Exception e) { return Health.down(e).withDetail("statusReason", truncate(e.getMessage()))... }`. Do NOT add InfluxDB credentials to details. [Source: pom.xml:influxdb3-java, InfluxProperties.java:9, InfluxDbConfig.java:11]
- **DataSource/Redis auto-indicators:** Spring Boot already exposes `db` (HikariCP validation query) and `redis` (`RedisConnectionFactory` `PING`). No custom `@Component` is needed unless enrichment (statusSeverity) demands a delegating wrapper. Prefer a thin decorator only if the auto indicator cannot carry AC 6 `statusSeverity`/`timestamp` details without replacing it.
- **Tracer:** `traceId` propagation — where available from `MDC`/request `X-Trace-Id`, add to health details; otherwise omit. Do not fabricate or log sensitive payloads.

### Testing Requirements

- Follow `project-context.md:146-170` testing rules: map each AC to at least one test; prefer few high-signal tests; use slices (`@WebMvcTest`, `HealthContributor` unit tests) over booted context where possible, and Testcontainers only when touching `notification_jobs`/`audit` tables.
- **Unit tests:** `InfluxDbHealthIndicatorTest` (mock `InfluxDBClient` → `UP`/`DOWN`/`timestamp` presence/`statusSeverity` mapping), `MqttHealthIndicatorTest` extension (assert `statusLabel`/`statusSeverity` on top of existing 4 tests), `WahaCircuitBreakerHealthIndicatorTest` extension (assert enriched fields on top of 4 tests).
- **Integration/slice test:** `ActuatorHealthIntegrationTest` exercises `GET /actuator/health` with `show-details=always` and asserts the five keys (`db`, `redis`, `mqtt`, `influxdb`, `wahaCircuitBreaker`) exist, each with `status` and `details.statusSeverity`/`details.timestamp`, and that forcing a stubbed InfluxDB failure still returns HTTP 200 (aggregate `DOWN`) rather than a crash — proves AC 7.
- **Negative/edge tests required (AC 7):** InfluxDB unreachable (`ConnectException`) → `DOWN` with `statusSeverity=CRITICAL` and `statusReason` truncated (no token); Redis/mock failure → `DOWN`; MQTT `UNKNOWN` → `DOWN` (preserve); `traceId` absent → details omit `traceId` (do not emit empty string). Dependency failure must not cause HTTP 500 on `/actuator/health`.
- **Do NOT** run full `mvn test` expecting green baseline — pre-existing `WahaRateLimiterTest` strict-stubbing errors are outside story scope (fixed baseline noted in `spec-5-9` review). Scope assertions to touched files: `mvn -Dtest="*Health*,*Influx*"` and file inspection.

## Project Structure Notes

- **Alignment:** New backend code under `syncro/apps/backend/src/main/java/com/syncro/health/` as the System Health bounded context per `architecture.md:1251` (`Backend: health/`). This story touches only backend (`health` + `telemetry.infrastructure` + `notification.infrastructure` for enrichments) — it does NOT add frontend work (Epic 6 story 6.4 will consume the health API; `features/system-health` already exists with `system-health-page.tsx` and `useActuatorHealthQuery.ts`). Follows `project-context.md:72-75` `com.syncro.<context>.<layer>` and `architecture.md` Project Structure. [Source: _bmad-output/planning-artifacts/architecture.md:1251]
- **Module boundaries:** `health` owns dependency availability; `telemetry` owns MQTT ingest internals (`MqttConnectionStatus`/`MqttHealthIndicator`); `notification` owns WAHA circuit state. Cross-module access via indicator aggregation only — do NOT inject `InfluxDBClient` outside `health`/`telemetry`, do NOT inject `MqttConnectionStatus` into `health` (keep Mqtt health in `telemetry`). [Source: architecture.md:Module-boundaries]
- **Config separation:** `com.syncro.config` holds typed props (`WahaResilienceProperties`, `InfluxProperties`, `MqttProperties`). No new dedicated health props unless InfluxDB ping timeout needs overriding (use `${SYNCRO_INFLUXDB_HEALTH_TIMEOUT_MS:2000}ms` via `application.yml` if added). Follow record + `@ConfigurationProperties` pattern from `WahaResilienceProperties.java:21`. [Source: WahaResilienceProperties.java:21, InfluxProperties.java:9]
- **Flyway:** No migration for this story — health snapshot persistence is out of scope (architecture lists `health snapshots` as system-of-record but Story 6.1 is stateless dependency checks; snapshots belong to a later health `V30__` if introduced by a subsequent story). Do NOT edit applied `V24-V29`. [Source: project-context.md:Flyway]
- **Frontend:** No frontend change required in this story except ensuring types stay aligned — `ActuatorHealthResponse.components.influxdb` key will become populated once `InfluxDbHealthIndicator` is `@Component("influxdb")`. Do NOT touch `components/syncro` or `features/alerts` — backend-only story except health API contract consumed by the existing `system-health-page.tsx`. [Source: types/index.ts:12-23, system-health-page.tsx:91-141]
- **Conflicts/variances:** Story 6.1 is backend-only; system-health frontend (`useActuatorHealthQuery.ts:9`, `system-health-page.tsx:91`) is already committed (dates 8/14-8/18). Variance is benign: backend must catch up to populate the actuator keys the UI already renders and fall back gracefully when a key is absent ("Unknown health data" path). Note this in dev notes. [Source: system-health-page.tsx git dates]

### References

- Epic & story definition: [_bmad-output/planning-artifacts/epics.md#Epic-6-System-Health-&-Operational-Diagnostics] (lines 966-987) + Story 6.1 lines 970-987
- NFR-006 (health visibility): [_bmad-output/planning-artifacts/epics.md#NFR-006]
- FR-057..FR-065: [_bmad-output/planning-artifacts/epics.md#FR-057..065] + AC lineage for PostgreSQL/Redis/InfluxDB/MQTT/WAHA
- AR-010 (health snapshots): [_bmad-output/planning-artifacts/epics.md#AR-010] + `_bmad-output/planning-artifacts/architecture.md:348`
- Architecture System Health bounded context: [_bmad-output/planning-artifacts/architecture.md#System-Health] (lines 943, 1251) + Health checks via Actuator + custom dependency checks (line 464)
- Operational status response contract: [_bmad-output/planning-artifacts/architecture.md:643-663] (`status`/`statusLabel`/`statusReason`/`statusSeverity`/`timestamp`/`allowedActions`)
- Page specifications health summary: [_bmad-output/planning-artifacts/page-specifications.md:64] `/api/v1/health/summary` + System Health page spec (lines 381-446, dependencies table)
- Current WAHA health: [syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java:19-48] + [WahaCircuitBreakerHealthIndicatorTest.java] + 5-9 fix `f13ba18`
- Current MQTT health: [syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttHealthIndicator.java:8] + [MqttConnectionStatus.java:11] + [MqttHealthIndicatorTest.java:14-51]
- Current health baselines: [syncro/apps/backend/src/main/java/com/syncro/api/HealthController.java:11] + [SecurityConfig.java:35] (`permitAll` for `/api/v1/health`, `/actuator/health`) + [application.yml:23-32] (`include: health,info`, `probes.enabled: true`, `show-details: always`, `group.readiness.exclude: wahaCircuitBreaker`)
- InfluxDB integration: [syncro/apps/backend/src/main/java/com/syncro/config/InfluxProperties.java:9] + [InfluxDbConfig.java:11] + `influxdb3-java:1.10.0` [pom.xml:85]
- Project context rules: [_bmad-output/project-context.md] Technology Stack, Documentation MCP Reference, Critical Implementation Rules, Testing Rules
- Frontend contracts: [syncro/apps/web/src/features/system-health/types/index.ts:1-23] (`ActuatorHealthComponent` + `ActuatorHealthResponse`) + [syncro/apps/web/src/features/system-health/components/system-health-page.tsx:91-141] + [syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.ts:9-29]
- Previous story lineage: [spec-5-9-implement-circuit-breaker-for-waha-calls.md#AC] (health readiness exclusion, Resilience4j patterns) + [spec-5-8-implement-waha-rate-limiting.md] (typed props, Redis pattern)
- Migration history: [syncro/apps/backend/src/main/resources/db/migration/V24__create_notification_jobs.sql] .. [V29] + [application.yml:group.readiness] (5-9 patch)
- Frontend system-health package already committed (git dates 8/14-8/18) — backend-first variance noted [git log --oneline -8, `Get-ChildItem system-health`]

## Dev Agent Record

### Agent Model Used

deepseek-v4-flash-free (openagentic), 2026-08-21

### Debug Log References

- Verified Spring Boot 4.0.6 health API via `javap` on `spring-boot-health-4.0.6.jar`: `Health`/`HealthIndicator`/`Status`/`Builder` in `org.springframework.boot.health.contributor.*`; name generator strips `HealthIndicator`/`HealthContributor` suffixes.
- Verified auto contributor keys: `DataSourceHealthContributorAutoConfiguration` → bean `dbHealthContributor` → `components.db`; `DataRedisHealthContributorAutoConfiguration` → bean `redisHealthContributor` → `components.redis`; both gated by `@ConditionalOnEnabledHealthIndicator` → disabled via `management.health.db.enabled`/`management.health.redis.enabled`.
- Verified `influxdb3-java:1.10.0` via `javap`: `InfluxDBClient` has NO `ping()` method (interface exposes write/query/getServerVersion only) → used the story's RestClient HTTP `/ping` fallback with 2s timeout.
- Full-suite regression check: baseline (git stash) vs. implementation produce identical failures (Failures: 2, Errors: 134) — all pre-existing (`RootAllocator` init under Java 25 surefire without `--sun-misc-unsafe-memory-access=allow`, plus mock strictness issues in unrelated tests). No new regressions.

### Completion Notes List

- **AC 1-5:** All five dependency health components exposed via Actuator: `db` (`DbHealthIndicator`), `influxdb` (`InfluxDbHealthIndicator`), `redis` (`RedisHealthIndicator`), `mqtt` (`MqttHealthIndicator` enriched), `wahaCircuitBreaker` (`WahaCircuitBreakerHealthIndicator` enriched).
- **AC 6:** `DependencyHealthSupport` (com.syncro.health) normalizes every component's details with `statusLabel`, `statusSeverity`, `timestamp` and optional `statusReason`/`traceId`. Mapping: UP→SUCCESS, DOWN→CRITICAL, OUT_OF_SERVICE→WARNING, UNKNOWN→NEUTRAL. All timestamps use the injected `Clock` bean (no `Instant.now()` direct calls).
- **AC 7:** Every indicator catches `Exception` and returns `Health.down(e)`/`outOfService()` with a reason — never propagates. Verified by unit tests + `ActuatorHealthIntegrationTest` (influxdb unreachable + mqtt UNKNOWN produce DOWN subcomponents and a structured 503 response, not a crash).
- **Variance noted (benign):** The integration test asserts HTTP 503 when the aggregate health is DOWN — Spring Boot Actuator maps DOWN→503 by default (`status.http-mapping`), which is the standard non-crash outcome; the story's Task 7 text assumed 200 but the payload shape and DOWN reporting are the actual AC 7 contract. Frontend `useActuatorHealthQuery` already treats non-OK responses via its `retry`/error path.
- **Variance noted:** `InfluxDbHealthIndicator` injects `InfluxProperties` + `Clock` (not `InfluxDBClient`) because the v3 client has no `ping()` — the RestClient `/ping` fallback is the story-sanctioned path.
- `traceId` is omitted from health details because simple dependency pings have no distributed-trace context ("where applicable" per AC 6); the helper supports it for later stories.
- `/api/v1/health/summary` aggregation intentionally NOT built — deferred to Story 6.4 per dev notes.
- No Flyway migration, no frontend changes, no new actuator endpoints — scope held to the five Actuator component keys.

### File List

- [modified] `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttHealthIndicator.java` — enriched with `DependencyHealthSupport` + `Clock`; UNKNOWN→DOWN contract preserved.
- [modified] `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java` — enriched with `statusLabel`/`statusSeverity`/`timestamp`/`statusReason`; readiness-group exclusion preserved.
- [modified] `syncro/apps/backend/src/main/resources/application.yml` — added `management.health.db.enabled: false` and `management.health.redis.enabled: false` to replace auto indicators with enriched ones.
- [new] `syncro/apps/backend/src/main/java/com/syncro/health/DependencyHealthSupport.java` — shared AC 6 enrichment helper.
- [new] `syncro/apps/backend/src/main/java/com/syncro/health/DbHealthIndicator.java` — `@Component("db")`, `DataSource.isValid(2)`.
- [new] `syncro/apps/backend/src/main/java/com/syncro/health/RedisHealthIndicator.java` — `@Component("redis")`, `RedisConnection.ping()`.
- [new] `syncro/apps/backend/src/main/java/com/syncro/health/InfluxDbHealthIndicator.java` — `@Component("influxdb")`, RestClient `/ping` with 2s timeout.
- [modified] `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttHealthIndicatorTest.java` — extended with AC 6 field assertions.
- [modified] `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicatorTest.java` — extended with AC 6 field assertions.
- [new] `syncro/apps/backend/src/test/java/com/syncro/health/DependencyHealthSupportTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/health/DbHealthIndicatorTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/health/RedisHealthIndicatorTest.java`
- [new] `syncro/apps/backend/src/test/java/com/syncro/health/InfluxDbHealthIndicatorTest.java` — `MockRestServiceServer`-based.
- [new] `syncro/apps/backend/src/test/java/com/syncro/health/ActuatorHealthIntegrationTest.java` — minimal-context `@SpringBootTest` + `MockMvc` hitting `/actuator/health`.

## Change Log

- 2026-08-21: Implemented story 6.1 — five enriched Actuator dependency health components; 29 health tests pass; no regressions vs. baseline.
- 2026-08-21: Applied code-review fixes — sanitized `statusReason` to stable reason codes on the public endpoint (decision), fixed MQTT volatile double-read race (patch); 31 health tests pass.

### Review Findings

- [x] [Review][Decision] statusReason / error detail on unauthenticated `/actuator/health` exposes internal exception text (hostnames/URLs) — spec mandates `statusReason` from `e.getMessage()`, but the endpoint is `permitAll` + `show-details: always`; sanitize to stable reason codes or keep raw? — **RESOLVED 2026-08-21: user chose sanitize.** Added `DependencyHealthSupport.reasonCode(Exception)` mapping failures to stable codes (TIMEOUT, CONNECTION_REFUSED, NETWORK_UNREACHABLE, DNS_FAILURE, UNAUTHORIZED, RATE_LIMITED, SERVER_ERROR, CONNECTION_FAILED). Indicators now emit `Health.down()` (no raw `error` detail) + sanitized `statusReason`; full exception logged at WARN. MQTT `statusReason` uses stable codes `MQTT_CONNECTION_FAILED`/`MQTT_NOT_SUBSCRIBED`.
- [x] [Review][Patch] MqttHealthIndicator double-reads volatile `status.lastError()` — a concurrent reconnect can clear it between the null-guard and `withDetail`, and Spring Boot 4 `Health.Builder.withDetail` asserts non-null → `IllegalArgumentException` → 500, violating AC 7 — **FIXED: single local read of `lastError`.**
- [x] [Review][Defer] DbHealthIndicator `dataSource.getConnection()` can block up to Hikari connection-timeout (default 30s) — no overall probe bound; matches Spring Boot's own `DataSourceHealthIndicator` behavior [DbHealthIndicator.java:33] — deferred, pre-existing framework pattern (DW-46)
- [x] [Review][Defer] RedisHealthIndicator `connection.ping()` inherits Lettuce command timeout (default 60s), unlike the 2s contract of db/influx — matches the replaced auto `redisHealthContributor` behavior [RedisHealthIndicator.java:31-32] — deferred, pre-existing framework pattern (DW-47)
- [x] [Review][Defer] MqttHealthIndicator stays UP during a mid-session broker outage (Spring Integration 7.x no disconnect event) — documented `TODO (DW-14)` in `MqttConnectionStatus`; pre-existing, not introduced by this story [MqttConnectionStatus.java:37-44] — deferred, pre-existing (DW-48)
