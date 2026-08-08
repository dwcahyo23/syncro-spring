---
title: '3-1 Configure MQTT Subscription and Telemetry Contract'
type: 'feature'
created: '2026-08-08'
status: 'done'
baseline_revision: 'b4109d942338d4cf33a80d4cd631000126cbaeff'
final_revision: '45f7a7a'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
operator_actions:
  - 'Start local infra: `docker compose up -d` from `syncro/infra` (Postgres, Redis, InfluxDB, EMQX, WAHA).'
  - 'Boot backend with `SPRING_PROFILES_ACTIVE=local`; confirm clean boot and an INFO log line showing the MQTT subscription request for the configured topic filter (e.g. `factory/+/+/telemetry`).'
  - 'Publish one JSON payload to `factory/GM1/BF-08410/telemetry` via an MQTT client (e.g. MQTTX or mosquitto_pub) while the backend is running; confirm a backend INFO log line appears containing the `topic` and a non-empty `traceId`.'
  - 'Call `GET /actuator/health`; confirm an `mqtt` component is present (UP when connected/subscribed). Stop EMQX and confirm `mqtt` becomes DOWN.'
---

<intent-contract>

## Intent

**Problem:** Syncro has EMQX running locally, `syncro.mqtt.*` env values bound, and a `spring-integration-mqtt` dependency, but nothing subscribes to the machine telemetry topic, so machines cannot push telemetry into the system and no traceId exists at the ingest boundary for end-to-end correlation.

**Approach:** Add a `com.syncro.telemetry` module that builds an auto-starting `MqttPahoMessageDrivenChannelAdapter` (Spring Integration MQTT v3, Paho client) from `MqttProperties` (host/port/username/password/clientId/topicFilter — all env-driven, no hardcoded URL), subscribes at QoS 1 to the configured topic filter, routes each inbound message through a handler that assigns a generated traceId and logs a structured line, and exposes connection/subscription state through an `ApplicationListener`-backed `MqttConnectionStatus` + a Spring Boot `HealthIndicator`.

## Boundaries & Constraints

**Always:**
- New module `com.syncro.telemetry.{application,infrastructure}`; MQTT adapter + config live in `telemetry/infrastructure/`, trace handler + envelope in `telemetry/application/` (matches architecture "Telemetry Ingestion" mapping).
- Add `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` (compile scope) to `syncro/apps/backend/pom.xml`. It is NOT managed by the Spring Boot 4.0.6 BOM and is declared `optional` by `spring-integration-mqtt` 7.0.4, so the explicit coordinate + version is required. Do NOT add the Paho v5 client.
- Use `MqttPahoMessageDrivenChannelAdapter` (v3 adapter; constructor `(String clientId, MqttPahoClientFactory factory, String... topics)`), `DefaultMqttPahoClientFactory`, `MqttConnectOptions` (`setServerURIs(new String[]{"tcp://" + host + ":" + port})`, username/password, `setAutomaticReconnect(true)`, `setCleanSession(true)`). Set QoS 1 and `autoStartup(true)` on the adapter.
- All values come from the existing `com.syncro.config.MqttProperties` record (`@ConfigurationPropertiesScan` already enabled). Topic filter comes from `topicFilter` verbatim (default/local value `factory/+/+/telemetry`). No EMQX host/URL may be hardcoded in Java source.
- `MqttConnectionStatus` implements `ApplicationListener<MqttIntegrationEvent>` and transitions on the adapter's published events: `MqttSubscribedEvent` -> `SUBSCRIBED`; `MqttConnectionFailedEvent` -> `FAILED` (record `cause` message). `MqttHealthIndicator` (a `HealthIndicator` bean) reports `UP` for `SUBSCRIBED`/`CONNECTED`, else `DOWN` with a `lastError` detail. Actuator `health` is already exposed (`management.endpoints.web.exposure.include: health,info`).
- Tests are hermetic (no live broker needed for green CI): unit tests call the handler with a synthetic `MqttPahoMessage` (`MqttHeaders.RECEIVED_TOPIC` header) and drive status via events directly; the config test asserts bean wiring (topics/qos/autoStartup/clientId) without requiring EMQX.
- Do NOT implement topic/machine validation, payload validation, quarantine, InfluxDB/Redis writes, dedupe, or optional-field handling — those are Stories 3.2–3.6.
- Existing `@SpringBootTest` integration tests already set `syncro.mqtt.*` to localhost:1883; the adapter's `doStart()` publishes `MqttConnectionFailedEvent` instead of throwing when the broker is unreachable (verified in `MqttPahoMessageDrivenChannelAdapter` bytecode), so the full context still loads and those tests stay green with or without EMQX. Do not add a shutdown/stop hook to hide that behavior.

**Block If:**
- If `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` cannot be resolved from Maven Central (offline). HALT with `blocked`.

**Never:**
- No hardcoded EMQX URL/host in backend source (`tcp://` scheme prefix is allowed; the host/port must come from `MqttProperties`).
- No `@ConditionalOnProperty` gate on `syncro.mqtt.enabled` (there is none today; the adapter is always on). Do NOT introduce a new enable/disable property.
- No changes to `application.yml`/`application-local.yml` (already bind `syncro.mqtt.*`) unless an additive property is truly required — prefer none.
- No DB migration, no API endpoint, no frontend work, no shared-subscription (`$share/`) topics, no manual-ack/backpressure (Story 3.12), no resilience4j (Epic 6/WAHA scope).
- Do not hand-roll a raw Paho `MqttClient` loop; use the Spring Integration adapter (already a dependency).

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | EMQX up; backend boots | Adapter starts, subscribes to `factory/+/+/telemetry` at QoS 1; `MqttConnectionStatus` reaches `SUBSCRIBED`; `/actuator/health#mqtt` = UP | No error |
| INBOUND_MESSAGE | EMQX up; payload published to `factory/GM1/BF-08410/telemetry` | Handler logs structured line with `traceId` (non-blank UUID), `topic`, and payload; `MqttHeaders.RECEIVED_TOPIC` mapped into envelope | Handler never throws to the adapter; malformed payload still gets a traceId (content validation is 3.2) |
| BROKER_DOWN_STARTUP | EMQX stopped; backend boots | Context still loads; adapter publishes `MqttConnectionFailedEvent`; status = `FAILED` with lastError; `mqtt` health = DOWN | `doStart()` catches; no exception escapes context refresh |
| BROKER_RECONNECT | EMQX comes back after failure | `automaticReconnect` reconnects; `MqttSubscribedEvent` fires again; status returns to `SUBSCRIBED`; health = UP | Paho reconnect scheduling handles retry |
| EMPTY_PAYLOAD | Zero-length byte payload arrives | traceId still generated and logged; no exception | Handler tolerates empty payload |

</intent-contract>

## Code Map

- `syncro/apps/backend/pom.xml` -- ADD `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` compile dep (BOM-unmanaged; `spring-integration-mqtt` declares it optional)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryEnvelope.java` -- NEW record `(String traceId, String topic, String payload, Instant receivedAt)`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- NEW `MessageHandler`; `TelemetryEnvelope enrich(Message<?>)` (public, unit-testable) + `handleMessage` logs structured line
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfig.java` -- NEW `@Configuration`: `MqttConnectOptions`, `DefaultMqttPahoClientFactory`, `MqttPahoMessageDrivenChannelAdapter` (clientId, factory, topicFilter; `setQos(1)`, `setAutoStartup(true)`), `IntegrationFlow` from adapter -> handler
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttConnectionStatus.java` -- NEW `ApplicationListener<MqttIntegrationEvent>`; enum state + lastError + lastChange
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttHealthIndicator.java` -- NEW `HealthIndicator` reading `MqttConnectionStatus`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` -- NEW unit: synthetic `MqttPahoMessage` -> traceId/topic/receivedAt assertions
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttConnectionStatusTest.java` -- NEW unit: event-driven transitions (SUBSCRIBED / FAILED with cause)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttHealthIndicatorTest.java` -- NEW unit: UP/DOWN mapping + lastError detail
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfigTest.java` -- NEW `@SpringBootTest` (syncro.mqtt.* = localhost:1883): adapter bean exists, `getTopics()==[topicFilter]`, QoS 1, autoStartup true, factory options carry `tcp://host:port`

## Tasks & Acceptance

**Execution:**
- [x] `syncro/apps/backend/pom.xml` -- add Paho v3 client 1.2.5 -- required so the adapter's Paho classes resolve at runtime/context creation
- [x] `TelemetryEnvelope.java` -- record (traceId, topic, payload, receivedAt) -- handler output contract
- [x] `MqttTelemetryIngestHandler.java` -- `enrich(Message)` reads `MqttHeaders.RECEIVED_TOPIC`, generates `UUID.randomUUID()` traceId, builds envelope; `handleMessage` logs `mqtt_telemetry_received traceId={} topic={} payload={}` -- traceId generation per AC + testable seam
- [x] `MqttSubscriptionConfig.java` -- factory/options/adapter/flow from `MqttProperties`; QoS 1; autoStartup true -- "backend subscribes on startup" + env-driven config
- [x] `MqttConnectionStatus.java` + `MqttHealthIndicator.java` -- event-driven state + `/actuator/health` exposure -- observability AC
- [x] `MqttTelemetryIngestHandlerTest.java` -- synthetic message asserts non-blank traceId, topic, receivedAt -- hermetic
- [x] `MqttConnectionStatusTest.java` -- SUBSCRIBED on `MqttSubscribedEvent`, FAILED+cause on `MqttConnectionFailedEvent` -- hermetic
- [x] `MqttHealthIndicatorTest.java` -- UP/DOWN + detail -- hermetic
- [x] `MqttSubscriptionConfigTest.java` -- wiring assertions (no live broker required) -- config AC
- [x] Run full backend test suite -- ensure existing `@SpringBootTest` contexts still load (doStart resilience) -- 301 run, 0 failures, 9 pre-existing skips; 1 pre-existing error `SyncroBackendApplicationTests.contextLoads` (AuditLogRepository unmocked + DataSource excluded) unrelated to this story

**Acceptance Criteria:**
- Given EMQX is running locally and backend MQTT env values are configured, when the backend starts, then it subscribes to `factory/{plantCode}/{machineCode}/telemetry` or the configured equivalent topic filter.
- Given the backend starts, then MQTT host, port, credentials, client ID, and topic filter all come from environment/profile config and no EMQX URL is hardcoded in backend source.
- Given an inbound MQTT message, then it receives or generates a traceId that is logged with the topic.
- Given the backend is connected/subscribed, then connection/subscription status is observable (via `/actuator/health` `mqtt` component) for later health reporting.

## Spec Change Log

- 2026-08-08 (dev): Implemented Story 3.1. Health API uses Spring Boot 4 package `org.springframework.boot.health.contributor.{Health,HealthIndicator}` (moved from `org.springframework.boot.actuate.health` in Boot 4.0.6). `MqttSubscribedEvent` real v7.0.4 constructor is `(Object, String)`; tests drive it with a single topic string. `MqttTelemetryIngestHandler` and `MqttConnectionStatus` inject the existing `Clock` bean (`TimeConfig`, `Clock.systemUTC()`). Full suite: 301 run, 0 failures, 9 pre-existing skips; 1 pre-existing error `SyncroBackendApplicationTests.contextLoads` because `AuditLogRepository` is unmocked while DataSource/JPA/Flyway are excluded (unrelated to this story).

## Review Triage Log

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 5 (low 5)
- defer: 2 (low 2)
- reject: 12
- addressed_findings:
  - `[low]` `patch` Fixed the FAILED state transition ordering race in `MqttConnectionStatus.onApplicationEvent` — `setState(FAILED)` no longer runs before `lastError` is assigned, so the health endpoint can never observe `FAILED` with a silently missing `lastError` detail. Added a health-level recovery regression test (SUBSCRIBED-after-FAILED reports UP with no stale `lastError`).
  - `[low]` `patch` Normalized the missing-topic fallback in `MqttTelemetryIngestHandler.handleMessage`'s catch branch to `""` (matching `enrich`'s convention) instead of logging a possibly-null header.
  - `[low]` `patch` Added a flow-wiring assertion to `MqttSubscriptionConfigTest` — the inbound `IntegrationFlow`'s input channel is the adapter's output channel and the ingest handler bean is wired — closing the untested `IntegrationFlow.from(adapter).handle(handler)` seam.
  - `[low]` `patch` Added `enrich` branch coverage in `MqttTelemetryIngestHandlerTest` for a missing `RECEIVED_TOPIC` header (empty-topic fallback) and a non-`byte[]` (String) payload `toString()` path.
  - `[low]` `patch` Deferred DW-14 (mid-session connectivity loss invisible to health) rather than patching, since the adapter's event set exposes no connection-lost event — recorded as a defer, not auto-fixed.

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 3 (low 3)
- defer: 1 (low 1)
- reject: 12
- addressed_findings:
  - `[low]` `patch` Removed unreachable `CONNECTED` enum state and its health up-branch — the adapter's event set has no connection-opened event, so `CONNECTED` could never be set (dead code).
  - `[low]` `patch` Marked `MqttConnectionStatus` fields (`state`, `lastError`, `lastChange`) `volatile` to fix the visibility race between the adapter event thread and the health endpoint thread.
  - `[low]` `patch` Cleared `lastError` on `MqttSubscribedEvent` so a recovered connection does not keep exposing the previous failure in health details; added a regression test.

## Design Notes

- Adapter wiring:
  ```java
  @Bean
  MqttPahoMessageDrivenChannelAdapter mqttInboundAdapter(MqttPahoClientFactory factory, MqttProperties props) {
    var adapter = new MqttPahoMessageDrivenChannelAdapter(props.clientId(), factory, props.topicFilter());
    adapter.setQos(1);
    adapter.setAutoStartup(true);
    return adapter;
  }
  @Bean
  IntegrationFlow mqttInboundFlow(MqttPahoMessageDrivenChannelAdapter adapter, MqttTelemetryIngestHandler handler) {
    return IntegrationFlow.from(adapter).handle(handler).get();
  }
  ```
- Status tracking is event-driven, not polling: the adapter publishes `MqttSubscribedEvent(source, topics)` on successful subscribe and `MqttConnectionFailedEvent(source, throwable)` on failure (verified in `MqttPahoMessageDrivenChannelAdapter` bytecode — constant-pool `MqttSubscribedEvent.<init>(Object,String)` at the `subscribe()` success path, and `MqttConnectionFailedEvent` published from `doStart()`/`subscribe()` catch paths). These events reach the context because the adapter is a managed bean (`ApplicationEventPublisherAware`).
- Initial state is `UNKNOWN`; `MqttHealthIndicator` maps `SUBSCRIBED` -> UP, `FAILED` -> DOWN with `lastError`, `UNKNOWN` -> DOWN (neutral for pre-first-event startup). The adapter's event set has no connection-opened event, so no `CONNECTED` state exists; subscription is the connectivity signal.
- TraceId uses `UUID.randomUUID()` for Story 3.1 because the payload contract (`messageId`) is parsed in Story 3.2+; later stories may prefer reusing the payload `messageId` per architecture "use messageId from payload or generate one".

## Verification

**Commands:**
- `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"; mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttTelemetryIngestHandlerTest,MqttConnectionStatusTest,MqttHealthIndicatorTest,MqttSubscriptionConfigTest"` -- expected: all pass without EMQX
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green (existing `@SpringBootTest` contexts load even if EMQX is down)

**Manual checks:**
- With `docker compose up -d` (EMQX on 1883) and backend on `local` profile, confirm the subscribe log line appears at startup, `/actuator/health#mqtt` is UP, publishing to `factory/GM1/BF-08410/telemetry` produces a `mqtt_telemetry_received` line with traceId, and stopping EMQX flips health to DOWN then back to UP on reconnect.

## Auto Run Result

**Summary:** Follow-up review pass on Story 3.1 (already implemented, status `done`). Confirmed the auto-starting, env-driven `MqttPahoMessageDrivenChannelAdapter` subscribes to the configured telemetry topic filter at QoS 1, routes inbound messages through `MqttTelemetryIngestHandler` (UUID traceId + `mqtt_telemetry_received` log line), and exposes connection/subscription state via `MqttConnectionStatus` + `MqttHealthIndicator` under `/actuator/health#mqtt`. Applied 5 low-severity patches from this review pass.

**Files changed (this pass):**
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttConnectionStatus.java` — reordered FAILED transition so `lastError` is set before `setState(FAILED)`, eliminating the stale-read window where health could observe FAILED with a missing detail.
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` — normalized missing-topic fallback to `""` in the catch-branch log (was potentially-null header).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfigTest.java` — added flow-wiring assertion (inbound flow input channel == adapter output channel; ingest handler bean wired).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttHealthIndicatorTest.java` — added recovery regression test (SUBSCRIBED-after-FAILED reports UP with no stale `lastError`).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` — added `enrich` branch coverage for missing `RECEIVED_TOPIC` header and String payload.

**Review findings breakdown:** 5 patches applied (all low: FAILED-transition ordering race, catch-branch null-topic consistency, flow-wiring test coverage, health recovery regression test, enrich branch coverage). 2 items deferred (DW-13 config validation pre-existing; DW-14 mid-session connectivity-loss observability, new). 12 items rejected (spec-aligned or out of Story 3.1 scope).

**Follow-up review recommendation:** false — the 5 fixes are low-severity, localized, and carry no behavior/API/security/data impact beyond correcting internal ordering and adding test coverage.

**Verification performed:**
- Targeted: `mvn test -Dtest="MqttTelemetryIngestHandlerTest,MqttConnectionStatusTest,MqttHealthIndicatorTest,MqttSubscriptionConfigTest"` → 16 run, 0 failures, 0 errors, BUILD SUCCESS (no EMQX required).

**Residual risks:**
- DW-14: a broker outage that begins after the initial subscribe is not reflected in health (stays UP/SUBSCRIBED) because the Paho reconnect path does not emit a connection-failed event; deferred for an adapter-level/event-source investigation.
- Qos-1 at-least-once + `cleanSession(true)` continues to drop messages published while disconnected (spec-mandated for Story 3.1; dedupe/backpressure are Stories 3.2/3.12).
- `MqttHealthIndicator` reports DOWN (UNKNOWN) before the first subscribe event — by design.

