---
title: '3-6 Support Optional Machine Telemetry Fields'
type: 'feature'
created: '2026-08-09'
baseline_revision: '801f9bbd0fa11c442ed2a3eabb0811a0ca06a697'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
final_revision: '461aa3f00cf5d8d7e13fcd1d13ef84e3375981ec'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** The telemetry contract is fixed to exactly `running`, `runtimeHours`, and `counting`; every other payload key is silently dropped, and there is no per-machine declaration of which optional parameters a machine sends. FR-032/FR-038 require accepting up to 10 configured optional parameters per machine (power analyzer, vibration, quality, custom sensors) so future sensors work without changing the base contract.

**Approach:** Declare a per-machine allowlist of optional field names (new nullable `jsonb` column `machines.optional_telemetry_fields`, surfaced through machine CRUD). `TelemetryPayload.parse` collects configured scalar fields from the payload; the persistence layer threads them into the InfluxDB `telemetry` point (type-inferred) and the Redis latest hash (`optional.<name>` keys). Unconfigured fields are ignored; base fields stay required.

## Boundaries & Constraints

**Always:**
- Config: `machines.optional_telemetry_fields` = JSON array of field-name strings; nullable; null ≡ empty list. Normalized on create/update: trim, drop blanks, dedupe preserving order, reject names not matching `^[A-Za-z0-9_]+$` or longer than 64 chars, reject more than 10, and reject reserved names `{running, runtimeHours, counting, countingDelta, plantCode, machineCode, traceId, receivedAt}` (base contract + Influx tag/field + Redis hash collisions). Rejections surface as `MachineValidationException` (HTTP 400).
- Optional field values in the payload must be JSON scalars: finite number, boolean, or string. A configured field present with a non-scalar value (object, array, null) → reject payload `invalid_field_type`, `field=<name>`.
- Unconfigured/unknown top-level payload fields are ignored (not stored, not rejected) — this is the configured machine field rule for unknown fields.
- Base fields `running`, `runtimeHours`, `counting` remain required; validation unchanged.
- Persist: configured optional fields go into the InfluxDB point (integral number → long `i`, floating → double, boolean, string) and the Redis latest hash as `optional.<name>` = `asText()` value. Dedupe key stays base-fields-only.
- No frontend changes; Stories 3.7/3.8 render configured fields from `machine.optionalTelemetryFields` + latest-hash `optional.*` keys.
- Migration: single additive, forward-only `V18`; no data backfill (existing rows stay null).

**Block If:**
- If per-field data-type configuration (bit-width, signed/unsigned, unit, tolerance) is required at ingest time. Phase 1 config is a name-only allowlist; value types are inferred from JSON at runtime. Adding per-field type config requires architecture input.

**Never:**
- No change to base required fields, `counting` type/semantics, the delta computation, or the dedupe key formula.
- No `schemaVersion`/`messageId`/`timestamp` envelope handling (no owning story; out of scope).
- No per-machine InfluxDB tables or measurements; shared `telemetry` measurement only.
- Do not reject or quarantine unconfigured unknown fields.
- Do not persist configured optional fields into the Redis hash under bare names — always `optional.<name>` to avoid collisions.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| CONFIGURED_FLOAT | machine configured `vibration`; payload `vibration:2.4` | Accepted; Influx `vibration=2.4`; Redis `optional.vibration=2.4` | No error expected |
| CONFIGURED_INT | machine configured `rpm`; payload `rpm:1200` | Accepted; Influx `rpm=1200i`; Redis `optional.rpm=1200` | No error expected |
| CONFIGURED_BOOL | machine configured `heaterOn`; payload `heaterOn:true` | Accepted; Influx `heaterOn=true`; Redis `optional.heaterOn=true` | No error expected |
| CONFIGURED_STRING | machine configured `qualityGrade`; payload `qualityGrade:"A"` | Accepted; Influx `qualityGrade="A"`; Redis `optional.qualityGrade=A` | No error expected |
| UNKNOWN_UNCONFIGURED | machine configured `vibration`; payload also has `temperature:30` | Accepted; `temperature` ignored, not stored | No error expected |
| CONFIGURED_NON_SCALAR | machine configured `vibration`; payload `vibration:{"x":1}` or `vibration:null` or `vibration:[1,2]` | Rejected | `invalid_field_type`, `field=vibration`; no persist |
| NO_OPTIONAL_CONFIG | machine has empty config; payload only base fields | Accepted; optionalFields empty | No error expected |
| BASE_STILL_REQUIRED | machine configured `vibration`; payload has `vibration` but omits `running` | Rejected | `missing_base_field`, `field=running` |
| CONFIG_OVER_LIMIT | `optionalTelemetryFields` has 11 names | Machine API rejects | 400 `MACHINE_VALIDATION` |
| CONFIG_RESERVED_NAME | config includes `counting` or `traceId` | Machine API rejects | 400 `MACHINE_VALIDATION` |
| CONFIG_BAD_NAME | config includes `bad-name` or `a.b` | Machine API rejects | 400 `MACHINE_VALIDATION` |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/resources/db/migration/V18__add_machines_optional_telemetry_fields.sql` -- NEW: `ALTER TABLE machines ADD COLUMN optional_telemetry_fields jsonb;`
- `syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineEntity.java` -- MODIFY: `@JdbcTypeCode(SqlTypes.JSON) @Column(name="optional_telemetry_fields", columnDefinition="jsonb") List<String> optionalTelemetryFields`, getter, thread through constructor + `update(...)`
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineService.java` -- MODIFY: `MachineCommand` + `MachineView` gain `List<String> optionalTelemetryFields`; normalize+validate config in `validateCommand`; pass into entity ctor/update; `toView` maps null→`List.of()`
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineDtos.java` -- MODIFY: `MachineRequest` gains `@Size(max=10) List<String> optionalTelemetryFields`; `MachineView` gains the field
- `syncro/apps/backend/src/main/java/com/syncro/machine/api/MachineController.java` -- MODIFY: thread field through `command(...)` / `toDto(...)`
- `syncro/apps/backend/src/main/java/com/syncro/machine/application/MachineAuditValues.java` -- MODIFY: include `optionalTelemetryFields` (normalized)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPayload.java` -- MODIFY: record gains `Map<String, JsonNode> optionalFields`; 3-arg convenience ctor (empty map); 2-arg `parse` delegates to 3-arg with `Set.of()`; new `parse(json, mapper, Set<String> configured)` collects configured scalars, rejects configured non-scalars
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` -- MODIFY: pass machine's configured optional field set (null→`Set.of()`) into `TelemetryPayload.parse`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` -- MODIFY: `toPoint` adds type-inferred fields from `payload.optionalFields()` (signature unchanged)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- MODIFY: Redis latest-hash map gains `"optional." + name` entries
- `new MachineEntity(...)` call sites (10 files): `MachineService.java`, `MachineServiceIntegrationTest.java`, `SetupCompletenessServiceIntegrationTest.java`, `MachineSparepartInstallationServiceIntegrationTest.java`, `SparepartServiceIntegrationTest.java`, `AcceptingTelemetryValidationService.java`, `TelemetryPersistenceIntegrationTest.java`, `TelemetryPersistenceServiceTest.java`, `TelemetryValidationIntegrationTest.java`, `TelemetryValidationServiceTest.java` -- MODIFY: pass optionalTelemetryFields (`List.of()` unless the test configures fields)
- Tests: `MachineServiceIntegrationTest`, `MachineControllerTest`, `TelemetryPayloadTest`, `TelemetryValidationServiceTest`, `TelemetryValidationIntegrationTest`, `InfluxTelemetryWriterTest`, `TelemetryPersistenceServiceTest`, `TelemetryPersistenceIntegrationTest` -- MODIFY/NEW cases below

## Tasks & Acceptance

**Execution:**
- [x] `V18__add_machines_optional_telemetry_fields.sql` -- NEW: additive nullable `jsonb` column; no backfill -- forward-only config storage
- [x] `MachineEntity.java` -- add jsonb `optionalTelemetryFields`, getter, ctor + `update(...)` param -- config persistence home
- [x] `MachineService.java` -- `MachineCommand`/`MachineView` field; `validateCommand` normalizes (trim/dedupe/null→empty) and rejects reserved names, `^[A-Za-z0-9_]+$` failures, >64 chars, >10 entries; pass into ctor/update; `toView` null→`List.of()` -- ownership + config invariants
- [x] `MachineDtos.java` -- `MachineRequest` `@Size(max=10) List<String> optionalTelemetryFields`; `MachineView` field -- API surface
- [x] `MachineController.java` -- thread field through command/toDto -- API wiring
- [x] `MachineAuditValues.java` -- include normalized optional field list -- audit captures config
- [x] `TelemetryPayload.java` -- `optionalFields` record component + 3-arg convenience ctor; `parse(json, mapper)` delegates to `parse(json, mapper, Set.of())`; new parse collects configured scalar fields (finite number/boolean/textual) into an immutable map, rejects configured non-scalar with `invalid_field_type`/`<name>`; base-field validation unchanged -- optional-field acceptance contract
- [x] `TelemetryValidationService.java` -- pass `machine.getOptionalTelemetryFields()` (null-safe) into parse -- per-machine rules applied at validation
- [x] `InfluxTelemetryWriter.java` -- iterate `payload.optionalFields()`: integral→`longValue`, floating→`doubleValue`, boolean, textual→string fields -- type-correct history persistence
- [x] `TelemetryPersistenceService.java` -- latest hash gains `optional.<name>` = `node.asText()` for each optional field -- latest-state persistence
- [x] Update all `new MachineEntity(...)` call sites (10 files) -- compile + consistency
- [x] `MachineServiceIntegrationTest.java` -- create/update round-trips config (null→empty), rejects >10, reserved name, bad name; dedupes duplicates -- config AC evidence (real Postgres)
- [x] `MachineControllerTest.java` -- request maps field; 400 on bad config -- API validation lock
- [x] `TelemetryPayloadTest.java` -- configured int/double/bool/string accepted; configured object/array/null rejected `invalid_field_type`; unknown unconfigured ignored; base still required when configured -- I/O matrix coverage
- [x] `TelemetryValidationServiceTest.java` -- machine with configured fields: scalars accepted + carried on payload; non-scalar rejected -- service wiring
- [x] `TelemetryValidationIntegrationTest.java` -- `3.6-VAL-*`: configured scalar accepted, unknown ignored, configured non-scalar rejected, base missing rejected (real Postgres) -- end-to-end validation
- [x] `InfluxTelemetryWriterTest.java` -- line protocol typing: `vibration=2.4`, `rpm=1200i`, `heaterOn=true`, `quality="A"` -- writer typing lock
- [x] `TelemetryPersistenceServiceTest.java` -- latest-hash map contains `optional.*` entries; point includes optional fields; dedupe base-only -- service persistence wiring
- [x] `TelemetryPersistenceIntegrationTest.java` -- `3.6-PERS-*`: persist payload with configured optional fields → InfluxDB fields present with correct types, Redis `optional.*` present, unconfigured field absent from both (real Postgres+Redis+InfluxDB) -- AC1/AC2/AC4 evidence
- [x] Run full backend suite -- no regression from Story 3.5 baseline

**Acceptance Criteria:**
- Given a machine with `optionalTelemetryFields` configured and active, when a valid MQTT payload contains those fields as scalars, then the backend accepts the message and stores the optional values in both InfluxDB history and Redis latest state -- verified by `3.6-PERS-001/002` + `InfluxTelemetryWriterTest` + `TelemetryPersistenceServiceTest`.
- Given a machine configured with [vibration], when a valid payload also contains an unconfigured `temperature` field, then the backend stores only configured fields and ignores `temperature` -- verified by `3.6-PERS-*` + `TelemetryPayloadTest`.
- Given a machine with configured optional fields and accepted telemetry, then the machine API exposes the configured list and the Redis latest hash carries `optional.<name>` values, so Stories 3.7/3.8 can render them without frontend work in this story -- verified by `MachineServiceIntegrationTest` + `3.6-PERS-002`.
- Given a machine with optional fields configured, when a payload contains optional fields but omits `running`/`runtimeHours`/`counting`, then the backend rejects with `missing_base_field` -- verified by `TelemetryPayloadTest` + `3.6-VAL-*`.

## Spec Change Log

## Design Notes

- **Type inference over config:** the configured list declares *which* fields a machine sends, not *how* to type them. Influx field types follow the JSON node: integral → long (`i`), floating → double, boolean, string. This keeps config minimal and forward-compatible; per-field type config is the `Block If` boundary.
- **Redis key namespacing:** optional values are stored as `optional.<name>` (flat `Map<String,String>`) to avoid collisions with base hash keys and to keep the PERS-002-style flat hash convention.
- **Reserved-name rejection:** names like `plantCode`/`machineCode`/`traceId`/`countingDelta` would collide with Influx tags/fields or the latest hash and crash the ingest write; rejecting them at config time keeps the ingest path crash-free.
- **Shared measurement growth:** optional fields become new columns on the shared `telemetry` measurement. Phase 1 is bounded (≤10 per machine, hundreds of machines); the 500-column InfluxDB limit and a column-hygiene policy are Epic 6 / architecture scope, not this story.
- **Golden example (canonical JBF19 machine):** machine `optionalTelemetryFields=["vibration","rpm","heaterOn","qualityGrade"]`; payload `{"running":true,"runtimeHours":12.5,"counting":100,"vibration":2.4,"rpm":1200,"heaterOn":true,"qualityGrade":"A","temperature":30}` → accepted; Influx line protocol gains `vibration=2.4,rpm=1200i,heaterOn=true,qualityGrade="A"`; Redis hash gains `optional.vibration=2.4`, `optional.rpm=1200`, `optional.heaterOn=true`, `optional.qualityGrade=A`; `temperature` is absent from both.

## Review Triage Log

### 2026-08-09 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 4: (high 0, medium 1, low 3)
- defer: 4: (high 0, medium 1, low 3)
- reject: 5
- addressed_findings:
  - `[low]` `patch` Integral optional values beyond `Long` range wrapped via `longValue()` in InfluxDB while Redis kept the full value (data divergence); `TelemetryPayload.isAcceptedScalar` now requires `canConvertToLong()` for integral nodes → `invalid_field_type` (matches the base `counting` guard).
  - `[low]` `patch` `TelemetryValidationService` used `Set.copyOf(configured)`, which throws on duplicate/null entries in the stored jsonb (hot-path crash on DB-edited config); replaced with `new HashSet<>(configured)` and added a null-set guard in `TelemetryPayload.parse`.
  - `[medium]` `patch` Optional string values were unbounded (multi-MB strings bloat Redis and make InfluxDB drop the entire point); added a 4096-char cap, oversized → `invalid_field_type`.
  - `[low]` `patch` `TelemetryPayload.parse` now skips configured names that collide with base field names or start with `_` (InfluxDB system keys), defending the ingest path against configs that bypass API validation.
- rejected_findings (dropped, by-design or noise): `null` value → `invalid_field_type` is the documented contract (intent-contract line 27, matrix row CONFIGURED_NON_SCALAR); dedupe key is base-fields-only by explicit spec constraint; the service-side >10 check is legitimate defense-in-depth for non-HTTP callers (invariant holds via `@Size`); case-variant names are distinct keys, not collisions; Flyway migration untracked-in-diff was resolved at commit time.

### 2026-08-09 — Follow-up review pass 2
- intent_gap: 0
- bad_spec: 0
- patch: 2: (high 0, medium 1, low 1)
- defer: 3: (high 0, medium 3, low 0) — 1 new ledger entry (DW-32); DW-28 and DW-31 re-confirmed but not modified (orchestrator owns their status/resolution)
- reject: 8
- addressed_findings:
  - `[medium]` `patch` `MachineService.validateCommand` accepted names starting with `_` (InfluxDB system keys), but ingest silently dropped them — a config/ingest disagreement with silent data loss; config now rejects `_`-prefixed names → `MachineValidationException`, aligning with the reserved/system-name rule in the intent-contract.
  - `[low]` `patch` The intent-contract normalizes config with "drop blanks", but `MachineService.validateCommand` rejected blank entries and failed the whole request; it now drops blank (and null) entries instead, matching the documented contract.
- rejected_findings (dropped, by-design or noise): base-fields-only dedupe key is an explicit `Never` constraint; the `MACHINE_VALIDATION` matrix shorthand vs the shared `VALIDATION_ERROR` handler (field attribution already tracked as DW-30); `@Size(max=10)` counts raw list entries while normalization is dedupe-then-count (Code Map mandates `@Size`; duplicate-heavy over-limit input is pathological); reserved-name constants duplicated across `machine`/`telemetry` contexts (extraction crosses bounded-context boundaries and both sets are currently identical — drift is speculative); Redis `asText()` string coercion loses value type (spec-mandated, PERS-002); audit-values mutable-list reference (all create/update flows pass `List.copyOf`, immutable in practice); UTF-16 length proxy for the 4096-char cap (valid defensive size bound, tighter than code points for astral chars); no parse-time cap for DB-edited >10 configs (invariant enforced at the API boundary; column hygiene is Epic 6 scope).

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryPayloadTest,TelemetryValidationServiceTest,InfluxTelemetryWriterTest,TelemetryPersistenceServiceTest,MachineControllerTest"` -- expected: all green (hermetic, no containers)
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryValidationIntegrationTest,TelemetryPersistenceIntegrationTest,MachineServiceIntegrationTest"` -- expected: `3.6-VAL-*`, `3.6-PERS-*`, config round-trip green (Testcontainers)
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green, no regression from Story 3.5 baseline

**Manual checks (operator, requires broker + infra):**
- With `docker compose up -d` and backend on `local` profile, create machine `BF-08410` with `optionalTelemetryFields=["vibration"]`, publish `{"running":true,"runtimeHours":12.5,"counting":100,"vibration":2.4,"temperature":30}` to `factory/GM1/BF-08410/telemetry`; verify `optional.vibration=2.4` in the latest Redis hash (`syncro:machine:{machineId}:latest`) and field `vibration` in the InfluxDB point, with `temperature` absent.

## Auto Run Result

**Change summary:** Follow-up review pass 2 re-verified the Story 3.6 worktree and aligned config normalization with the documented intent-contract. Two behavior patches to `MachineService.validateCommand`: (1) optional-field names starting with `_` (InfluxDB system keys) are now rejected at config time instead of being accepted and silently dropped at ingest; (2) blank/null optional-field entries are dropped during normalization per the "trim, drop blanks" rule instead of rejecting the whole config. Both covered by new `MachineServiceIntegrationTest` cases.

**Files changed (this pass):**
- `MachineService.java` — reject `_`-prefixed names; drop blank/null entries in config normalization.
- `MachineServiceIntegrationTest.java` — `3.6-SVC-008` blank/null entries dropped, `3.6-SVC-009` underscore-prefixed name rejected.

**Review findings breakdown:** 13 deduplicated findings across the blind-hunter and edge-case review layers. Patches applied: 2 — `_`-prefixed name rejection, blank-entry dropping. Deferred: 3 — InfluxDB field-type conflict across samples (already tracked, DW-28, unchanged), stale `optional.*` keys after config removal including intermittent field absence (already tracked, DW-31, unchanged), and NEW DW-32: a machine PUT without `optionalTelemetryFields` wipes the configured list (destructive default; null-means-unchanged vs frontend passthrough is a design decision for Stories 3.7/3.8). Rejected: 8 — base-only dedupe key is an explicit `Never` constraint; `MACHINE_VALIDATION` matrix shorthand vs the shared `VALIDATION_ERROR` handler (field attribution already DW-30); `@Size(max=10)` raw-count edge (Code Map mandates `@Size`); duplicated reserved-name constants across contexts (cross-boundary extraction is a design decision; both sets identical); Redis `asText()` type-loss (spec-mandated, PERS-002); audit-values mutable-list reference (immutable `List.copyOf` in all flows); UTF-16 length proxy for the 4096-char cap (valid defensive size bound); parse-time cap for DB-edited >10 configs (invariant enforced at the API; column hygiene is Epic 6 scope).

**Follow-up review recommendation:** false.

**Verification:**
- Hermetic suite (`TelemetryPayloadTest`, `TelemetryValidationServiceTest`, `InfluxTelemetryWriterTest`, `TelemetryPersistenceServiceTest`, `MachineControllerTest`): 65 tests, 0 failures/errors, BUILD SUCCESS (JDK 25).
- Integration suite (`TelemetryValidationIntegrationTest`, `TelemetryPersistenceIntegrationTest`, `MachineServiceIntegrationTest`): 42 tests, 0 failures/errors, BUILD SUCCESS (Testcontainers Postgres/Redis/InfluxDB).
- Full suite: 416 run, 0 failures, 1 pre-existing error (`SyncroBackendApplicationTests.contextLoads`, missing `AuditLogRepository` — Story 3-5 documented baseline), 15 skipped.

**Residual risks:**
- InfluxDB field-type conflict if a machine alternates a configured field's JSON type across samples (int vs float) — deferred (architecture/Epic 6 scope, DW-28).
- A machine PUT without `optionalTelemetryFields` silently resets the configured list to empty — deferred, DW-32.
- `optional.*` keys linger in the Redis latest hash up to `latestTtl` after config removal, and a configured field intermittently absent from payloads keeps its last value — deferred, DW-31.
- The known pre-existing full-suite `contextLoads` error remains (unrelated to 3.6).

