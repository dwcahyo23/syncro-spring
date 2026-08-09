# Deferred Work

### DW-1: MQTT credentials configured but local EMQX MQTT auth not enforced

origin: migrated from legacy ledger ("Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)"), 2026-08-07
location: syncro backend MQTT/EMQX broker configuration
reason: Local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.
status: open

### DW-2: Add durable UI state evidence for AC11

origin: migrated from legacy ledger ("Deferred from: code review of 2-2-manage-plant-scoped-machine-groups (2026-05-27)"), 2026-08-07
location: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx:176
reason: Current code/browser evidence is acceptable for this review, but component tests or captured trace for empty/loading/error/read-only/forbidden/validation states would make regression proof stronger.
status: open

### DW-3: Hardcoded local ports in web test config

origin: migrated from legacy ledger ("Deferred from: code review of 2-5-manage-spareparts (2026-05-28)"), 2026-08-07
location: syncro/apps/web/playwright.config.ts:2
reason: Playwright baseline and local test env defaults predate this story; not caused by Story 2.5 functional change.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-web-e2e-config-hardening
resolution-undo: cc5d75d761fd6374ffb8e047e9ec1dfeb40b9c2acb6e41087d6dcce158aaad73 2026-08-08 7374617475733a206f70656e

### DW-4: Missing audit trails for machine responsibility assignments/removals

origin: migrated from legacy ledger ("Deferred from: code review of 2-7-assign-machine-responsibility-levels (2026-06-03)"), 2026-08-07
location: n/a
reason: No audit logging for assignments/removals — deferred, pre-existing (Epic 2 Task 2-9).
status: done 2026-08-08
resolution: already resolved: MachineResponsibilityService.java:85,103,120 now records CREATE/UPDATE/DELETE audit entries for RESPONSIBILITY via AuditLogWriter (AuditEntityType.RESPONSIBILITY), landed by story 2-9 (commit c386dba, merged ad00966).

### DW-5: Redundant DB indexes wasting write performance

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Redundant DB indexes wasting write perf — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-db-index-hygiene
resolution-undo: 116e09ed8ca373373f2953aff0446ca01aca198accd121c508cb4c4e3d05415a 2026-08-08 7374617475733a206f70656e

### DW-6: Expensive unindexed joins with order by

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Expensive unindexed joins with order by — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-db-index-hygiene
resolution-undo: 116e09ed8ca373373f2953aff0446ca01aca198accd121c508cb4c4e3d05415a 2026-08-08 7374617475733a206f70656e

### DW-7: Brittle Next.js server command in Playwright

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: syncro/apps/web (Playwright server command)
reason: Brittle Next.js server command in Playwright — deferred, pre-existing.
status: done 2026-08-08
resolution: resolved by sweep bundle dw-web-e2e-config-hardening
resolution-undo: cc5d75d761fd6374ffb8e047e9ec1dfeb40b9c2acb6e41087d6dcce158aaad73 2026-08-08 7374617475733a206f70656e

### DW-8: Worthless skipped test suites

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: n/a
reason: Worthless skipped test suites — deferred, pre-existing.
status: open

### DW-9: Missing optimistic locking on Installation entity

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: Installation entity (backend)
reason: Missing optimistic locking on Installation entity — deferred, pre-existing.
status: open

### DW-10: tests/api suites not collected by the default Playwright testDir

origin: flagged in spec-web-e2e-config-hardening review log (WH-01), 2026-08-08
location: syncro/apps/web/playwright.config.ts (`testDir: ./tests/e2e`)
reason: The API suites (audit-log contract, spareparts, installations — the primary `apiBaseUrl()` consumers of the config-hardening change) live under `tests/api/`, outside the default testDir, so `npm run test:e2e` and `playwright test --list` silently omit them. CI can be green with zero API-suite execution and no signal.
status: done 2026-08-08
resolution: resolved by bundle dw-web-e2e-config-hardening automate run — added `playwright.api.config.ts` (testDir `./tests/api`), `npm run test:api` (`playwright test --config=playwright.api.config.ts`), and `npm run test:api:check` (tests/support/check-api-collection.mjs) which exits non-zero if no tests/api spec is collected. Run `test:api:check` after `test:api` in CI.

### DW-11: Portless BASE_URL hangs web server readiness

origin: flagged in spec-web-e2e-config-hardening review log (WH-03), 2026-08-08
location: syncro/apps/web/playwright.config.ts (webServer derivation)
reason: With a portless `BASE_URL` (e.g. a deployed origin like `https://app.example.com`), the derived default web server command degrades to plain `npm run dev` (Next default port 3000) while `webServer.url` is the portless origin — the server never reaches readiness and the run hangs until the 120s timeout. Pre-existing mismatch; the config-hardening derivation keeps it.
status: open
mitigation: documented in syncro/apps/web/tests/README.md — pair a portless `BASE_URL` with `PLAYWRIGHT_SKIP_WEB_SERVER=1` so Playwright targets the already-running origin without booting a local server.

### DW-12: Follow-up review still recommended for dw-web-e2e-config-hardening after the damping cap was spent
origin: review-budget-followup
location: n/a
source_spec: `spec-web-e2e-config-hardening.md`
severity: low
reason: The follow-up-review damping cap (limits.max_followup_reviews = 1) was spent with the story finalized (status: done, verify green) while the review pass still recommended an independent follow-up. The work was committed by bmad-loop run 20260808-012337-779e; this entry preserves the lingering recommendation for a deliberate later review.
status: open

### DW-13: Validate MQTT connection properties in typed config
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
  summary: Add null/blank validation to `MqttProperties` (host, port, clientId, topicFilter) so a missing env value fails fast instead of producing `tcp://null:1883` or a runtime adapter NPE.
  evidence: Real, surfaced by review of Story 3.1 — `MqttSubscriptionConfig.mqttConnectOptions` builds `tcp://" + host + ":" + port` with no guard, and `MqttProperties` has no validation constraints. Shared config hardening touching a class used by other stories; defer beyond Story 3.1 scope.

### DW-14: Mid-session MQTT connectivity loss invisible to health indicator
- source_spec: `_bmad-output/implementation-artifacts/spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md`
  summary: `MqttConnectionStatus` stays `SUBSCRIBED`/UP throughout a broker outage that begins after the initial subscribe, because the Paho reconnect path handles the drop in its background thread and does not publish an `MqttConnectionFailedEvent` the way `doStart()`/`subscribe()` catch paths do.
  evidence: Real, surfaced by review of Story 3.1 — with `setAutomaticReconnect(true)`, a mid-session drop does not emit the adapter's connection-failed event, so the sole observability signal (health) reports UP for the entire offline window. The Spring Integration adapter's event set exposes no connection-lost event observable by this listener; needs a later adapter-level or event-source investigation, out of Story 3.1 scope.

### DW-15: Negative-value range validation for telemetry base fields
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: `TelemetryPayload.parse` accepts negative `runtimeHours` and negative `counting`; the epic mandates rejecting values outside physically plausible ranges.
  evidence: Real, surfaced by review of Story 3.2 — the parse path has no lower-bound guard (`runtimeNode.doubleValue() < 0`, `countingNode.longValue() < 0`). Negative-count rejection is explicitly owned by Story 3.5's AC ("invalid negative/non-numeric count payloads are rejected by payload validation"); negative runtimeHours range validation has no owning story yet. Deferred to avoid range logic landing ahead of Story 3.5's counter-wrap semantics.

### DW-16: Per-message DB round-trips on MQTT ingest thread without caching
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: `TelemetryValidationService.validate` performs two synchronous JPA lookups (`findByCodeIgnoreCase`, `findByPlantIdAndCodeIgnoreCase`) per inbound message on the QoS-1 ingest thread, with no caching or offload.
  evidence: Real, surfaced by review of Story 3.2 — validation is the first DB-touching step on the ingest path (Story 3.1 was log-only). Acceptable for Phase-1 telemetry volume; cache/backpressure offload belongs with the bounded-queue ingest-worker story (Epic 3 backpressure NFR) rather than Story 3.2.

### DW-17: Detached `MachineEntity` with lazy associations returned in `Accepted` result
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: `TelemetryValidationService.validate` returns the `MachineEntity` from `findByPlantIdAndCodeIgnoreCase` outside any transaction; its `plant`/`machineGroup` associations are LAZY, so Story 3.4's store write that touches `accepted.machine().getPlant()` will throw `LazyInitializationException`.
  evidence: Real, surfaced by review of Story 3.2 — `validate()` is not `@Transactional` and the repository's implicit transaction closes on return, detaching the entity; `MachineEntity.plant`/`machineGroup` are `FetchType.LAZY`. Harmless today (no store write yet, Story 3.4) but guaranteed to detonate at the first downstream lazy access. Needs a contract decision (lightweight machine view vs entity fetched with joins kept within a transaction) before Story 3.4.

### DW-18: Raw payload logged at INFO on every accepted telemetry message
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: `MqttTelemetryIngestHandler` logs the full unredacted payload in `mqtt_telemetry_received` at INFO for every accepted message, inflating log volume at telemetry rate.
  evidence: Pre-existing from Story 3.1 (`payload={}` in `handleMessage` at baseline d14ce61), surfaced incidentally by review of Story 3.2 which split accepted vs rejected logging — the natural point to have trimmed accepted-path logging to traceId/topic.

### DW-19: `MqttSubscriptionConfigTest` forced to mock DB-backed `TelemetryValidationService` to keep context alive
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: The handler's new `TelemetryValidationService` dependency drags DB-touching repositories into `MqttSubscriptionConfigTest`, which now must supply a mocked-repo bean purely to construct the handler.
  evidence: Real, surfaced by review of Story 3.2 — `MqttSubscriptionConfigTest` added a `TestConfiguration` bean `new TelemetryValidationService(mock(PlantRepository.class), mock(MachineRepository.class))`; with Mockito defaults that bean's `validate()` rejects every message, so the wiring test's handler is non-functional in principle. A `@FunctionalInterface` validator abstraction or splitting the handler's logging from validation would decouple wiring tests.

### DW-20: Whitespace/edge tokens in topic segments surface as `unknown_plant`/`unknown_machine` instead of `malformed_topic`
- source_spec: `_bmad-output/implementation-artifacts/spec-3-2-validate-mqtt-topic-and-base-payload.md`
  summary: `TelemetryTopic.parse` only guards `isEmpty()` on segments, so `factory/ GM1/BF-08410/telemetry` and `factory/GM1/ /telemetry` parse successfully and then fail master-data lookup as `unknown_plant`/`unknown_machine`, misdirecting operators away from a malformed-topic root cause.
  evidence: Real, surfaced by review of Story 3.2 — no trimming or whitespace guard in `parse`; such topics can never match master data, so the reason points at master-data rather than the topic shape.

### DW-21: Inactive-machine gate rejects by exclusion rather than inclusion
- source_spec: `_bmad-output/implementation-artifacts/spec-3-3-reject-inactive-machine-telemetry.md`
  summary: `TelemetryValidationService` rejects only `MachineStatus.INACTIVE` and accepts any other status by default; when the enum grows (e.g. `DECOMMISSIONED`, `SUSPENDED`), telemetry for those machines would be silently accepted instead of rejected.
  evidence: Real, surfaced by review of Story 3.3 — the gate at `TelemetryValidationService.validate` is `status == INACTIVE → reject`, equivalent to `!= ACTIVE → reject` today only because the enum is exactly `ACTIVE`/`INACTIVE`; an inclusion-based `status != ACTIVE → reject` would fail closed for future statuses. Deferred: spec intentionally names INACTIVE only; revisit when a new machine status is introduced.

### DW-22: Heartbeat-thinning dedupe relies on value-equality until Story 3.9 messageId
- source_spec: `_bmad-output/implementation-artifacts/spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md`
  summary: Dedupe keys on `machineId + running + runtimeHours + counting` (value-equality, TTL `dedupeWindow`) mean a machine reporting a constant heartbeat (same values) at intervals within the window is silently dropped as a duplicate even when each message is distinct in time.
  evidence: Real, surfaced by review of Story 3.4 (BH-7) — the fallback idempotency rule intentionally trades this off until Story 3.9 delivers `messageId`/payload `timestamp`, which will become the dedupe key prefix and restore distinct-heartbeat persistence. Tracked here so the thinning semantics are revisited when 3.9 lands.

### DW-23: QoS-1 redelivery dedupe is best-effort because `cleanSession(true)` drops in-flight messages
- source_spec: `_bmad-output/implementation-artifacts/spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md`
  summary: The dedupe SETNX gate only guards against duplicates that actually arrive; with Paho `cleanSession(true)` (Story 3.1 MQTT config), messages in-flight during a broker reconnect are discarded rather than redelivered, so the dedupe key cleanup on write failure cannot fully guarantee at-least-once semantics.
  evidence: Real, surfaced by review of Story 3.4 (BH-10) — `cleanSession(true)` defeats the redelivery net that the dedupe-key-delete-on-failure cleanup was designed to support. Belongs to Story 3.1's MQTT connection config decision; revisit if at-least-once becomes a hard requirement.

### DW-24: Follow-up review still recommended for 3-4-persist-accepted-telemetry-to-influxdb-and-redis after the damping cap was spent
origin: review-budget-followup
location: n/a
source_spec: `spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md`
severity: low
reason: The follow-up-review damping cap (limits.max_followup_reviews = 1) was spent with the story finalized (status: done, verify green) while the review pass still recommended an independent follow-up. The work was committed by bmad-loop run 20260808-181116-d9d3; this entry preserves the lingering recommendation for a deliberate later review.
status: open

### DW-25: Production-count delta continuity lost across gaps longer than the latest-state TTL
- source_spec: `_bmad-output/implementation-artifacts/spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md`
  summary: Story 3.5 computes `countingDelta` from the previous `counting` in the Redis latest hash (`syncro:machine:{machineId}:latest`). That key expires after `latestTtl` (default PT5M), so a message arriving after a gap longer than the TTL finds no previous value and is treated as a first sample (`countingDelta = 0`). Summed deltas still equal total production since the first observed sample, so sparepart consumption is not distorted, but per-message delta continuity across idle gaps is not persisted.
  evidence: Real, surfaced during Story 3.5 design — the only previous-counter store is the TTL'd latest hash; a durable per-machine counter-state store (PostgreSQL table or longer-lived Redis key) was deliberately NOT added to avoid schema/scope creep. Revisit when Epic 4 sparepart consumption needs gap-spanning delta continuity or when the ingest worker (backpressure story) introduces durable per-machine state.

### DW-26: Uniform 16-bit wrap rule cannot distinguish genuine counter decrease/reset/out-of-order delivery from a wrap
- source_spec: `_bmad-output/implementation-artifacts/spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md`
  summary: `CountingDeltaCalculator` treats any `current < previous` as a 16-bit unsigned wrap (`delta = floorMod(current - previous, 65536)`). A genuine counter reset/re-provision (previous=1000, current=100 → delta 64536), an out-of-order/stale message (previous=200, current=150 → delta 65516), or a repeated decrease permanently corrupts the delta chain: a phantom near-max delta is written to history and the latest hash regresses to the stale counting, skewing every subsequent delta until the counter passes the stale value again.
  evidence: Real, surfaced by review of Story 3.5 (BH-2/BH-6/EH-3). The architecture mandates "counter decrease that is not wrap is rejected with a recorded reason" (project-context rule L-94), but distinguishing wrap from decrease/reset requires a per-machine counter-source configuration (bit-width/unsigned flag) that does not exist in Phase 1 — the spec explicitly chose the uniform wrap rule and the Block If clause defers the configurable-counter-type decision to architecture. Revisit with counter-type configuration or an ordering check (Story 3.9 payload timestamp/messageId) before the delta chain feeds Epic 4 sparepart consumption.

### DW-27: Redis latest-write failure after the InfluxDB point is persisted makes the next delta double-count the already-persisted span
- source_spec: `_bmad-output/implementation-artifacts/spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md`
  summary: `TelemetryPersistenceService.persist` computes the delta from the Redis latest hash `counting` and only updates that hash after the InfluxDB point is written. If `putLatest` fails after the point is persisted (the Story 3.4 policy keeps the dedupe gate so a redelivery cannot duplicate the history record), the latest hash still holds the older `counting`, so the next message computes its delta from the stale previous and double-counts the span already written to history.
  evidence: Real, surfaced by review of Story 3.5 (BH-5/EH-5). Redis is cache-only and can regress after any write failure, so the delta chain based on it diverges from persisted history without detection. No cheap fix at persist time (the stale value cannot be rolled forward because Redis is the failing dependency); a durable counter baseline or chain-source from InfluxDB history is the Epic 4 / ingest-worker scope.

### DW-28: InfluxDB field-type conflict when a configured field's JSON value type varies across samples
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md`
  summary: `InfluxTelemetryWriter.addOptionalField` infers Influx field types from the JSON node (integral→`i` long, floating→double, boolean, string), so a configured field that alternates representation across samples (e.g. `vibration:2` vs `vibration:2.4`, or `2` vs `2.0`) triggers an InfluxDB field-type conflict that rejects subsequent points for that series and silently drops telemetry.
  evidence: Real, surfaced by review of Story 3.6 (BH-2/EH-2) — InfluxDB 1.x rejects a write when an existing field key's type changes within a series; type inference is the spec's explicit Design Note choice (golden example `rpm=1200i`). Fixing requires a type-stability strategy (per-machine field-type map or all-double normalization) that changes the documented storage contract — architecture/Epic 6 scope per the spec's `Block If` boundary.

### DW-29: Config-time reserved-name denylist not future-proof against base-field promotion or InfluxDB system keys
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md`
  summary: `RESERVED_OPTIONAL_FIELDS` is a static set; a future promotion of a field to the base contract, or a configured name colliding with InfluxDB system keys (`_field`/`_measurement`/`_value`/`_time`), would silently collide with base tags/fields on already-deployed machines.
  evidence: Real, surfaced by review of Story 3.6 (BH-4, partial) — `TelemetryPayload.parse` now skips base-field names and underscore-prefixed keys defensively, but the config-time allowlist itself is not protected against future base-contract growth; verify InfluxDB 1.8 reserved-key semantics before extending the denylist.

### DW-30: MachineValidationException handler attributes all validation failures to the `code` field
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md`
  summary: `MachineExceptionHandler` maps every `MachineValidationException` to `Map.of("code", "Invalid value.")`, so Story 3.6's new config-rule rejections (reserved/bad/oversized optional field names) produce a `VALIDATION_ERROR` that blames the `code` field the client never touched.
  evidence: Real, surfaced by review of Story 3.6 (BH-8) — DTO-level failures (`@Size(max=10)`) correctly key the error on `optionalTelemetryFields`, but service-level rule failures route through the pre-existing shared handler's hardcoded `code` key; the 400 + VALIDATION_ERROR contract is met but the field attribution is misleading. Root cause is the pre-existing handler design; fixing requires a field-aware exception/message contract shared by all machine validation paths.

### DW-31: Stale `optional.*` keys linger in the Redis latest hash after a field is removed from machine config
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md`
  summary: Removing a field from `machines.optional_telemetry_fields` does not delete previously-written `optional.<name>` keys from the latest hash; they persist until `latestTtl` expiry, so consumers (Stories 3.7/3.8) can render values for no-longer-configured fields.
  evidence: Real, surfaced by review of Story 3.6 (BH-9) — `TelemetryPersistenceService.persist` only writes new `optional.*` entries and never prunes on config change; cleanup would couple machine CRUD to telemetry latest-state (new wiring + method), TTL-bounded and low impact.

### DW-32: Machine PUT without `optionalTelemetryFields` wipes a machine's configured optional fields
- source_spec: `_bmad-output/implementation-artifacts/spec-3-6-support-optional-machine-telemetry-fields.md`
  summary: `MachineService.update` is full-replace, so a client that omits or sends `null` for `optionalTelemetryFields` on a routine machine edit (the frontend is explicitly untouched by Story 3.6 and does not yet send the field) normalizes to `List.of()` and silently erases the machine's previously configured optional telemetry fields; Stories 3.7/3.8 may later find the config gone.
  evidence: Real, surfaced by review of Story 3.6 (BH-6) — update always overwrites `optionalTelemetryFields` from the request (null ≡ empty per the intent-contract), while create/update paths are the only sanctioned writers of the config. Fixing requires either null-means-unchanged update semantics (a deviation from the documented null ≡ empty contract) or frontend field passthrough coordinated with Stories 3.7/3.8 — a design decision, not a local code fix.
