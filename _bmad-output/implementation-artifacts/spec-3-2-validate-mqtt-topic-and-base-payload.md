---
title: '3-2 Validate MQTT Topic and Base Payload'
type: 'feature'
created: '2026-08-08'
baseline_revision: 'd14ce614135eeedd85a5d195fb8fa140f2a3181f'
final_revision: 'b66b06c'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
operator_actions:
  - 'Start local infra: `docker compose up -d` from `syncro/infra` (Postgres, Redis, InfluxDB, EMQX, WAHA).'
  - 'Boot backend with `SPRING_PROFILES_ACTIVE=local`; confirm clean boot.'
  - 'Ensure plant `GM1` and machine `BF-08410` exist in master data (seed via the plants/machines API or existing fixtures).'
  - 'Publish a valid payload `{"running":true,"runtimeHours":12.5,"counting":100}` to `factory/GM1/BF-08410/telemetry`; confirm an INFO `mqtt_telemetry_received` line with traceId.'
  - 'Publish a payload missing `counting` to `factory/GM1/BF-08410/telemetry`; confirm a WARN `mqtt_telemetry_rejected` line containing `reason=missing_base_field` and the traceId; confirm the ingest worker stays up.'
  - 'Publish any payload to `factory/XX1/BF-08410/telemetry` (unknown plant) and to `factory/GM1/UNKNOWN/telemetry` (unknown machine); confirm WARN `mqtt_telemetry_rejected` lines with `reason=unknown_plant` / `reason=unknown_machine`.'
---

<intent-contract>

## Intent

**Problem:** Story 3.1 established the MQTT subscription and logs every inbound message with a traceId, but nothing validates the topic against registered master data or checks the base payload shape, so invalid machine data could reach telemetry stores unchallenged.

**Approach:** After enrichment, validate the topic `factory/{plantCode}/{machineCode}/telemetry` by resolving the plant by code, then the machine by plant+code against master data; parse the payload JSON and require non-null `running`, `runtimeHours`, `counting`. Reject invalid topics/payloads with a structured log line (`reason` + `traceId`) and skip any downstream write, never throwing to the MQTT adapter.

## Boundaries & Constraints

**Always:**
- New application-layer classes under `com.syncro.telemetry.application`: a `TelemetryTopic` parser record, a `TelemetryPayload` record (`boolean running`, `double runtimeHours`, `long counting`), and a `TelemetryValidationService` that orchestrates: parse topic → `PlantRepository.findByCodeIgnoreCase(plantCode)` → `MachineRepository.findByPlantIdAndCodeIgnoreCase(plantId, machineCode)` → parse payload → require the three base fields. Returns a result object carrying either the resolved `MachineEntity` + parsed payload or a rejection `reason`.
- `MqttTelemetryIngestHandler` is extended to inject `TelemetryValidationService`; after `enrich`, it validates and logs `mqtt_telemetry_rejected reason={} traceId={} topic={}` for invalid input and `mqtt_telemetry_received` only for accepted input. It must keep the never-throw-to-adapter contract: any validation error is caught and logged, never rethrown.
- Machine code is normalized/compared case-insensitively (existing `findByPlantIdAndCodeIgnoreCase`); plant code via `findByCodeIgnoreCase` (existing `PlantRepository`). Topic must have exactly 4 `/`-delimited segments with `factory` prefix and `telemetry` suffix.
- Payload parsing uses Jackson `ObjectMapper` (existing convention: `new ObjectMapper()` inline, auto-configured bean available). Missing/`null` value, wrong type, or unparseable JSON for any of the three base fields → rejection with a specific reason.
- Hermetic unit tests: `TelemetryTopicTest`, `TelemetryPayloadTest`, and `TelemetryValidationServiceTest` (Mockito-mocked `PlantRepository`/`MachineRepository`) plus handler tests — no live broker or DB required for green CI. One `@SpringBootTest` integration test seeds a Plant + Machine via Testcontainers (existing pattern) and asserts lookup/rejection end-to-end.
- `MqttTelemetryIngestHandlerTest` and `MqttTelemetryIngestAtddScaffoldTest` constructors must be updated to supply the validation service (a stub that accepts everything, so existing enrichment assertions stay valid).

**Block If:**
- If the existing `MqttTelemetryIngestAtddScaffoldTest`/`MqttTelemetryIngestHandlerTest` constructor contract cannot be preserved without breaking Story 3.1's never-throw contract. Resolve by keeping validation swallow-and-log inside `handleMessage`.

**Never:**
- No quarantine table/service (Story 3.10), no InfluxDB/Redis writes (Story 3.4), no inactive-machine rejection (Story 3.3), no optional-field handling (Story 3.6), no dedupe (Story 3.12), no config-validation changes (DW-13).
- Do NOT reuse `AuditLogWriter` (it requires an `AuthenticatedUser` actor; the MQTT path has none). Rejection evidence is structured logging only.
- No new DB migration, no API endpoint, no frontend work, no hardcoded EMQX URL, no `@ConditionalOnProperty` gate on the adapter.
- Do NOT change `MqttSubscriptionConfig` wiring or the topic filter.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | topic `factory/GM1/BF-08410/telemetry`, payload `{"running":true,"runtimeHours":12.5,"counting":100}`; plant GM1 + machine BF-08410 registered | Validation accepted; `mqtt_telemetry_received` logged with traceId; no store write yet | No error |
| UNKNOWN_PLANT | topic `factory/XX1/BF-08410/telemetry`; plant XX1 not registered | Rejected reason `unknown_plant`; `mqtt_telemetry_rejected` with reason + traceId | Logged, swallowed |
| UNKNOWN_MACHINE | plant exists, machine code not registered under it | Rejected reason `unknown_machine` | Logged, swallowed |
| MALFORMED_TOPIC | topic `factory/only-one-segment` or `WRONG/plant/machine/telemetry` or empty | Rejected reason `malformed_topic` | Logged, swallowed |
| MISSING_FIELD | payload `{"running":true}` (no runtimeHours/counting) | Rejected reason `missing_base_field` (names missing fields) | Logged, swallowed |
| WRONG_TYPE | payload `{"running":"yes","runtimeHours":12,"counting":100}` | Rejected reason `invalid_field_type` | Logged, swallowed |
| UNPARSEABLE_JSON | payload `not json` or empty | Rejected reason `unparseable_payload` | Logged, swallowed |
| VALIDATION_THROWS | repository or parser throws unexpectedly | No exception escapes to adapter; `mqtt_telemetry_ingest_failed` logged | Try/catch in handler |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryTopic.java` -- NEW record `(String plantCode, String machineCode)` with static `parse(String topic)` returning `Optional`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` -- NEW record `(boolean running, double runtimeHours, long counting)` with static `parse(String json, ObjectMapper)` returning parse/validation result
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` -- NEW `@Service`; orchestrates topic+payload validation vs `PlantRepository`/`MachineRepository`; returns `TelemetryValidationResult` (accepted machine+payload or reason)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- MODIFY: inject `TelemetryValidationService`; validate after enrich; log reject/accept; keep never-throw
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryTopicTest.java` -- NEW unit
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPayloadTest.java` -- NEW unit
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationServiceTest.java` -- NEW unit (Mockito)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` -- MODIFY: constructor supplies accepting stub validator
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestAtddScaffoldTest.java` -- MODIFY: constructor supplies accepting stub validator
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` -- NEW `@SpringBootTest` + Testcontainers Postgres: seed plant+machine, assert accept/reject

## Tasks & Acceptance

**Execution:**
- [x] `TelemetryTopic.java` -- record + `parse()` enforcing 4 segments, `factory` prefix, `telemetry` suffix -- topic identity extraction seam
- [x] `TelemetryPayload.java` -- record + `parse()` requiring non-null `running`/`runtimeHours`/`counting` with type checks -- base payload contract
- [x] `TelemetryValidationService.java` -- resolve plant by code, machine by plant+code, parse payload; return accept/reject result with reason -- core validation gate
- [x] `MqttTelemetryIngestHandler.java` -- inject service, validate after enrich, log `mqtt_telemetry_rejected`/`mqtt_telemetry_received`, swallow errors -- rejection logging + worker safety
- [x] `TelemetryTopicTest.java` -- happy path + malformed/empty/extra-segment cases -- hermetic
- [x] `TelemetryPayloadTest.java` -- happy + missing field + wrong type + unparseable -- hermetic
- [x] `TelemetryValidationServiceTest.java` -- Mockito: unknown plant/machine, accept, valid-payload dispositions -- hermetic
- [x] Update `MqttTelemetryIngestHandlerTest.java` + `MqttTelemetryIngestAtddScaffoldTest.java` -- accepting stub validator in constructor -- preserve Story 3.1 contract
- [x] `TelemetryValidationIntegrationTest.java` -- Testcontainers: seeded plant+machine accepted; unknown machine rejected -- end-to-end
- [x] Run full backend test suite -- existing `@SpringBootTest` contexts still load; no regression -- green (332 run, 0 failures, 1 pre-existing error, 15 skipped)

**Acceptance Criteria:**
- Given an MQTT message with topic containing plantCode and machineCode, when the backend receives it, then it validates plantCode and machineCode against registered machine data.
- Given an MQTT message, when the payload is parsed, then the backend validates it includes `running`, `runtimeHours`, and `counting`.
- Given an invalid topic or payload, then it is rejected before any InfluxDB/Redis write.
- Given a rejection, then it is logged with reason and traceId.
- Given any validation error, then the MQTT ingest worker does not crash.

## Spec Change Log

## Review Triage Log

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6 (low 6)
- defer: 4 (low 4)
- reject: 4
- addressed_findings:
  - `[low]` `patch` Fixed `TelemetryValidationIntegrationTest` overriding `SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry` (copy-paste from `AuditLogWiringIntegrationTest`) to the production `factory/+/+/telemetry` namespace used throughout this story.
  - `[low]` `patch` Removed `field=null` noise from `mqtt_telemetry_rejected` — the handler now emits `field={}` only when the rejection carries a field (missing/wrong-type payload), matching the spec's documented `reason={} traceId={} topic={}` contract for reason-only rejections.
  - `[low]` `patch` Replaced the handler's `if/else if` chain over the sealed `Result` type with an exhaustive `switch` expression, so adding a third permit later fails to compile instead of silently dropping messages.
  - `[low]` `patch` Added `acceptsCaseInsensitiveCodes` integration test (3.2-VAL-004) proving `factory/gm1/bf-08410/telemetry` resolves via `findByCodeIgnoreCase`/`findByPlantIdAndCodeIgnoreCase`, closing the case-insensitivity test gap.
  - `[low]` `patch` `TelemetryPayload.parse` now accepts integral-valued floating-point `counting` (e.g. `12.0` from a float-typed PLC tag) while still rejecting fractional (`12.9`) — `isIntegralNumber()` alone returned false for `DoubleNode`; added `acceptsIntegralDoubleCounting` regression test.
  - `[low]` `patch` Added `handleMessageLogsReceivedForAcceptedMessage` and `handleMessageLogsRejectionWithReasonAndTraceId` capturing logback events, so AC "rejection logged with reason and traceId" is asserted in CI rather than only exercised without assertion.

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 6 (low 6)
- defer: 3 (low 3)
- reject: 3
- addressed_findings:
  - `[low]` `patch` Rejected trailing-slash (`factory/GM1/BF-08410/telemetry/`) and empty plantCode/machineCode segments (`factory//X/telemetry`) in `TelemetryTopic.parse` — `String.split` drops trailing empties so the 4-segment guard alone let malformed topics through; added `endsWith("/")` + non-empty-segment guards plus regression tests.
  - `[low]` `patch` Rejected fractional and non-convertible `counting` (`isIntegralNumber()` + `canConvertToLong()`) and non-finite `runtimeHours` (`Double.isFinite`) in `TelemetryPayload.parse`, so fractional counts are no longer silently truncated and `1e999` no longer parses as Infinity; added regression tests.
  - `[low]` `patch` Replaced the unchecked `(ParseResult.Accepted) parseResult` cast in `TelemetryValidationService` with a sealed-type `switch` expression that is exhaustive at compile time.
  - `[low]` `patch` Propagated the offending `field` from `TelemetryPayload.ParseResult.Rejected` into `TelemetryValidationService.Result.Rejected` and logged it in the handler's `mqtt_telemetry_rejected` line, so operators see which field failed.
  - `[low]` `patch` Added `RejectingTelemetryValidationService` stub + `handleMessageSwallowsRejectionWithoutThrowing` so the handler's `mqtt_telemetry_rejected` branch is exercised (previously only the accepted path was tested).

## Design Notes

- Topic regex-free parsing: `topic.split("/")` must yield exactly 4 segments; fail fast otherwise. `[0]` must equal `factory`, `[3]` must equal `telemetry`, `[1]`/`[2]` are plantCode/machineCode. This tolerates the configured `factory/+/+/telemetry` filter shape.
- Validation order mirrors the architecture: parse topic → resolve plant → resolve machine → parse payload → require base fields. Machine resolution is case-insensitive to match master-data normalization (`PlantService.normalizeCode` uppercases).
- Rejection is a `TelemetryValidationResult` sealed-style result (either `Accepted(machine, payload)` or `Rejected(reason)`), so the handler stays a thin log-and-go and the result is unit-testable without a broker.
- `TelemetryPayload.parse` returns a `ParseResult` (accepted record or rejection reason with the offending field) rather than throwing, so field-level reasons (missing vs wrong-type) are distinct in the log.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryTopicTest,TelemetryPayloadTest,TelemetryValidationServiceTest,MqttTelemetryIngestHandlerTest,MqttTelemetryIngestAtddScaffoldTest"` -- expected: all pass without EMQX/DB
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green (existing contexts load)

**Manual checks (operator, requires broker + master data):**
- With `docker compose up -d` (EMQX, Postgres, Redis, InfluxDB) and backend on `local` profile, publish a valid payload to `factory/GM1/BF-08410/telemetry` for a registered machine → INFO `mqtt_telemetry_received` with traceId.
- Publish to an unknown plant (`factory/XX1/...`) or unknown machine, and a payload missing `counting` → WARN `mqtt_telemetry_rejected` with reason + traceId; worker stays up.

## Auto Run Result

Status: done

Fresh review pass on the already-completed Story 3.2 (spec supplied with `status: done`; review_loop_iteration reset to 0 per step-01).

**Implemented change (this pass):** No new feature work — the story was implemented and committed earlier (HEAD before this pass: 514edf6). This pass hardened the existing implementation based on a fresh Blind Hunter + Edge Case Hunter review of `d14ce61..HEAD`.

**Files changed (this pass):**
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` — rejection log now emits `field={}` only when present; `if/else if` over sealed `Result` replaced with exhaustive switch so a future permit fails to compile instead of silently dropping messages.
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` — `counting` now accepts integral-valued floating-point numbers (`12.0`) while still rejecting fractional (`12.9`).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` — added logback `ListAppender` assertions for the accepted and rejected log branches (AC "rejection logged with reason and traceId" now asserted in CI).
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPayloadTest.java` — added `acceptsIntegralDoubleCounting` regression test.
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` — topic filter override aligned to production `factory/+/+/telemetry`; added `acceptsCaseInsensitiveCodes` (3.2-VAL-004).
- `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md` — new Review Triage Log entry for this pass.
- `_bmad-output/implementation-artifacts/deferred-work.md` — appended DW-17..DW-20 (no existing entries modified; orchestrator owns their status).

**Review findings breakdown:** patch 6 (all low, fixed this pass); defer 4 (low, appended DW-17..DW-20); reject 4 (negative-value findings already tracked as DW-15, DW-16 transaction nuance already tracked, spec metadata contradiction by-design loop reset, speculative stub NPE trap). intent_gap 0; bad_spec 0.

**Follow-up review recommendation:** false — all patched findings were localized low-consequence fixes (logging shape, one validation boundary, test coverage); no API/contract/data impact.

**Verification performed:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryTopicTest,TelemetryPayloadTest,TelemetryValidationServiceTest,MqttTelemetryIngestHandlerTest,MqttTelemetryIngestAtddScaffoldTest"` — 0 failures, 0 errors (3 disabled scaffolds skipped).
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryValidationIntegrationTest"` — 4/4 pass via Testcontainers Postgres, including new case-insensitive test.
- Full `mvn test` — 342 run, 0 failures, 1 error (pre-existing `SyncroBackendApplicationTests.contextLoads` — `AuditLogRepository` unavailable because the test excludes JPA autoconfiguration; matches baseline's documented 1 pre-existing error), 15 skipped.

**Residual risks:**
- DW-17 (detached lazy `MachineEntity` in `Accepted` contract) will throw `LazyInitializationException` at the first downstream lazy access (Story 3.4 store write) — deferred with a required contract decision.
- Accepted-path INFO logging still includes the full raw payload (DW-18, pre-existing from Story 3.1).
- `MqttSubscriptionConfigTest` still constructs the handler with a mocked-repo validator (DW-19); whitespace-in-segment topics surface as `unknown_plant`/`unknown_machine` rather than `malformed_topic` (DW-20).
- Negative `runtimeHours`/`counting` range validation remains owned by Story 3.5 / DW-15.


