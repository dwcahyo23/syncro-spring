---
workflowStatus: 'completed'
totalSteps: 5
stepsCompleted: ['step-01-detect-mode', 'step-02-load-context', 'step-03-risk-and-testability', 'step-04-coverage-plan', 'step-05-generate-output']
lastStep: 'step-05-generate-output'
nextStep: ''
lastSaved: '2026-08-08'
---

# Test Design: Story 3.1 - Configure MQTT Subscription and Telemetry Contract

**Date:** 2026-08-08
**Author:** Yusuf (TEA Master Test Architect)
**Status:** Approved (Draft review)
**Epic:** Epic 3 - Telemetry Ingestion & Latest Machine Visibility
**Baseline revision:** `b4109d942338d4cf33a80d4cd631000126cbaeff`
**Change under test:** `ec973873f8ae0cb7aa020f2dd30eb61e8be8ef1e` (Story 3.1)

---

## Executive Summary

**Scope:** Risk-based test design for Story 3.1 — auto-starting, env-driven MQTT subscription (`MqttPahoMessageDrivenChannelAdapter` at QoS 1) routing inbound telemetry through `MqttTelemetryIngestHandler` (traceId assignment + structured log), with connection/subscription state exposed via `MqttConnectionStatus` + `MqttHealthIndicator` under `/actuator/health#mqtt`.

This story is the ingest boundary of Epic 3. It introduces a new external integration (MQTT broker) and a new `com.syncro.telemetry` module. The primary risk is operational resilience at the broker boundary (broker-down startup, reconnect, health observability) plus the known deferred gaps (validation, TLS, backpressure) that later stories must gate against.

**Risk Summary:**

- Total risks identified: 12
- High-priority risks (≥6): 3
- Critical categories: TECH / SEC / DATA

**Coverage Summary:**

- P0 scenarios: 4 (~8 hours)
- P1 scenarios: 8 (~12 hours)
- P2/P3 scenarios: 12 (~8 hours)
- **Total effort**: ~28 hours (~4 days)

**Testability verdict:** Good. The implementation provides clean, injectable seams (`Clock` bean, `MqttHeaders.RECEIVED_TOPIC` header, event-driven status via `ApplicationListener`), enabling hermetic unit tests without a live broker. The main testability gap is end-to-end verification of adapter event publication and reconnect behavior, which requires a real broker (manual or optional test-container).

---

## Not in Scope

| Item | Reasoning | Mitigation |
| ---- | --------- | ---------- |
| Payload/topic/machine validation (3.2, 3.3) | Explicitly deferred in spec; Story 3.1 only assigns traceId and logs | Covered by Stories 3.2/3.3 validation tests + quarantine (3.11) |
| InfluxDB / Redis persistence (3.4) | Persistence is a later story | Covered by Story 3.4 tests |
| Backpressure / bounded queue / delayed ack (3.12) | Deferred; no queue bound in 3.1 | Covered by Story 3.12 |
| Dedup / messageId parsing (3.2) | Payload contract not parsed in 3.1 (traceId is UUID) | Covered by Story 3.2 |
| TLS / device auth / ACLs (3.13) | Spec allows plaintext in dev; prod TLS deferred | Covered by Story 3.13; OPS risk R-004 tracked |
| Frontend / dashboard (3.7, 3.8) | Separate stories | Covered by 3.7/3.8 |
| `MqttProperties` null/blank config validation | DW-13 deferred; no config-value guards in 3.1 | Tracked as R-012; monitor |

---

## Risk Assessment

Probability (1=Unlikely, 2=Possible, 3=Likely) × Impact (1=Minor, 2=Degraded, 3=Critical). Score = P×I. Action: 1-3 DOCUMENT, 4-5 MONITOR, 6-8 MITIGATE, 9 BLOCK.

### High-Priority Risks (Score ≥6)

| Risk ID | Category | Description | Probability | Impact | Score | Mitigation | Owner | Timeline |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ---------- | ----- | -------- |
| R-001 | TECH | Broker-down startup resilience: adapter `autoStartup(true)` publishes `MqttConnectionFailedEvent` instead of throwing, so full context must still load and existing `@SpringBootTest` contexts stay green with or without EMQX | 3 | 2 | 6 | Config slice + manual broker-down verification; regression guard on suite | Dev | Immediate |
| R-004 | SEC | Plaintext MQTT (`tcp://`) and static credentials from env; no TLS in Story 3.1 (TLS is Story 3.13). Telemetry + credentials-exposed risk in non-dev | 2 | 3 | 6 | Track as known gap; TLS/auth/ACL in 3.13; never log password; keep credentials env-only | Dev/Sec | By 3.13 |
| R-009 | DATA | No validation of inbound payload/topic — arbitrary/garbage messages are accepted and INFO-logged (contract validation deferred to 3.2/3.3) | 3 | 2 | 6 | Handler must never throw to adapter; quarantine/validation in 3.2/3.3; monitor log volume | Dev | By 3.2 |

### Medium-Priority Risks (Score 3-4)

| Risk ID | Category | Description | Probability | Impact | Score | Mitigation | Owner |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ---------- | ----- | ------ |
| R-002 | TECH | Paho v3 client `1.2.5` is BOM-unmanaged and declared `optional` by `spring-integration-mqtt` 7.0.4 — dependency resolution/version drift risk | 2 | 2 | 4 | Pin exact version already in pom; verify Maven Central resolution; CI build guard | Dev |
| R-006 | PERF | No backpressure/dedup; no bounded queue. High message rate could overload single handler thread / message channel | 2 | 2 | 4 | Deferred to 3.12; monitor ingest worker health (6.2) | Dev |
| R-007 | PERF | Full-payload INFO logging on every message at high volume → log volume/disk growth | 2 | 2 | 4 | Log rotation/monitoring; consider sampling in later story; track message rate | Ops/Dev |
| R-008 | DATA | QoS 1 = at-least-once (duplicates possible) + `cleanSession(true)` drops messages published while disconnected → message loss window + duplicate reprocessing | 2 | 2 | 4 | Spec-mandated for 3.1; dedup via messageId in 3.2; document residual loss window | Dev |
| R-010 | BUS | Health indicator reports DOWN during initial `UNKNOWN` state (before first subscribe event) → false-DOWN flags / health-probe failures at startup | 2 | 2 | 4 | Document UNKNOWN-as-DOWN semantics; validate probe grace period; monitor | Ops |
| R-012 | OPS | Missing `MqttProperties` null/blank validation (DW-13) — malformed env (null host/port/password) yields confusing startup/connection errors | 2 | 2 | 4 | Add config validation in later pass; document required env vars | Dev |

### Low-Priority Risks (Score 1-2)

| Risk ID | Category | Description | Probability | Impact | Score | Action |
| ------- | -------- | ----------- | ----------- | ------ | ----- | ------- |
| R-003 | TECH | Visibility race between adapter event thread (status writes) and health endpoint thread (reads). `volatile` mitigates | 1 | 2 | 2 | Monitor |
| R-005 | SEC | Payload content INFO-logged; telemetry is operational (non-secret) per spec, but full raw payload in logs | 1 | 2 | 2 | Monitor |
| R-011 | OPS | Single ingest worker; no shared subscription (`$share/`) — horizontal scale deferred | 1 | 2 | 2 | Monitor |

### Risk Category Legend

- **TECH**: Technical/Architecture (flaws, integration, scalability)
- **SEC**: Security (access controls, auth, data exposure)
- **PERF**: Performance (SLA violations, degradation, resource limits)
- **DATA**: Data Integrity (loss, corruption, inconsistency)
- **BUS**: Business Impact (UX harm, logic errors, revenue)
- **OPS**: Operations (deployment, config, monitoring)

---

## NFR Planning

**Purpose:** Capture story-level NFR validation needs. This is NOT a final evidence audit (deferred to `nfr-assess` after implementation evidence exists).

| NFR Category | Requirement / Threshold | Risk Link | Planned Validation | Evidence Needed |
| ------------ | ----------------------- | --------- | ------------------ | --------------- |
| Security | Credentials (`syncro.mqtt.password`) never logged; env-driven config only | R-004 | Static scan for password logging; manual env review | SAST report, config review |
| Security | TLS/auth/ACL deferred to 3.13 (plaintext allowed in dev) | R-004 | Manual verification of disabled TLS in dev; prod gate at 3.13 | Security review |
| Reliability | Broker-down startup must not crash context; adapter emits `MqttConnectionFailedEvent` | R-001 | Config slice (context loads w/o broker) + manual broker-down | Test report, boot logs |
| Reliability | Reconnect after failure → `MqttSubscribedEvent` → status returns SUBSCRIBED / health UP | R-001 | Manual broker stop/start; optional broker test-container | Manual proof, monitoring |
| Reliability | Handler never throws to adapter; malformed payload still gets traceId | R-009 | Unit tests (empty/null payload, non-byte payload) | Test report |
| Performance | No bounded queue/backpressure in 3.1 (bounded at 3.12) | R-006 | Monitor message rate; no load target yet | Monitoring data |
| Maintainability | New module boundaries (`application`/`infrastructure`) match architecture; seams injectable | - | Code review + unit tests | Coverage report |

**Unknown thresholds:** Performance/throughput targets, log-volume thresholds, and reconnect-interval expectations are not defined in the spec — do not invent. Left as UNKNOWN; converted to R-006/R-007/R-012 monitoring items.

---

## Entry Criteria

- [ ] Story 3.1 code present in working tree (commit `ec97387`) and compiles
- [ ] `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` resolvable from Maven Central (offline is a BLOCK)
- [ ] Test environment provisioned (Postgres, Redis, InfluxDB, EMQX via `syncro/infra` docker-compose for manual checks)
- [ ] `syncro.mqtt.*` env values configured in `local` profile
- [ ] Existing backend `@SpringBootTest` contexts load (baseline green = 301 run, 0 failures, 9 pre-existing skips, 1 pre-existing unrelated error `SyncroBackendApplicationTests.contextLoads`)

## Exit Criteria

- [ ] All 4 new hermetic test classes pass (`MqttTelemetryIngestHandlerTest`, `MqttConnectionStatusTest`, `MqttHealthIndicatorTest`, `MqttSubscriptionConfigTest`)
- [ ] Full backend suite passes (no new failures vs baseline)
- [ ] No open high-priority / high-severity bugs
- [ ] Manual broker verification recorded (subscribe log, health UP/DOWN, publish→traceId log)
- [ ] Residual deferred risks (R-004, R-006, R-008, R-009, R-012) documented and tracked

---

## Test Coverage Plan

### P0 (Critical) - Run on every commit

**Criteria:** Blocks core journey + High risk (≥6)

| Requirement | Test Level | Risk Link | Test Count | Owner | Notes |
| ----------- | ---------- | --------- | ---------- | ----- | ----- |
| Adapter subscribes to configured topic filter at QoS 1, autoStartup | Component | R-001 | 1 | Dev | `MqttSubscriptionConfigTest` (existing) |
| Context loads with broker down (no throw; `MqttConnectionFailedEvent`) | Component | R-001 | 1 | Dev | Config slice; verify adapter bean + health DOWN |
| Handler assigns non-blank traceId + maps RECEIVED_TOPIC + receivedAt | Unit | R-009 | 2 | Dev | `MqttTelemetryIngestHandlerTest` (existing) |
| Health maps SUBSCRIBED→UP, FAILED→DOWN+lastError, UNKNOWN→DOWN | Unit | R-001/R-010 | 3 | Dev | `MqttHealthIndicatorTest` (existing) |

**Total P0**: 7 tests, ~8 hours

### P1 (High) - Run on PR to main

**Criteria:** Important features + Medium risk (3-4) + Core workflows

| Requirement | Test Level | Risk Link | Test Count | Owner | Notes |
| ----------- | ---------- | --------- | ---------- | ----- | ----- |
| Status transitions SUBSCRIBED / FAILED+cause / clear lastError on recovery | Unit | R-001 | 4 | Dev | `MqttConnectionStatusTest` (existing) |
| Live-broker subscribe → receive → traceId log (manual or broker container) | E2E | R-001 | 1 | QA | mosquitto_pub/MQTTX to `factory/GM1/BF-08410/telemetry` |
| Broker stop → health DOWN; restart → reconnect → health UP | E2E | R-001/R-003 | 1 | QA | Manual; verifies autoreconnect + event-driven recovery |
| `/actuator/health` exposes `mqtt` component | API | R-001/R-010 | 1 | QA | Manual/curl |
| Credentials never logged; env-driven (no hardcoded URL) | API/Static | R-004 | 1 | QA | Grep source + config review |

**Total P1**: 8 tests, ~12 hours

### P2 (Medium) - Run nightly/weekly

**Criteria:** Secondary/edge cases + Low risk (1-2)

| Requirement | Test Level | Risk Link | Test Count | Owner | Notes |
| ----------- | ---------- | --------- | ---------- | ----- | ----- |
| Handler tolerates empty/null/non-byte payload; missing topic header | Unit | R-009 | 4 | Dev | Extend handler test |
| `handleMessage` never throws when `enrich` throws (defensive catch) | Unit | R-009 | 1 | Dev | Extend handler test |
| Factory `connectionOptions` carry `tcp://host:port`, username, password char[] | Component | R-002/R-004 | 2 | Dev | Extend config test |
| QoS 1 + `cleanSession` semantics documented (at-least-once, loss window) | Manual | R-008 | 1 | QA | Document only |
| Paho 1.2.5 resolves & version pinned | Build | R-002 | 1 | Dev | CI dependency check |

**Total P2**: 9 tests, ~7 hours

### P3 (Low) - Run on-demand

**Criteria:** Nice-to-have + Exploratory

| Requirement | Test Level | Test Count | Owner | Notes |
| ----------- | ---------- | ---------- | ----- | ----- |
| High message-rate log-volume observation | Manual | 2 | Ops | Monitoring |
| Malformed config env (null host/port) behavior | Manual | 1 | Dev | DW-13 exploratory |
| `$share/` shared-subscription feasibility note | Manual | 1 | Dev | R-011 |

**Total P3**: 4 scenarios, ~2 hours

---

## Execution Order

### Smoke Tests (<5 min)
- [ ] `mvn test -Dtest="MqttTelemetryIngestHandlerTest,MqttConnectionStatusTest,MqttHealthIndicatorTest,MqttSubscriptionConfigTest"` (12 tests)
- [ ] Full backend suite baseline comparability

### P0 Tests (<10 min)
- [x] Adapter wiring (topic/qos/autoStartup) (Component)
- [x] Handler traceId/topic/receivedAt (Unit)
- [x] Health UP/DOWN mapping (Unit)
- [ ] Context loads with broker down (Component)

### P1 Tests (<30 min)
- [x] Connection status transitions (Unit)
- [ ] Live-broker publish → traceId log (E2E/manual)
- [ ] Broker stop/start → health DOWN/UP (E2E/manual)
- [ ] `/actuator/health#mqtt` exposure (API)

### P2/P3 Tests (<60 min)
- [ ] Handler edge payloads (empty/null/non-byte/missing header) (Unit)
- [ ] Factory options (tcp://host:port, credentials) (Component)
- [ ] Log-volume / malformed-config observation (Manual)

---

## Resource Estimates

### Test Development Effort

| Priority | Count | Hours/Test | Total Hours | Notes |
| -------- | ----- | ---------- | ----------- | ----- |
| P0 | 7 | 1.0 | ~7 | Mostly existing tests; add broker-down context test |
| P1 | 8 | 1.5 | ~12 | Manual E2E + broker scenarios dominate |
| P2 | 9 | 0.75 | ~7 | Handler/config test extensions |
| P3 | 4 | 0.5 | ~2 | Manual/exploratory |
| **Total** | **28** | **-** | **~28 hours (~4 days)** | |

### Prerequisites

**Test Data:**
- MQTT test payloads (JSON `{"running":true}`, empty byte[], null)
- Sample topic `factory/GM1/BF-08410/telemetry`

**Tooling:**
- MQTT client (MQTTX / mosquitto_pub) for manual publish
- EMQX (docker-compose `syncro/infra`) for manual broker scenarios
- Optional: test MQTT broker container for automated E2E (out of scope for hermetic CI)

**Environment:**
- Backend `local` profile with `syncro.mqtt.*` set
- `/actuator/health` exposed (`management.endpoints.web.exposure.include: health,info`)

---

## Quality Gate Criteria

### Pass/Fail Thresholds
- **P0 pass rate**: 100% (no exceptions)
- **P1 pass rate**: ≥95%
- **P2/P3 pass rate**: ≥90% (informational)
- **High-risk mitigations**: 100% complete or documented waivers (R-004/R-006/R-008/R-009/R-012 are deferred-story waivers, tracked)

### Coverage Targets
- **Critical paths**: ≥80% (adapter wiring, handler, health, status)
- **Security scenarios**: 100% (credentials not logged; TLS deferred waivered to 3.13)
- **Business logic**: ≥70%
- **Edge cases**: ≥50% (empty/null payload, missing header, broker-down)

### Non-Negotiable Requirements
- [ ] All P0 tests pass
- [ ] No unexpected high-risk (≥6) items unmitigated (deferred items carry explicit story waivers)
- [ ] Credential-logging security scenario passes
- [ ] Broker-down startup does not crash context
- [ ] Planned NFR evidence exists or `nfr-assess` documents CONCERNS/waivers

---

## Mitigation Plans

### R-001: Broker-down startup resilience (Score: 6)
**Mitigation Strategy:** Verify context loads with broker down (adapter emits event, no throw). Add config-slice regression guard. Verify reconnect→SUBSCRIBED→UP manually.
**Owner:** Dev
**Timeline:** Immediate
**Status:** In Progress (config slice test exists; manual E2E pending)
**Verification:** `MqttSubscriptionConfigTest` + manual broker stop/start + `/actuator/health#mqtt`

### R-004: Plaintext MQTT + static credentials (Score: 6)
**Mitigation Strategy:** Keep credentials env-only, never log password; document TLS/auth/ACL as Story 3.13 gate. Grep-source guard for password logging.
**Owner:** Dev/Sec
**Timeline:** By 3.13
**Status:** Planned (waiver for 3.1)
**Verification:** SAST/static scan + config review

### R-009: No inbound validation (Score: 6)
**Mitigation Strategy:** Handler never throws to adapter; malformed payload still gets traceId. Validation + quarantine in 3.2/3.3. Unit-test defensive path.
**Owner:** Dev
**Timeline:** By 3.2
**Status:** In Progress (handler defensive tests exist)
**Verification:** Handler edge-payload unit tests + 3.2 validation tests

---

## Assumptions and Dependencies

### Assumptions
1. EMQX broker is available for manual verification via `syncro/infra` docker-compose.
2. Hermetic CI (no broker) is the baseline; E2E broker scenarios are manual or optional.
3. Deferred risk items (R-004, R-006, R-008, R-009, R-012) are acceptable for Story 3.1 and tracked to later stories.
4. Paho `1.2.5` resolves from Maven Central (offline is a BLOCK, per spec).

### Dependencies
1. `org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5` from Maven Central - required now
2. EMQX broker (local) - required for manual E2E
3. `MqttProperties` + `TimeConfig` beans - present in baseline
4. Actuator health exposure - present in baseline

### Risks to Plan
- **Risk**: Broker cannot be provisioned for manual verification
  - **Impact**: P1 E2E scenarios (R-001 reconnect, publish→traceId) cannot be executed
  - **Contingency**: Rely on hermetic unit/config tests + defer manual E2E; document as CONCERNS

---

## Interworking & Regression

| Service/Component | Impact | Regression Scope |
| ----------------- | ------ | ---------------- |
| Backend `@SpringBootTest` contexts | Adapter auto-start with broker down must not break context load | Full backend suite must stay green (baseline 301 run, 0 failures, 9 skips, 1 pre-existing unrelated error) |
| `/actuator/health` | New `mqtt` component added; must not disturb existing health contributors | Existing health-related tests |
| Epic 2 machine master data | Topic identity later validated against registered machines (3.2/3.3) | No change in 3.1; contract for future |
| Epic 6 ingest-worker health | `MqttHealthIndicator` becomes input to 6.2 health reporting | Monitor integration latity |

---

## Follow-on Workflows (Manual)
- Run `*atdd` to generate failing P0/P1 tests (e.g., broker-down context, handler edge payloads).
- Run `*automate` once E2E broker harness is available.
- Re-assess R-004/R-006/R-008/R-009/R-012 as Stories 3.2/3.3/3.12/3.13 land.

---

## Approval

**Test Design Approved By:**

- [ ] Product Manager: ________ Date: ________
- [ ] Tech Lead: ________ Date: ________
- [ ] QA Lead: Yusuf (TEA) Date: 2026-08-08

**Comments:** Story 3.1 is a well-seamed ingest boundary. Primary test focus is broker resilience (R-001) and deferred-gap tracking (R-004/R-009). Existing 12 hermetic tests provide strong unit coverage; the main open work is manual broker E2E verification.

---

## Appendix

### Knowledge Base References
- `risk-governance.md` - Risk classification framework
- `probability-impact.md` - Risk scoring methodology
- `test-levels-framework.md` - Test level selection
- `test-priorities-matrix.md` - P0-P3 prioritization

### Related Documents
- Spec: `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
- Epic: `_bmad-output/implementation-artifacts/epic-3-context.md`
- Project Context: `_bmad-output/project-context.md`

---

**Generated by**: BMad TEA Agent - Test Architect Module
**Workflow**: `bmad-testarch-test-design`
**Version**: 4.0 (BMad v6)