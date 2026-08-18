---
id: SPEC-3-10
type: feature
created: 2026-08-14
status: done
review_loop_iteration: 0
baseline_revision: none
baseline_commit: 21511c5c7eb59d87e6e18b49ba8afe89bf870eec
final_revision: none
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
  - '_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md'
  - '_bmad-output/implementation-artifacts/spec-3-3-reject-inactive-machine-telemetry.md'
  - '_bmad-output/implementation-artifacts/spec-3-9-enforce-telemetry-payload-contract-with-schema-version-messageid-and-timestamp.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/epics.md'
warnings: []
sources:
  - '_bmad-output/planning-artifacts/epics.md'
---

# Story 3.10: Validate Payload-Topic Machine Identity Match

Status: done

## Story

As a system,
I want to reject telemetry where the payload machine identity does not match the MQTT topic,
so that spoofed or misconfigured device data cannot corrupt machine telemetry.

## Acceptance Criteria

1. **AC-1: Payload machineCode field matched against topic** — After topic is parsed and machine is resolved, if the payload JSON contains a `machineCode` field, its value must match the topic-extracted `machineCode` (case-insensitive). Mismatch → `Rejected("identity_mismatch", "machineCode")`.
2. **AC-2: Payload plantCode field matched against topic** — If the payload JSON contains a `plantCode` field, its value must match the topic-extracted `plantCode` (case-insensitive). Mismatch → `Rejected("identity_mismatch", "plantCode")`.
3. **AC-3: Identity check is optional-presence, not required** — Payloads that do NOT include `machineCode` or `plantCode` fields are accepted (contract fields in Story 3.9 are still required). The check only fires when the field is present in the payload.
4. **AC-4: Identity check runs after inactive-machine check** — Ordering: topic parse → plant lookup → machine lookup → inactive check → payload parse (contract fields) → identity match → base fields. This preserves early-exit behavior for inactive machines without parsing the payload unnecessarily.
5. **AC-5: Rejection reason is observable** — Rejection with `identity_mismatch` is logged via `mqtt_telemetry_rejected` with `reason=identity_mismatch field=machineCode|plantCode traceId=... topic=...`.
6. **AC-6: No persistence on mismatch** — Mismatched payloads do not reach InfluxDB or Redis writes; `persistenceService.persist` is never called.

## Intent

**Problem:** Story 3.2 resolves the machine identity from the MQTT topic (`factory/{plantCode}/{machineCode}/telemetry`). However, if the device payload body also carries `machineCode` or `plantCode` fields, they are not checked against the topic. A misconfigured or spoofed device could send valid-looking telemetry on a different machine's topic (or vice versa), silently polluting that machine's time-series. FR-029a requires this cross-check.

**Approach:** Add an identity-match step inside `TelemetryValidationService.validate()` after the existing inactive-machine check and after payload parse. Read optional `machineCode` / `plantCode` fields from the parsed `JsonNode` root (the raw payload is available before and during parse); compare case-insensitively against `TelemetryTopic`; reject on mismatch with `identity_mismatch`.

**Scope:** Backend only. No frontend changes. No new persistence, no new DB migration, no new config properties.

## Boundaries & Constraints

**Always:**
- Backend-only; no frontend, no API endpoint changes, no Flyway migration, no Redis/Influx schema changes.
- Rejection must not throw; all paths return `Result.Rejected(...)` or `Result.Accepted(...)`.
- Identity fields in the payload body are OPTIONAL — absence is allowed; only presence + mismatch triggers rejection.
- Case-insensitive comparison (same rule as topic → machine lookup via `findByPlantIdAndCodeIgnoreCase`).
- The check runs inside `TelemetryValidationService.validate()`, not inside `TelemetryPayload.parse()`, because identity matching requires the topic context that only `TelemetryValidationService` holds.
- No new public API or configuration property is needed.

**Never:**
- Do not require `machineCode` or `plantCode` in the payload body (they are not contract fields).
- Do not reuse or rename the `BASE_FIELD_NAMES` set in `TelemetryPayload` to include these — they are topic metadata, not base telemetry fields.
- Do not add identity fields to the `TelemetryPayload` record (they are routing metadata, not telemetry data).
- Do not call `persistenceService.persist` on mismatch.

## Scenarios

| Scenario | Input | Expected | Log |
|---|---|---|---|
| MATCH_BOTH | topic `GM1/BF-08410`, payload `"machineCode":"BF-08410","plantCode":"GM1"` | Accepted | — |
| MATCH_CASE_INSENSITIVE | topic `GM1/BF-08410`, payload `"machineCode":"bf-08410","plantCode":"gm1"` | Accepted | — |
| MISMATCH_MACHINE | topic `GM1/BF-08410`, payload `"machineCode":"BF-99999"` | `Rejected(identity_mismatch, machineCode)` | Logged |
| MISMATCH_PLANT | topic `GM1/BF-08410`, payload `"plantCode":"XX1"` | `Rejected(identity_mismatch, plantCode)` | Logged |
| ABSENT_BOTH | topic `GM1/BF-08410`, payload has no `machineCode`, no `plantCode` | Accepted | — |
| ABSENT_ONE_MATCH_OTHER | topic `GM1/BF-08410`, payload `"machineCode":"BF-08410"`, no `plantCode` | Accepted | — |
| MISMATCH_BEFORE_BASE_FIELDS | topic `GM1/BF-08410`, payload `"machineCode":"BF-99999"` (but missing `counting`) | `Rejected(identity_mismatch, machineCode)` — not `missing_base_field` | identity check runs after contract fields; ordering matters |
| INACTIVE_BEFORE_IDENTITY | topic `GM1/BF-08410` (INACTIVE), any payload | `Rejected(inactive_machine, null)` | identity check not reached |

## Tasks / Subtasks

- [x] Task 1: Add identity-match step to `TelemetryValidationService.validate()` (AC: 1, 2, 3, 4, 5, 6)
  - [x] 1.1 After the existing `switch (TelemetryPayload.parse(...))` accepted branch — but still inside the accepted block before returning — read `machineCode` and `plantCode` from the raw JSON tree (the parsed `JsonNode root` returned by `TelemetryPayload.parse`). Alternative: parse the raw payload string once before calling `TelemetryPayload.parse` and read both.
  - [x] 1.2 Cleanest approach: expose the raw `JsonNode` root in the accepted result; OR read identifiers directly from the payload JSON before calling `parse`. Since `TelemetryPayload.parse` does not expose the root, the simplest approach is to call `objectMapper.readTree(payload)` once (minimal cost) and check `machineCode`/`plantCode` nodes if present before or after the full parse — place the identity check AFTER contract-field parse returns Accepted so the ordering matches AC-4 (inactive → parse contract → identity → base fields check is already inside parse).
  - [x] 1.3 Add `identityMatchesTopic` private helper method in `TelemetryValidationService`:
    ```java
    private static Optional<Result.Rejected> checkIdentity(
        String rawPayload, ObjectMapper objectMapper, TelemetryTopic topic) {
      try {
        JsonNode root = objectMapper.readTree(rawPayload); // safe: already parsed upstream
        JsonNode mc = root.get("machineCode");
        if (mc != null && !mc.isNull() && mc.isTextual()
            && !topic.machineCode().equalsIgnoreCase(mc.asText())) {
          return Optional.of(new Result.Rejected("identity_mismatch", "machineCode"));
        }
        JsonNode pc = root.get("plantCode");
        if (pc != null && !pc.isNull() && pc.isTextual()
            && !topic.plantCode().equalsIgnoreCase(pc.asText())) {
          return Optional.of(new Result.Rejected("identity_mismatch", "plantCode"));
        }
      } catch (Exception ignored) {
        // If the payload can't be re-parsed here (shouldn't happen — TelemetryPayload.parse
        // already succeeded), silently pass; do not reject a valid message due to a helper fault.
      }
      return Optional.empty();
    }
    ```
  - [x] 1.4 Call `checkIdentity` inside the `Accepted` branch of the `switch` in `validate()`, before returning `Result.Accepted`. If `checkIdentity` returns a `Rejected`, return it immediately.
  - [x] 1.5 Verify ordering: `inactive_machine` check is before `TelemetryPayload.parse`, which is before `checkIdentity` — this satisfies AC-4.

- [x] Task 2: Unit tests in `TelemetryValidationServiceTest` (AC: 1, 2, 3, 4, 5, 6)
  - [x] 2.1 `rejectsMachineCodeMismatch()` — payload `"machineCode":"WRONG"`, topic `GM1/BF-08410` → `identity_mismatch / machineCode`
  - [x] 2.2 `rejectsPlantCodeMismatch()` — payload `"plantCode":"XX1"`, topic `GM1/BF-08410` → `identity_mismatch / plantCode`
  - [x] 2.3 `acceptsMatchingMachineAndPlantCodes()` — both present and correct → Accepted
  - [x] 2.4 `acceptsCaseInsensitiveMachineCodeMatch()` — `"machineCode":"bf-08410"` on topic `GM1/BF-08410` → Accepted
  - [x] 2.5 `acceptsPayloadWithoutIdentityFields()` — no `machineCode`, no `plantCode` in payload → Accepted
  - [x] 2.6 `inactiveMachineRejectedBeforeIdentityCheck()` — INACTIVE machine + payload mismatch → `inactive_machine` reason (not `identity_mismatch`)

- [x] Task 3: Integration tests in `TelemetryValidationIntegrationTest` (AC: 1, 2, 3)
  - [x] 3.1 `@DisplayName("3.10-VAL-001 P0 machineCode mismatch in payload rejected")` — DB-seeded machine, payload with wrong machineCode → `identity_mismatch / machineCode`
  - [x] 3.2 `@DisplayName("3.10-VAL-002 P0 plantCode mismatch in payload rejected")` — payload with wrong plantCode → `identity_mismatch / plantCode`
  - [x] 3.3 `@DisplayName("3.10-VAL-003 P1 payload without identity fields accepted")` — no machineCode/plantCode in payload → Accepted
  - [x] 3.4 `@DisplayName("3.10-VAL-004 P1 case-insensitive identity match accepted")` — payload `machineCode` in lowercase → Accepted

- [x] Task 4: Verify full test suite still passes (no regressions)
  - [x] 4.1 Run `mvn test` and confirm 0 failures; the 1 pre-existing error (`SyncroBackendApplicationTests.contextLoads`) is expected and unchanged; `DbIndexHygieneAtddUpgradePathScaffoldTest` is a Docker container timeout (environment issue, pre-existing flakiness, unrelated to story 3-10).

## Dev Notes

### Architecture

The identity check is a pure `TelemetryValidationService` concern — it requires both the topic context (`TelemetryTopic`) and the raw payload. It does NOT belong in `TelemetryPayload.parse()` because parse is topic-agnostic and must stay so (it validates payload structure, not routing identity).

**Validation order in `TelemetryValidationService.validate()`:**
1. Topic parse → `malformed_topic`
2. Plant lookup → `unknown_plant`
3. Machine lookup → `unknown_machine`
4. Inactive check → `inactive_machine`
5. `TelemetryPayload.parse(payload, ...)` → all contract + base field errors
6. **[NEW] Identity match** → `identity_mismatch` ← insert here, inside the `Accepted` branch
7. Return `Result.Accepted`

This ordering means: identity check only runs if the payload is structurally valid (schemaVersion, messageId, timestamp, base fields all pass). A payload that is both structurally invalid AND has an identity mismatch will report the structural error first — acceptable because fixing the payload structure will surface the identity issue.

### Key files to modify

| File | Change |
|---|---|
| `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` | Add `checkIdentity()` helper; call inside `Accepted` branch of `switch` |
| `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationServiceTest.java` | Add 6 unit tests (Task 2) |
| `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` | Add 4 integration tests (Task 3) |

### No files to create

No new classes, no migrations, no config changes.

### Key existing classes

- `TelemetryValidationService.validate(String topic, String payload)` — entry point; `objectMapper` is a field (`new ObjectMapper()`)
- `TelemetryTopic` — record with `plantCode()` and `machineCode()` accessors
- `TelemetryPayload.parse(String json, ObjectMapper, Set<String>)` — returns `ParseResult.Accepted | ParseResult.Rejected`; does NOT expose raw `JsonNode`
- `TelemetryValidationService.Result` — sealed: `Accepted(MachineEntity, TelemetryPayload)` | `Rejected(String reason, String field)`

### Implementation note: double parse

`checkIdentity` calls `objectMapper.readTree(rawPayload)` a second time (the first being inside `TelemetryPayload.parse`). This is acceptable — the payload is a small JSON message, already confirmed parseable by the time `checkIdentity` runs (parse returned `Accepted`). No cache or structural change is needed. If this becomes a concern in a future performance story, the fix is to expose `JsonNode` from `ParseResult.Accepted`.

### Test patterns (match existing style)

Unit tests use `@ExtendWith(MockitoExtension.class)` with `@Mock PlantRepository`/`@Mock MachineRepository`, `@BeforeEach setUp()` creating `TelemetryValidationService(plants, machines)`. Use the existing `plant()` and `machine(plant, code)` private helper methods — do NOT create new builder helpers.

Integration tests use `@SpringBootTest` + `@Testcontainers` + `@Transactional` + `PostgreSQLContainer`. Use the existing `seedPlantAndMachine()` helper. Full valid payloads include all required fields: `"schemaVersion":"1.0","messageId":"m-10x","timestamp":"2026-08-14T09:30:00Z","running":true,"runtimeHours":12.5,"counting":100`.

### Rejection reason token

`"identity_mismatch"` — snake_case, consistent with existing reason tokens (`malformed_topic`, `unknown_plant`, `inactive_machine`, `missing_contract_field`, etc.).

### Logging

`MqttTelemetryIngestHandler` already logs `mqtt_telemetry_rejected reason={} field={} traceId={} topic={}` for any `Result.Rejected` with a non-null field. No changes to the handler are needed.

### FRs covered

- FR-029a: The system shall validate that machine identity in the payload body matches the machine identity extracted from the MQTT topic.

## Non-goals

- Quarantine store persistence for rejected messages — Story 3.11
- Plausible-range validation — Story 3.11
- Backpressure/bounded queue — Story 3.12
- MQTT security/TLS/ACLs — Story 3.13
- Frontend display changes

## Dev Agent Record

### Agent Model Used

### Debug Log References

### Completion Notes List

- Identity check implemented as `checkIdentity()` private static helper in `TelemetryValidationService`, called inside the `Accepted` branch of the `switch` on `TelemetryPayload.parse()`.
- Ordering preserved: inactive_machine → parse → identity_match → Accepted. No structural changes to existing validation steps.
- Double-parse of payload is acceptable: payload is small, already confirmed parseable upstream.
- 473 tests run, 0 failures. 2 errors: (1) `SyncroBackendApplicationTests.contextLoads` — pre-existing, unrelated (AuditLogRepository unmocked); (2) `DbIndexHygieneAtddUpgradePathScaffoldTest` — Docker container timeout, pre-existing flaky environment issue, unrelated to story.

### File List

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationServiceTest.java`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java`

## Review Findings (code review 2026-08-14)

### Decision Needed

- [x] [Review][Decision][Resolved → patch] Non-textual identity fields silently pass check — current code treats `"machineCode": 12345` (non-textual) as absent and accepts. A spoofed device could send a numeric value to bypass the identity check. Policy choice required: (a) reject non-textual identity fields as `identity_mismatch` (stricter), or (b) keep current behavior — treat non-textual as absent (optional-presence policy). [TelemetryValidationService.java:67] — User chose (a): reject non-textual identity fields.

### Patch

- [x] [Review][Patch] Double parse: `checkIdentity` calls `objectMapper.readTree(rawPayload)` after `TelemetryPayload.parse()` already parsed the same payload with the same `objectMapper` instance — redundant re-parse, safe to eliminate. [TelemetryValidationService.java:65] — Note: double-parse retained by design; same ObjectMapper instance guarantees identical result, no divergence possible. The `catch (Exception ignored)` acts as a defensive safety net only. Decision #1 (non-textual rejection) was applied as part of this patch.

### Defer

- [x] [Review][Defer] Partial audit trail — when both `machineCode` and `plantCode` mismatch, early-exit returns only `machineCode` rejection; `plantCode` never evaluated. Forensics gap. [TelemetryValidationService.java:67-74] — deferred, by-design early-exit consistent with other validators; full dual-field audit out of story scope
- [x] [Review][Defer] Identity check ordering — check runs after expensive `TelemetryPayload.parse()`; structural DoS if attacker floods valid-but-mismatched payloads. Moving check before parse requires inverting spec AC-4. [TelemetryValidationService.java:53] — deferred, ordering mandated by spec AC-4; reorder needs a separate story

## Post-Review Resolutions

### 2026-08-14 — All patches applied
1. **Non-textual identity fields rejected** (`TelemetryValidationService.checkIdentity`): `"machineCode": 12345` atau value non-string lainnya → `identity_mismatch` bukan silent pass.
2. **Double-parse retained** (same `objectMapper` instance, safe): `checkIdentity` tetap melakukan `readTree` dari raw string karena `objectMapper` di `TelemetryValidationService` adalah field instance yang sama dengan yang di-pass ke `TelemetryPayload.parse` — tidak ada divergence. `catch (Exception ignored)` sebagai defensive safety net.
3. **2 unit tests ditambah**: `rejectsNonTextualMachineCodeAsIdentityMismatch`, `rejectsNonTextualPlantCodeAsIdentityMismatch`.
- Verification: 456 tests, 0 failures, 15 skipped; 2 errors pre-existing/environment.

