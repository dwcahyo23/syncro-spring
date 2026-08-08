---
stepsCompleted: ['step-01-preflight-and-context', 'step-02-generation-mode', 'step-03-test-strategy', 'step-04c-aggregate', 'step-05-validate-and-complete']
lastStep: 'step-05-validate-and-complete'
lastSaved: '2026-08-08'
workflowType: 'testarch-atdd'
storyId: '3.1'
storyKey: '3-1-configure-mqtt-subscription-and-telemetry-contract'
storyFile: '_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md'
atddChecklistPath: '_bmad-output/test-artifacts/atdd-checklist-3-1-configure-mqtt-subscription-and-telemetry-contract.md'
generatedTestFiles:
  - 'syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java'
  - 'syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java'
  - 'syncro/apps/web/tests/api/mqtt-telemetry-contract.atdd-red.spec.ts'
inputDocuments:
  - '_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md'
  - '_bmad-output/test-artifacts/test-design-story-3-1-configure-mqtt-subscription-and-telemetry-contract.md'
  - '_bmad-output/test-artifacts/test-design-progress-3-1.md'
  - '_bmad-output/project-context.md'
---

# ATDD Checklist - Epic 3, Story 3.1: Configure MQTT Subscription and Telemetry Contract

**Date:** 2026-08-08
**Author:** Yusuf (TEA / bmad-testarch-atdd)
**Primary Test Level:** Unit + Integration/Config-slice (backend), API (actuator health contract)

---

## Story Summary

Add a `com.syncro.telemetry` module that builds an auto-starting `MqttPahoMessageDrivenChannelAdapter` (Spring Integration MQTT v3, Paho client) from `syncro.mqtt.*` properties (env-driven, no hardcoded URL), subscribes at QoS 1 to the configured topic filter, routes each inbound message through `MqttTelemetryIngestHandler` (assigns a UUID traceId, logs `mqtt_telemetry_received`), and exposes connection/subscription state via `MqttConnectionStatus` + `MqttHealthIndicator` under `/actuator/health#mqtt`.

> **Run context:** Story 3.1 was already implemented and verified (commit `ec973873f8ae0cb7aa020f2dd30eb61e8be8ef1e`). This ATDD run is a **gap-closing red-phase scaffold** run produced from the story-level test design (`test-design-story-3-1-configure-mqtt-subscription-and-telemetry-contract.md`): it adds RED scaffolds for the uncovered risk areas (R-001 broker-down context load, R-004 credentials carry, R-009 handler edge payloads) plus an implementation checklist to turn them green / verify them.

---

## Acceptance Criteria

1. Given EMQX is running and backend MQTT env values are configured, when the backend starts, then it subscribes to `factory/{plantCode}/{machineCode}/telemetry` (or the configured equivalent topic filter).
2. Given the backend starts, then MQTT host, port, credentials, client ID, and topic filter all come from environment/profile config and no EMQX URL is hardcoded in backend source.
3. Given an inbound MQTT message, then it receives/generates a traceId that is logged with the topic, and the handler never throws to the adapter.
4. Given the backend is connected/subscribed, then connection/subscription status is observable via `/actuator/health` `mqtt` component.

---

## Story Integration Metadata

- **Story ID:** `3.1`
- **Story Key:** `3-1-configure-mqtt-subscription-and-telemetry-contract`
- **Story File:** `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
- **Checklist Path:** `_bmad-output/test-artifacts/atdd-checklist-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
- **Generated Test Files:**
  - `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java`
  - `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java`
  - `syncro/apps/web/tests/api/mqtt-telemetry-contract.atdd-red.spec.ts`

---

## Red-Phase Test Scaffolds Created

### Backend Unit Scaffold (3 tests - all `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java`

- **Test:** `enrichToleratesNonByteStringPayload` (P2, R-009) - a String payload (not `byte[]`) is captured as text; traceId non-blank; receivedAt fixed.
  - **Verifies:** AC3 (handler maps any payload to the envelope; content validation deferred to 3.2).
- **Test:** `enrichToleratesMissingTopicHeader` (P2, R-009) - a message with no `MqttHeaders.RECEIVED_TOPIC` yields an empty topic (never throws); traceId still assigned.
  - **Verifies:** AC3 (handler resilient to absent topic header).
- **Test:** `handleMessageSwallowsEnrichmentFailureToAdapter` (P2, R-009) - a payload whose `toString()` throws forces `enrich()` to fail; `handleMessage` must swallow it (log `mqtt_telemetry_ingest_failed`) and never rethrow to the Spring Integration adapter.
  - **Verifies:** AC3 ("handler never throws to the adapter").

### Backend Config-Slice Scaffold (3 tests - all `@Disabled`)

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java`

- **Test:** `contextLoadsAndHealthIsDownWhileBrokerUnreachable` (P0, R-001) - with no broker on `localhost:1883` the full context still loads (adapter bean present), and `MqttHealthIndicator` reports DOWN.
  - **Verifies:** AC4 + R-001 (broker-down startup never crashes the context; mqtt health surfaces DOWN).
- **Test:** `connectionOptionsCarryCredentialsAndReconnectSettings` (P2, R-004/R-002) - factory `MqttConnectOptions` carry `tcp://localhost:1883`, username `test-user`, password `char[]` `s3cret`, `automaticReconnect(true)`, `cleanSession(true)`.
  - **Verifies:** AC2 (env-driven credentials, no hardcoded URL; reconnect settings per spec).
- **Test:** `healthIndicatorBeanIsRegistered` (P1, R-010) - a `MqttHealthIndicator` bean is present so `/actuator/health` exposes `mqtt`.
  - **Verifies:** AC4 (observability wiring).

### API Test (Playwright, 4 tests - all `test.skip`)

**File:** `syncro/apps/web/tests/api/mqtt-telemetry-contract.atdd-red.spec.ts`

- **Test:** `[P1] /actuator/health exposes an mqtt component` - `GET {actuator}/health` returns 200 with `components.mqtt` present and status `UP`/`DOWN`.
  - **Verifies:** AC4 (health component exposure).
- **Test:** `[P1] mqtt health is DOWN when the broker is not connected` - `components.mqtt.status` === `DOWN`.
  - **Verifies:** AC4 + R-001/R-010 (DOWN on disconnected).
- **Test:** `[P2] mqtt health DOWN carries a lastError detail after a failure` - when DOWN with details, `details.lastError` is present.
  - **Verifies:** AC4 (observability detail).
- **Test:** `[P1] mqtt health is UP once the adapter is subscribed` - activation lock; `components.mqtt.status` === `UP` when the broker is up and subscribed.
  - **Verifies:** AC4 + R-001 (SUBSCRIBED -> UP).

---

## Data Factories Created

None. Backend scaffolds build synthetic `MessageBuilder` payloads inline matching the existing `MqttTelemetryIngestHandlerTest`; the config-slice scaffold reuses the `MqttPropertiesTestConfiguration` pattern from `MqttSubscriptionConfigTest`.

---

## Fixtures Created

None new. The API spec derives the actuator base URL from the existing `apiBaseUrl()` helper (strips `/api/v1`).

---

## Mock Requirements

### Backend (hermetic)

- None. Unit scaffold uses a fixed `Clock`; config-slice scaffold is a `@SpringBootTest` slice with `DataSource/HibernateJpa/Flyway` autoconfiguration excluded (no DB, no live broker needed).

### API (Playwright)

- Requires a live backend exposing `/actuator/health` (operator actions in the spec: boot with `SPRING_PROFILES_ACTIVE=local`, EMQX up for the UP test). The `mqtt` component is read from the real health response; no mocks.

---

## Implementation Checklist

> Red-phase scaffolds stay `@Disabled` / `test.skip` until a developer activates the current task. Activate ONE at a time: remove the skip, confirm it fails (red), implement the minimal fix, confirm green. Backend commands use `$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"`.

### Test: contextLoadsAndHealthIsDownWhileBrokerUnreachable (P0, R-001) - flagship confidence lock

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate (remove `@Disabled` method-level / class-level) and run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionResilienceAtddScaffoldTest#contextLoadsAndHealthIsDownWhileBrokerUnreachable"`
- [ ] Confirm it fails FIRST (red) only if the context throws or health is not DOWN - current implementation is expected to satisfy it (green when activated). If red, verify `MqttPahoMessageDrivenChannelAdapter.doStart()` publishes `MqttConnectionFailedEvent` rather than throwing (see spec design note).
- [ ] Confirm the full `@SpringBootTest` suite still stays green broker-down (adapter resilience regression).
- [ ] ? Test passes (green phase)

**Estimated Effort:** 0.5-1 hour (verification; expected green)

### Test: connectionOptionsCarryCredentialsAndReconnectSettings (P2, R-004/R-002)

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate and run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionResilienceAtddScaffoldTest#connectionOptionsCarryCredentialsAndReconnectSettings"`
- [ ] Confirm `MqttConnectOptions` in `MqttSubscriptionConfig` carry username/password (`char[]`), `tcp://host:port`, `automaticReconnect(true)`, `cleanSession(true)` (already implemented). If red, fix the factory/options wiring.
- [ ] Cross-check AC2: no EMQX URL hardcoded in backend source (grep `tcp://` - only the `tcp://` + host + ":" + port scheme prefix is allowed).
- [ ] ? Test passes (green phase)

**Estimated Effort:** 0.5 hour (verification; expected green)

### Test: healthIndicatorBeanIsRegistered (P1, R-010)

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionResilienceAtddScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate and run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionResilienceAtddScaffoldTest#healthIndicatorBeanIsRegistered"`
- [ ] Confirm the `MqttHealthIndicator` bean is present (already implemented). If red, verify the `@Component` / bean wiring.
- [ ] ? Test passes (green phase)

**Estimated Effort:** 0.25 hour

### Tests: enrichToleratesNonByteStringPayload / enrichToleratesMissingTopicHeader / handleMessageSwallowsEnrichmentFailureToAdapter (P2, R-009)

**File:** `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java`

**Tasks to make this test pass:**

- [ ] Activate each (remove `@Disabled`) and run: `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttTelemetryIngestAtddScaffoldTest"`
- [ ] Confirm `enrich()` maps non-byte payloads to text, tolerates a missing topic header (empty topic), and `handleMessage` swallows enrichment failures (the `try/catch` already logs `mqtt_telemetry_ingest_failed`). If any red, fix the handler's defensive path.
- [ ] Confirm the existing `MqttTelemetryIngestHandlerTest` (happy + empty payload) still passes.
- [ ] ? Tests pass (green phase)

**Estimated Effort:** 0.5-1 hour (verification; expected green)

### Tests: API mqtt health contract (P1/P2) - [P1] exposure, [P1] DOWN when disconnected, [P2] lastError detail, [P1] UP when subscribed

**File:** `syncro/apps/web/tests/api/mqtt-telemetry-contract.atdd-red.spec.ts`

**Tasks to make this test pass:**

- [ ] Requires a live backend exposing `/actuator/health` (`management.endpoints.web.exposure.include: health,info` already set), `API_URL` exported, and EMQX up for the UP test (operator actions in the spec).
- [ ] Activate each test (remove `test.skip`) and run: `npm --prefix syncro/apps/web run test:api`
- [ ] Confirm `[P1] mqtt health is DOWN when the broker is not connected` passes with EMQX stopped, and `[P1] mqtt health is UP once the adapter is subscribed` passes with EMQX running (validates R-001 reconnect + R-010 observability end-to-end).
- [ ] If the health JSON shape differs (e.g. `components` nested under `status`), align the assertion with the Spring Boot 4 actuator response contract.
- [ ] ? Tests pass (green phase)

**Estimated Effort:** 1-2 hours (live backend + broker)

---

## Running Tests

```bash
# Backend - activate one at a time, run the specific scaffold class
$env:JAVA_HOME="C:\Users\Dell\AppData\Local\Programs\Eclipse Adoptium\jdk-25.0.3.9-hotspot"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttTelemetryIngestAtddScaffoldTest"
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionResilienceAtddScaffoldTest"

# Full existing suite (regression - broker-down contexts must stay green)
mvn -q -f syncro/apps/backend/pom.xml test -Dtest="MqttTelemetryIngestHandlerTest,MqttConnectionStatusTest,MqttHealthIndicatorTest,MqttSubscriptionConfigTest"

# API contract scaffold (requires live backend + broker)
npm --prefix syncro/apps/web run test:api
```

---

## Red-Green-Refactor Workflow

### RED Phase (Complete)

- All tests written as red-phase scaffolds: 3 backend unit `@Disabled`, 3 backend config-slice `@Disabled`, 4 API `test.skip`.
- Scaffolds assert EXPECTED behavior (no placeholder assertions).
- Backend scaffolds compile (`mvn test-compile` clean); web spec passes typecheck.

### GREEN Phase (DEV Team - Next Steps)

1. Pick one scaffolded test from the implementation checklist (start with P0: `contextLoadsAndHealthIsDownWhileBrokerUnreachable`).
2. Remove `@Disabled` / `test.skip` for that test and confirm it fails first (red).
3. Read the test; implement / verify the minimal fix.
4. Run the test; verify green.
5. Check off the task; move to the next.

### REFACTOR Phase (DEV Team - After All Tests Pass)

1. Verify all activated tests pass; review for quality.
2. Ensure the existing 12 hermetic telemetry tests still pass after any handler/config changes.
3. Update `test-design-story-3-1-configure-mqtt-subscription-and-telemetry-contract.md` risk statuses (R-001, R-004, R-009, R-010).
4. When all activated tests pass, manually update story status in `sprint-status.yaml`.

---

## Notes

- **Primary gap covered:** R-001 broker-down context load (flagship confidence lock), R-004 credentials carry, R-009 handler edge payloads, R-010 health observability. The existing 12 hermetic tests already cover adapter wiring, status transitions, health mapping, and the handler happy path.
- **Activation-lock vs behavior-lock:** the P0/P1/P2 scaffolds assert already-implemented behavior and are expected to go green once activated; they exist to catch regressions and to complete the acceptance surface identified in the test design.
- **Out of scope (deferred to later stories):** payload/topic/machine validation (3.2/3.3), persistence (3.4), backpressure (3.12), TLS/auth/ACL (3.13), dedup/messageId parsing (3.2). These are tracked as deferred risks R-004/R-006/R-008/R-009/R-012.
- **Manual broker E2E** (publish -> traceId log, broker stop/start -> health DOWN/UP) remains gated by the spec's operator actions and is not hermetically automated here.

---

## Knowledge Base References Applied

- **api-request / api-testing-patterns** - Playwright `request` fixture against the actuator health contract; lazy URL derivation from `apiBaseUrl()`.
- **test-levels-framework** - Unit (handler edges), Integration/config-slice (broker resilience, credentials), API (health contract).
- **test-priorities-matrix / risk-governance** - P0-P3 ordering driven by the story risk register (R-001/R-004/R-009/R-010).
- **test-quality** - Given-When-Then, one assertion intent per test, deterministic data (fixed `Clock`).

---

## Contact

- Tag @TEA in team standup
- Consult `./resources/knowledge` for testing best practices

---

**Generated by BMad TEA Agent** - 2026-08-08