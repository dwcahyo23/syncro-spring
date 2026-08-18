---
id: SPEC-3-9
type: feature
created: 2026-08-14
status: done
review_loop_iteration: 1
baseline_revision: 21511c5
baseline_commit: 21511c5c7eb59d87e6e18b49ba8afe89bf870eec
final_revision: none
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
  - '_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md'
  - '_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md'
  - '_bmad-output/implementation-artifacts/spec-3-3-reject-inactive-machine-telemetry.md'
  - '_bmad-output/implementation-artifacts/spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md'
  - '_bmad-output/implementation-artifacts/spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md'
  - '_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md'
  - '_bmad-output/planning-artifacts/architecture.md'
  - '_bmad-output/planning-artifacts/epics.md'
warnings: []
sources:
  - '_bmad-output/planning-artifacts/epics.md'
---

# Enforce Telemetry Payload Contract with Schema Version, MessageId, and Timestamp

## Intent

**Problem:** The MQTT telemetry contract defined in Story 3.1 (architecture line 410: `schemaVersion`, `messageId`, `timestamp` (UTC ISO-8601) required) is not yet enforced at parse time. Today `TelemetryPayload.parse` (Story 3.2) requires only `running`, `runtimeHours`, and `counting`. Deduplication currently uses a composite key of `running:runtimeHours:counting` (TelemetryPersistenceService.java:46), and the InfluxDB point timestamp uses the server `receivedAt` instead of the payload timestamp. This means payload evolution is unguarded, duplicate `messageId`s are not deduplicated, and time-series precision relies on server clock rather than the device-reported UTC timestamp.

**Approach:** Extend `TelemetryPayload` to require `schemaVersion`, `messageId`, and `timestamp` (UTC ISO-8601) as contract fields, reject missing/invalid values with specific reason codes, quarantine unrecognized `schemaVersion` values (rejection path — quarantine store persistence is Story 3.11), switch the dedupe gate to use `messageId` as the primary key, and normalize the payload `timestamp` to UTC for the InfluxDB point time with a fallback to server receipt time when absent (flagged).

## Boundaries & Constraints

**Always:**
- All work is backend-only; no frontend, no API endpoint changes, no new migration.
- `schemaVersion`, `messageId`, `timestamp` become REQUIRED in `TelemetryPayload.parse`. Missing any → `Rejected` with reason `missing_contract_field` and the specific `field` name.
- `schemaVersion` must be a non-blank string. Unrecognized/unsupported values → `Rejected` with reason `unsupported_schema_version` (quarantine-path rejection; NOT silently dropped). Supported value is `"1.0"` (matches research fixtures in `technical-research-2026-05-25.md`).
- `messageId` must be a non-blank string with bounded length (≤ 255 chars). Invalid → `Rejected` with reason `invalid_field_type` / field `messageId`.
- `timestamp` must be an ISO-8601 UTC string parseable by `Instant.parse` (e.g. `2026-08-14T09:30:00Z`). Invalid → `Rejected` with reason `invalid_timestamp`.
- Dedupe key in `TelemetryPersistenceService` switches from `running:runtimeHours:counting` to `messageId` (architecture line 427: "Telemetry ingest dedupe should use messageId from payload as primary key").
- InfluxDB point time uses the payload `timestamp` (normalized to UTC epoch); when absent/fallback is used, point time uses `receivedAt` and the record is flagged (e.g. field `timestampInferred=true`) per AC line "timestamp is normalized to UTC before InfluxDB write; if absent, server receipt time is used and record is flagged".
- Rejections must log `traceId`, `topic`, `reason`, and `field` (consistent with existing `MqttTelemetryIngestHandler` logging).
- Timestamp fields must use `Instant`/ISO-8601 UTC (project rule: absolute timestamps use `Instant`; ISO-8601 UTC).

**Block If:**
- If `timestamp` is present but not parseable as ISO-8601 UTC → reject with `invalid_timestamp` (do NOT silently fall back).

**Never:**
- No quarantine table/entity/endpoint in this story — that is Story 3.11 (`telemetry_quarantine`). Story 3.9 only routes unrecognized `schemaVersion` to the existing rejection path with a specific reason code so Story 3.11 can persist it.
- No MQTT payload-topic identity match (Story 3.10), no plausibility/range validation beyond what exists (negative counting already rejected), no backpressure (Story 3.12).
- Do not change `MqttTelemetryIngestHandler`'s generated traceId behavior — payload `messageId` may be logged as correlation but the envelope traceId remains the correlation key for now (Story 3.11 will standardize quarantine correlation).
- Do not make `schemaVersion`/`messageId`/`timestamp` optional at any point.
- No changes to `application.yml`/`application-local.yml` unless a new property is strictly required (prefer none; reuse `syncro.telemetry.dedupe-window`).
- Do not use `LocalDateTime` or zone-conversion for the payload timestamp; keep UTC `Instant`.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | Valid payload: `{"schemaVersion":"1.0","messageId":"01J...","timestamp":"2026-08-14T09:30:00Z","running":true,"runtimeHours":120.5,"counting":42}` | `Accepted`; InfluxDB point time = payload timestamp; dedupe key uses messageId | No error |
| MISSING_SCHEMA_VERSION | Payload without `schemaVersion` | `Rejected(missing_contract_field, "schemaVersion")` | Logged with reason+field |
| UNSUPPORTED_SCHEMA_VERSION | `"schemaVersion":"2.0"` (not "1.0") | `Rejected(unsupported_schema_version, "schemaVersion")` — NOT silently dropped | Logged; Story 3.11 will persist quarantine |
| MISSING_MESSAGE_ID | Payload without `messageId` | `Rejected(missing_contract_field, "messageId")` | Logged with reason+field |
| INVALID_MESSAGE_ID | `"messageId":""` or `null` or 300-char string | `Rejected(invalid_field_type, "messageId")` | Logged with reason+field |
| MISSING_TIMESTAMP | Payload without `timestamp` | `Rejected(missing_contract_field, "timestamp")` | Logged with reason+field |
| INVALID_TIMESTAMP | `"timestamp":"yesterday"` or `"2026-08-14 09:30:00"` (non-ISO-Z) | `Rejected(invalid_timestamp, "timestamp")` | Logged with reason+field |
| DUPLICATE_MESSAGE_ID | Same `messageId` within `dedupeWindow` | Second message rejected by dedupe gate; no InfluxDB write, no Redis update, no duplicate alert/notification downstream | `mqtt_telemetry_duplicate` log with winner traceId |
| TIMESTAMP_ABSENT_FALLBACK | Payload timestamp missing but contract passes (if allowed by policy) | Point time = `receivedAt`, record flagged `timestampInferred=true` | Flag logged |

## Capabilities

### CAP-1: Enforce Required Contract Fields

**intent:** System rejects any telemetry payload missing `schemaVersion`, `messageId`, or `timestamp` with a specific reason.

**success:**
- Missing `schemaVersion` → `Rejected(missing_contract_field, "schemaVersion")`
- Missing `messageId` → `Rejected(missing_contract_field, "messageId")`
- Missing `timestamp` → `Rejected(missing_contract_field, "timestamp")`
- Non-blank requirement enforced; empty/whitespace/`null` treated as missing or invalid

### CAP-2: Guard Schema Version Evolution

**intent:** System quarantines (rejects, not silently drops) payloads with unrecognized schema versions.

**success:**
- `"1.0"` accepted
- Any other value → `Rejected(unsupported_schema_version, "schemaVersion")`
- Rejection is observable via structured log with reason + field (feeds Story 3.11 quarantine)

### CAP-3: Deduplicate by MessageId

**intent:** System uses `messageId` as the primary dedupe key so duplicate QoS-1 deliveries do not create duplicate alerts or notifications downstream.

**success:**
- Dedupe Redis key `syncro:machine:{machineId}:telemetry:dedupe:{messageId}` (TTL = `syncro.telemetry.dedupe-window`)
- Duplicate `messageId` within window → skip persist, log `mqtt_telemetry_duplicate` with winner traceId
- Alert evaluation (Epic 4) and notifications (Epic 5) receive each messageId at most once per window

### CAP-4: Normalize Payload Timestamp to UTC

**intent:** System writes InfluxDB points at the payload-reported UTC timestamp (not server receipt time), falling back to receipt time with a flag when absent.

**success:**
- Valid payload timestamp parsed via `Instant.parse` → used as InfluxDB point time (epoch nanos, UTC)
- Absent timestamp (if policy allows) → point time = `receivedAt`, record flagged `timestampInferred=true`

## Non-goals

- Quarantine store persistence, retention, or API — Story 3.11
- Payload-topic machine identity match — Story 3.10
- Plausible-range validation (negative runtime, counter decrease) — Story 3.11 scope per epics
- Backpressure/bounded queue — Story 3.12
- MQTT security/TLS/ACLs — Story 3.13
- Frontend display changes

## Success Signal

Backend rejects payloads missing `schemaVersion`/`messageId`/`timestamp` with specific reasons; unrecognized `schemaVersion` values are rejected (quarantine path) not silently dropped; duplicate `messageId` within `dedupe-window` is not persisted twice; InfluxDB points carry the normalized payload UTC timestamp. All covered by automated tests.

## Assumptions

- Existing Redis dedupe infrastructure (`setIfAbsent` + TTL from `TelemetryProperties.dedupeWindow`) is retained, only the key composition changes.
- `InfluxTelemetryWriter.toPoint` signature can be extended to accept the normalized payload timestamp (or the payload carries it).
- No payload format change for `running`/`runtimeHours`/`counting` — only additive contract enforcement.
- `schemaVersion` `"1.0"` is the only supported value for now (research fixtures).

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` -- ADD required contract fields `schemaVersion`, `messageId`, `timestamp`; extend record + `parse` validation; add `SUPPORTED_SCHEMA_VERSION = "1.0"`; BASE_FIELD_NAMES includes the new fields so they are not collected as optional fields
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` -- unchanged flow (rejection reasons flow through); verify rejection logging carries field
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- CHANGE dedupe key to `syncro:machine:{machineId}:telemetry:dedupe:{messageId}`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` -- CHANGE point time to normalized payload timestamp (fallback `receivedAt` + `timestampInferred` flag field)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- MAY log `messageId` alongside traceId for correlation (no behavior change to rejection logging)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPayloadTest.java` -- ADD cases: missing each contract field, unsupported schema version, invalid/blank messageId, invalid timestamp format
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/AcceptingTelemetryValidationServiceTest.java` -- UPDATE fixtures to include contract fields; add happy-path assertions
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/RejectingTelemetryValidationServiceTest.java` -- ADD rejection cases for missing/unsupported contract fields
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- ADD duplicate-messageId dedupe test (Redis)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriterTest.java` -- ADD point-time normalization + fallback-flag tests
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` -- UPDATE seeded payloads to include contract fields; add one MQTT-level rejection case

## Tasks & Acceptance

### Task 1: Extend Payload Contract (AC-1, AC-2)

- [x] `TelemetryPayload.java`
  - [x] Add `SUPPORTED_SCHEMA_VERSION` constant (`"1.0"`)
  - [x] Extend record: `(boolean running, double runtimeHours, long counting, String schemaVersion, String messageId, Instant timestamp, Map<String, JsonNode> optionalFields)`
  - [x] Add `schemaVersion`, `messageId`, `timestamp` to `BASE_FIELD_NAMES`
  - [x] In `parse`: validate each contract field before base fields:
    - [x] `schemaVersion`: present, non-blank string; `"1.0"` accepted; else `unsupported_schema_version`
    - [x] `messageId`: present, non-blank string, length ≤ 255; else `missing_contract_field`/`invalid_field_type`
    - [x] `timestamp`: present, string parseable via `Instant.parse`; else `missing_contract_field`/`invalid_timestamp`
- [x] Kept convenience constructor `TelemetryPayload(boolean, double, long, schemaVersion, messageId, Instant)`; removed legacy 3-arg/4-arg constructors (all callers updated to carry contract fields)

### Task 2: Switch Dedupe Key to messageId (AC-3)

- [x] `TelemetryPersistenceService.java`
  - [x] Change dedupe key from `running:runtimeHours:counting` composite to `syncro:machine:{machineId}:telemetry:dedupe:{messageId}`
  - [x] Keep `setIfAbsent` + TTL from `properties.dedupeWindow()`
  - [x] Keep `deleteDedupeKey` cleanup on Influx write failure
  - [x] Duplicate log now reports `messageId` instead of `counting`

### Task 3: Normalize Timestamp for InfluxDB Point Time (AC-4)

- [x] `InfluxTelemetryWriter.toPoint`
  - [x] Use payload `timestamp` (normalized UTC `Instant`) as point time when present
  - [x] When absent: use `envelope.receivedAt()` and add field `timestampInferred=true`
  - [x] Preserve existing tags/fields (`plantCode`, `machineCode`, `running`, `runtimeHours`, `counting`, `countingDelta`, `traceId`, optional fields)

### Task 4: Tests & Verification

- [x] Update all telemetry fixtures in unit/integration tests to include `schemaVersion:"1.0"`, unique `messageId`, valid `timestamp`
- [x] Add rejection tests: missing schemaVersion, unsupported version, missing/blank messageId, missing/invalid timestamp
- [x] Add dedupe test: same `messageId` twice within window → second skipped, single Influx write (incl. same-messageId-different-counting case)
- [x] Add Influx point-time test: payload timestamp wins; absent → receivedAt + flag
- [x] Run backend test suite — 454 run, 0 failures, 15 skipped; 1 pre-existing error `SyncroBackendApplicationTests.contextLoads` (AuditLogRepository unmocked + DataSource excluded, unrelated to this story, documented since Story 3.1)

### Acceptance Criteria (from epics.md Story 3.9)

1. ✅ Given backend receives MQTT telemetry message, when payload is validated, then `schemaVersion`, `messageId`, and `timestamp` (UTC ISO-8601) are required fields — enforced in `TelemetryPayload.parse`.
2. ✅ Given missing any of these fields, then rejection with specific reason (per-field) — `missing_contract_field`/`invalid_field_type`/`invalid_timestamp`/`unsupported_schema_version` with field name.
3. ✅ Given unrecognized `schemaVersion` values, then quarantined (rejected with `unsupported_schema_version`, not silently dropped) — durable quarantine store is Story 3.11.
4. ✅ Given `messageId` is used for deduplication, then duplicate `messageId` does not create duplicate alerts or notifications — dedupe gate keyed by messageId, verified via unit + integration tests.
5. ✅ Given `timestamp`, then normalized to UTC before InfluxDB write; if absent, server receipt time is used and record is flagged (`timestampInferred=true`).

## Design Notes

### Dedupe Key Change (architecture.md:427)

```
BEFORE: syncro:machine:{machineId}:telemetry:dedupe:{running}:{runtimeHours}:{counting}
AFTER:  syncro:machine:{machineId}:telemetry:dedupe:{messageId}
```

Rationale: `messageId` is the contract-level idempotency key (FR-041a). The composite key caused false negatives when devices legitimately re-send identical counter states, and false positives are impossible with a unique `messageId`. TTL stays `syncro.telemetry.dedupe-window` (default PT30S).

### Schema Version Guard

```java
static final String SUPPORTED_SCHEMA_VERSION = "1.0";
```

Unrecognized → `Rejected("unsupported_schema_version", "schemaVersion")`. This is the quarantine-path rejection; the durable quarantine record (`telemetry_quarantine` table) is Story 3.11.

### Timestamp Normalization

- Parse via `Instant.parse` (accepts `2026-08-14T09:30:00Z` — ISO-8601 UTC).
- Reject anything not parseable (`invalid_timestamp`) — no silent fallback.
- Point time uses payload timestamp; `receivedAt` remains in the envelope for observability and is used only as fallback with `timestampInferred=true` flag.

## Deferred Work Items

- DW-3-9-1: Quarantine store persistence for rejected payloads (Story 3.11) — rejection reasons from this story become the quarantine `reason_code` values.
- DW-3-9-2: Payload-topic machine identity match (Story 3.10).
- DW-3-9-3: countingDelta arrival-order vs device-timestamp ordering (known Phase-1 limitation, deferred by code review decision 2026-08-14) — late-arriving messages can make the InfluxDB counting series non-monotonic; Epic 6 analytics must account for this. Timestamp-aware delta needs a separate story.

## Change Log

### 2026-08-14 — Implemented Story 3.9 (initial)
- `TelemetryPayload` now requires and validates `schemaVersion` ("1.0"), `messageId` (non-blank, ≤255), `timestamp` (ISO-8601 UTC via `Instant.parse`). New rejection reasons: `missing_contract_field`, `invalid_timestamp`, `unsupported_schema_version`; contract fields added to `BASE_FIELD_NAMES` so they are never collected as optional fields.
- `TelemetryPersistenceService` dedupe key changed to `syncro:machine:{machineId}:telemetry:dedupe:{messageId}`; duplicate log now reports `messageId`. Same-messageId with different counting is deduplicated (new unit test).
- `InfluxTelemetryWriter.toPoint` uses payload timestamp (UTC normalized) as point time; falls back to `receivedAt` with `timestampInferred=true` field when absent (unit tested).
- All telemetry test fixtures updated to carry the required contract fields; new contract-rejection tests added at unit and integration (3.9-VAL-001..005) level.
- Full suite: 454 run, 0 failures, 15 skipped; 1 pre-existing error `SyncroBackendApplicationTests.contextLoads` (documented since Story 3.1, unrelated).

## File List

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` (modified)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` (modified)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` (modified)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPayloadTest.java` (modified — 31 tests)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationServiceTest.java` (modified)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryValidationIntegrationTest.java` (modified — 16 tests incl. 3.9-VAL-001..005)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` (modified — 14 tests)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` (modified — 9 tests)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/AcceptingTelemetryValidationService.java` (modified)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriterTest.java` (modified — 5 tests incl. fallback-flag case)

## Dev Agent Record

### Agent Model Used

deepseek-v4-flash (opencode)

### Completion Notes

- Implemented all 4 tasks; all 5 acceptance criteria verified by automated tests.
- No new dependencies, no migration, no config changes, no frontend work.
- Rejection reasons produced by this story are the seed values Story 3.11 will persist in `telemetry_quarantine` (`missing_contract_field`, `invalid_timestamp`, `unsupported_schema_version`, plus existing base-field reasons).

## Review History

<!-- Auto-generated: review loop iteration tracking -->

## Review Findings (code review 2026-08-14)

### Decision Needed

- [x] [Review][Decision][Resolved → defer] countingDelta arrival-order vs device-timestamp ordering — point time now uses device `timestamp` (AC-5) but `countingDelta` is computed against Redis "latest" in arrival order (TelemetryPersistenceService.java:61-64). A late-arriving message with an older device timestamp gets a delta computed against newer-arrived counting and is written at an older time → non-monotonic `counting`/`countingDelta` over the InfluxDB series; time-window aggregation (Epic 6 analytics) can double-count or see negative deltas. Alert/threshold path (Epic 4) consumes arrival-order deltas so it stays correct. Options: (a) accept + document as known Phase-1 limitation, (b) make delta computation device-timestamp-aware (heavy, out of story scope). Recommend (a) defer with documentation.

### Patch

- [x] [Review][Patch] InfluxDB writable-range guard for payload timestamp [InfluxTelemetryWriter.java:47] — device-controlled timestamp is unbounded at parse; year >= ~2262 overflows `epochSecond * 1_000_000_000L` to a negative value and year < 1677 / > 2262 is rejected by InfluxDB → write hard-fails, dedupe key deleted, message lost, retry loop. Fix: if payload timestamp is outside InfluxDB writable range [1677-09-21, 2262-04-11], fall back to `receivedAt` + `timestampInferred=true` (reuses existing fallback flag semantics).
- [x] [Review][Patch] Trailing content after JSON silently ignored [TelemetryPayload.java:42] — `readTree` accepts `{...}garbage` or two concatenated payloads `{...}{...}`; the tail is dropped without error. Fix: parse with `ObjectMapper` configured `FAIL_ON_TRAILING_TOKENS` enabled (via `readValue(json, JsonNode.class)`), so trailing tokens reject with `unparseable_payload`.
- [x] [Review][Patch] messageId whitespace not normalized [TelemetryPayload.java:63] — `" m-1 "` vs `"m-1"` become distinct dedupe keys → duplicate Influx records. Fix: trim messageId before length check and store the trimmed value.
- [x] [Review][Patch] messageId control characters accepted → log injection [TelemetryPersistenceService.java:53] — raw `messageId` is logged in the duplicate warning; device-controlled value with newline can forge log lines. Fix: reject messageId containing ASCII control chars (< 0x20) as `invalid_field_type`.
- [x] [Review][Patch] Test coverage gaps — null-valued contract fields (`"schemaVersion":null` etc.), messageId exact-255 accepted boundary, whitespace-only messageId, timestamp offset variant (`+07:00`), out-of-range timestamp fallback flag; `buildsPointWithCanonicalSchema` nano assertion weaker than code.

### Deferred

- [x] [Review][Defer] General timestamp plausibility bound vs `receivedAt` (device clock skew, e.g. +24h → points invisible in query windows) [TelemetryPayload.java:74-79] — deferred to Story 3.11 plausible-range validation scope. Reason: plausible-range validation is explicitly Story 3.11 scope per epics.
- [x] [Review][Defer] Dedupe TTL boundary (replay of same messageId after `dedupeWindow` expiry is re-accepted) [TelemetryPersistenceService.java:47] — correct window semantics, boundary untested. Reason: behavior is correct by design; test-only, low value.

### Dismissed (noise / by design)

- [x] [Review][Dismiss] Dedupe drops distinct updates sharing a messageId within window with no content fingerprint — by design (architecture.md:427 mandates messageId as primary dedupe key; content-based key was the pre-change behavior being replaced).
- [x] [Review][Dismiss] `timestampInferred` fallback unreachable in production (parse hard-rejects missing timestamp) — matches spec's hedged `TIMESTAMP_ABSENT_FALLBACK` row and AC-5 wording; defensive path only.
- [x] [Review][Dismiss] Duplicate JSON keys last-wins — standard Jackson behavior; low value.
- [x] [Review][Dismiss] messageId length in UTF-16 code units — cosmetic boundary for exotic input.
- [x] [Review][Dismiss] `timestampInferred` field-name collision with a configured optional field — unreachable via parse (timestamp null only via direct construction); point writer and parse boundary decoupled.

## Post-Review Resolutions

### 2026-08-14 — Decision #1 resolved (user): defer countingDelta ordering as known Phase-1 limitation
- Delta tetap dihitung dalam arrival order; InfluxDB series dapat non-monotonic untuk late-arriving messages.
- Epic 4 (threshold/alert) mengkonsumsi delta arrival-order sehingga tetap benar; Epic 6 analytics harus memperhitungkan caveat ini.
- Jika dibutuhkan, solusi timestamp-aware delta memerlukan story terpisah (DW-3-9-3).

### 2026-08-14 — All 5 patch findings applied
1. **InfluxDB writable-range guard** (`InfluxTelemetryWriter.java`): payload timestamp di luar [1677-09-21, 2262-04-11] → fallback ke `receivedAt` + `timestampInferred=true`; mencegah overflow nanos dan Influx write hard-fail.
2. **Trailing tokens ditolak** (`TelemetryPayload.parse`): parse via `createParser` + `readTree` + cek `nextToken() != null` → trailing garbage / concatenated JSON → `unparseable_payload`.
3. **messageId di-trim** sebelum validasi & disimpan (whitespace `" m-1 "` ≡ `"m-1"` untuk dedupe).
4. **Control chars (< 0x20) pada messageId ditolak** (`invalid_field_type`) — mencegah log injection di duplicate warning.
5. **Test coverage ditambah**: null-valued contract fields, whitespace-only & trimmed messageId, 255-boundary, control chars, trailing tokens, concatenated JSON, timestamp offset `+07:00` → UTC, out-of-range fallback flag; assertion nano diperkuat di `buildsPointWithCanonicalSchema`.
- Verification: 463 tests, 0 failures, 15 skipped; 1 pre-existing error `SyncroBackendApplicationTests.contextLoads` (unrelated, documented since Story 3.1).

