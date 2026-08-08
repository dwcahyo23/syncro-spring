---
title: '3-3 Reject Inactive Machine Telemetry'
type: 'feature'
created: '2026-08-08'
baseline_revision: 'b472a2cff3675480436736ce860fe71e09c26c79'
final_revision: '36ab6ea'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Story 3.2 validates topic and payload against registered master data, but telemetry for machines whose manual status is `INACTIVE` is still accepted, so manual machine status stops being authoritative and decommissioned/disabled machines would keep feeding telemetry stores.

**Approach:** After the machine is resolved by plant+code, reject the message when the machine's manual master-data status is `INACTIVE` with a structured reason `inactive_machine`, flowing through the existing `mqtt_telemetry_rejected` WARN log (with reason, traceId, and machine identity from the topic) before any payload parse or downstream write.

## Boundaries & Constraints

**Always:**
- In `TelemetryValidationService.validate`, insert the status check immediately after the `unknown_machine` guard and **before** `TelemetryPayload.parse`. Reject `machine.getStatus() == MachineStatus.INACTIVE` with `new Result.Rejected("inactive_machine", null)` (field `null`, matching whole-message rejections like `unknown_plant`/`unknown_machine`).
- Manual machine status is read only from the `MachineEntity.status` master-data field (`MachineStatus` enum: `ACTIVE`/`INACTIVE`); never inferred from telemetry freshness. No new repository finder needed — `findByPlantIdAndCodeIgnoreCase` already returns the entity with status.
- Hermetic unit tests: extend `TelemetryValidationServiceTest` with an `rejectsInactiveMachine` test (machine built with `MachineStatus.INACTIVE`); ensure active-machine acceptance and existing reject reasons stay green. No broker or DB required.
- One `@SpringBootTest` integration test in `TelemetryValidationIntegrationTest` (existing Testcontainers pattern) seeds an `INACTIVE` machine and asserts rejection with reason `inactive_machine`, plus asserts an `ACTIVE` machine with the same code path is still accepted (continuity guard).
- `MqttTelemetryIngestHandler` needs NO change: the `inactive_machine` rejection has `field == null`, so it emits the short `mqtt_telemetry_rejected reason={} traceId={} topic={}` WARN line — topic carries the machine identity. Verify with the existing handler test pattern (stub validator returning a `Rejected("inactive_machine", null)`) if a handler-level log assertion is added.

**Block If:**
- If the story requires quarantine persistence, InfluxDB/Redis write guards, or UI feedback — those belong to Stories 3.4/3.7+ and are out of scope here; do not widen this story unattended.

**Never:**
- No new DB migration, no new table, no quarantine, no API endpoint, no frontend work.
- Do NOT reuse `AuditLogWriter` (MQTT path has no `AuthenticatedUser`); rejection evidence is structured logging only (Story 3.2 decision).
- No payload-topic identity mismatch changes (Story 3.10), no dedupe (Story 3.12), no range validation (Story 3.5), no config changes.
- Do NOT change `MqttSubscriptionConfig` wiring or the topic filter.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| INACTIVE_MACHINE | topic `factory/GM1/BF-08410/telemetry`, valid payload; machine BF-08410 exists with `MachineStatus.INACTIVE` | Rejected `inactive_machine`; `mqtt_telemetry_rejected reason=inactive_machine traceId=... topic=factory/GM1/BF-08410/telemetry`; no store write | Logged, swallowed |
| ACTIVE_MACHINE | same topic/payload; machine `ACTIVE` | Accepted; `mqtt_telemetry_received` logged; no store write yet (Story 3.4) | No error |
| UNKNOWN_MACHINE | machine code not registered | Rejected `unknown_machine` (unchanged, checked before status) | Logged, swallowed |
| INVALID_PAYLOAD_ACTIVE | machine `ACTIVE`, payload missing `counting` | Rejected `missing_base_field` (unchanged; status gate passes then payload fails) | Logged, swallowed |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` -- MODIFY: add inactive-status rejection after machine lookup, before payload parse
- `syncro/apps/backend/src/main/java/com/syncro/machine/domain/MachineStatus.java` -- EXISTS `ACTIVE`/`INACTIVE` enum; read-only
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineEntity.java` -- EXISTS `getStatus()` accessor; read-only
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java` -- EXISTS `findByPlantIdAndCodeIgnoreCase`; read-only
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- NO CHANGE (rejection flows through existing `field == null` WARN branch)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationServiceTest.java` -- MODIFY: add `rejectsInactiveMachine` unit test
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` -- MODIFY: add `3.3-VAL-*` Testcontainers tests (inactive rejected, active accepted continuity)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` -- MODIFY (optional): add log-assertion for `inactive_machine` rejection via rejecting stub

## Tasks & Acceptance

**Execution:**
- [x] `TelemetryValidationService.java` -- insert `if (machine.get().getStatus() == MachineStatus.INACTIVE) return new Result.Rejected("inactive_machine", null);` after the `unknown_machine` guard, before the payload switch -- status gate in the validation order specified by epic architecture
- [x] `TelemetryValidationServiceTest.java` -- add `rejectsInactiveMachine` (Mockito: machine with `MachineStatus.INACTIVE`, expect reason `inactive_machine`, field null) -- hermetic coverage of the new gate
- [x] `TelemetryValidationServiceTest.java` -- continuity for `ACTIVE` machine + invalid payload (`missing_base_field`) is already covered by the pre-existing `rejectsInvalidPayload`; a new `rejectsInactiveMachineBeforeParsingPayload` pins that the status gate fires before payload parse (INACTIVE + unparseable payload still yields `inactive_machine`) -- guards regression of Story 3.2 contract
- [x] `TelemetryValidationIntegrationTest.java` -- add `3.3-VAL-001` (seed INACTIVE machine, reject `inactive_machine`) and `3.3-VAL-002` (ACTIVE machine accepted continuity) -- end-to-end with real Postgres
- [x] `MqttTelemetryIngestHandlerTest.java` -- add handler log assertion for a `Rejected("inactive_machine", null)` stub: WARN `mqtt_telemetry_rejected` with reason + traceId + topic -- AC "log evidence includes machine identity and traceId"
- [x] Run full backend test suite -- existing `@SpringBootTest` contexts still load; no regression -- green (346 run, 0 failures, 1 pre-existing error, 15 skipped)

**Acceptance Criteria:**
- Given registered machine status is `INACTIVE`, when MQTT telemetry arrives for that machine, then backend rejects the telemetry before writing to InfluxDB or Redis.
- Given a rejected inactive-machine message, then the rejection reason identifies inactive machine status (`inactive_machine`).
- Given a rejected inactive-machine message, then audit/log evidence includes machine identity (topic) and traceId.
- Given machine status in master data, then it remains manual master data and is never inferred from telemetry freshness.

## Spec Change Log

## Review Triage Log

### 2026-08-08 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4 (low 4)
- defer: 1 (low 1)
- reject: 6
- addressed_findings:
  - `[low]` `patch` Added `rejectsInactiveMachineBeforeParsingPayload` unit test pinning that the status gate fires before payload parse (INACTIVE machine + unparseable payload still yields `inactive_machine`, not a payload reason) — previously the gate-before-parse ordering was never directly tested.
  - `[low]` `patch` Handler log test for `inactive_machine` now asserts the WARN line does NOT contain `field=`, pinning that the `field == null` short-form branch is taken.
  - `[low]` `patch` Integration `3.3-VAL-002` now asserts `field()==null` on the inactive rejection, matching `3.3-VAL-001`'s assertion strength.
  - `[low]` `patch` Spec task accounting corrected: the ACTIVE-machine continuity for `missing_base_field` was already covered by the pre-existing `rejectsInvalidPayload`; the spec now documents that rather than claiming a new test.

### 2026-08-08 — Follow-up review pass (done → fresh review)
- intent_gap: 0
- bad_spec: 0
- patch: 0
- defer: 1 (low 1)
- reject: 13
- addressed_findings:
  - none

## Design Notes

- The status gate sits between machine resolution and payload parsing, mirroring the epic's documented validation order (receive → machine exists from topic → reject inactive → schema/contract → dedupe → writes).
- `Rejected("inactive_machine", null)` uses `field = null` so the handler logs the concise `reason={} traceId={} topic={}` line already covered by Story 3.2 tests — no handler branch changes required.
- Machine identity is carried by the topic (`factory/{plantCode}/{machineCode}/telemetry`), which the WARN line already logs, satisfying the "machine identity and traceId" acceptance criterion.

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryValidationServiceTest,TelemetryValidationIntegrationTest,MqttTelemetryIngestHandlerTest"` -- expected: all pass (integration via Testcontainers Postgres)
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green (no regression)

**Manual checks (operator, requires broker + master data):**
- With `docker compose up -d` and backend on `local` profile, set machine BF-08410 status to `INACTIVE` via master data, publish valid payload to `factory/GM1/BF-08410/telemetry` → WARN `mqtt_telemetry_rejected` with `reason=inactive_machine`, traceId, topic; worker stays up; no InfluxDB/Redis write.

## Auto Run Result

Status: done

Follow-up review pass on the completed Story 3.3 (spec supplied with `status: done` → fresh review).

**Review findings breakdown (this pass):** patch 0; bad_spec 0; intent_gap 0; defer 1 (low 1 — fail-open/exclusion-based status gate and null-status acceptance, already tracked as DW-21, not re-opened per orchestrator ledger ownership); reject 13 (false null-NPE claim — Java `null == enum` is `false`, no NPE; mutually-exclusive `unknown_*`/inactive ordering that is structurally guaranteed; contrived continuity scenarios; cosmetic test-duplication; spec-mandated stub-driven handler-log pattern; out-of-scope metrics/comment/deferral-format suggestions; unverifiable-claim concern, since full-suite result was re-verified this pass).

**Verification performed (this pass):**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryValidationServiceTest,TelemetryValidationIntegrationTest,MqttTelemetryIngestHandlerTest"` (JDK 25, Testcontainers Postgres) — ServiceTest 7/7, Integration 6/6, HandlerTest 9/9, all green.
- Full suite `mvn -q -f syncro/apps/backend/pom.xml test` — 347 run, 0 failures, 1 pre-existing error (`SyncroBackendApplicationTests.contextLoads`: `AuditLogRepository` unavailable under excluded JPA/DataSource autoconfig — pre-existing, unrelated to telemetry), 15 skipped. No regression from Story 3.2 baseline.

**Residual risks:**
- DW-21 (exclusion-based gate accepts future non-ACTIVE statuses and null status) remains deferred to a future machine-status change; tracked in the ledger, owned by the orchestrator.
- Manual operator check (broker + master data, publish against an INACTIVE machine) still to be exercised outside CI.

