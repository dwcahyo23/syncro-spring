# Deferred Work

### DW-1: MQTT credentials configured but local EMQX MQTT auth not enforced

origin: migrated from legacy ledger ("Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)"), 2026-08-07
location: syncro backend MQTT/EMQX broker configuration
reason: Local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.
status: open
decision: 2026-08-19 Bundle it now — Create a dev session that enables EMQX password authentication in docker-compose (EMQX_AUTH__MNESIA__PASSWORD_HASH or built-in DB), updates application.yml MQTT client credentials, and verifies the Spring MQTT client connects successfully with auth enforced.
decision: 2026-08-19 Bundle it now — Create a dev session that enables EMQX password authentication in docker-compose (EMQX_AUTH__MNESIA__PASSWORD_HASH or built-in DB), updates application.yml MQTT client credentials, and verifies the Spring MQTT client connects successfully with auth enforced.

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
decision: 2026-08-19 Bundle it now — Add a @Version Long version field to InstallationEntity, update the corresponding migration, and propagate OptimisticLockException handling to the service and exception-handler layers.
decision: 2026-08-19 Bundle it now — Add a @Version Long version field to InstallationEntity, update the corresponding migration, and propagate OptimisticLockException handling to the service and exception-handler layers.

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
status: done 2026-08-19
resolution: already resolved: syncro/apps/web/playwright.config.ts:6-8 — port derived from new URL(baseURL).port with fallback to portless 'npm run dev'; PLAYWRIGHT_SKIP_WEB_SERVER guard at line 57 prevents hanging when BASE_URL has no port
decision: 2026-08-19 New small bundle — Create a focused dev session: fix playwright.config.ts webServer baseURL to correctly derive or default the port from BASE_URL, and add or update tests/README.md to document PLAYWRIGHT_SKIP_WEB_SERVER.
decision: 2026-08-19 New small bundle — Create a focused dev session: fix playwright.config.ts webServer baseURL to correctly derive or default the port from BASE_URL, and add or update tests/README.md to document PLAYWRIGHT_SKIP_WEB_SERVER.
mitigation: documented in syncro/apps/web/tests/README.md — pair a portless `BASE_URL` with `PLAYWRIGHT_SKIP_WEB_SERVER=1` so Playwright targets the already-running origin without booting a local server.

### DW-12: Follow-up review still recommended for dw-web-e2e-config-hardening after the damping cap was spent

origin: review-budget-followup
location: n/a
source_spec: spec-web-e2e-config-hardening.md
severity: low
reason: The follow-up-review damping cap (limits.max_followup_reviews = 1) was spent with the story finalized (status: done, verify green) while the review pass still recommended an independent follow-up. The work was committed by bmad-loop run 20260808-012337-779e; this entry preserves the lingering recommendation for a deliberate later review.
status: done 2026-08-19
resolution: already resolved: spec-web-e2e-config-hardening review log present; follow-up review recommendation was itself the deliverable — no further code gap exists

### DW-13: Validate MQTT connection properties in typed config

origin: code review of spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md
location: MqttProperties.java, MqttSubscriptionConfig.java
severity: high
reason: Add null/blank validation to `MqttProperties` (host, port, clientId, topicFilter) so a missing env value fails fast instead of producing `tcp://null:1883` or a runtime adapter NPE. Shared config hardening touching a class used by other stories; defer beyond Story 3.1 scope.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-mqtt-ingest-hardening
resolution-undo: 2aab570a2e12b0e9048c9f1d1c99c10d652ac3874b5f4e13571efd5754194040 2026-08-19 7374617475733a206f70656e

### DW-14: Mid-session MQTT connectivity loss invisible to health indicator

origin: code review of spec-3-1-configure-mqtt-subscription-and-telemetry-contract.md
location: MqttConnectionStatus.java, MqttSubscriptionConfig.java
severity: high
reason: `MqttConnectionStatus` stays `SUBSCRIBED`/UP throughout a broker outage that begins after the initial subscribe, because the Paho reconnect path handles the drop in its background thread and does not publish an `MqttConnectionFailedEvent`. With `setAutomaticReconnect(true)`, a mid-session drop does not emit the adapter's connection-failed event; the Spring Integration adapter's event set exposes no connection-lost event observable by this listener. Needs a later adapter-level or event-source investigation, out of Story 3.1 scope.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-mqtt-ingest-hardening
resolution-undo: 2aab570a2e12b0e9048c9f1d1c99c10d652ac3874b5f4e13571efd5754194040 2026-08-19 7374617475733a206f70656e

### DW-15: Negative-value range validation for telemetry base fields

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: TelemetryPayload.java
severity: high
reason: `TelemetryPayload.parse` accepts negative `runtimeHours` and negative `counting`; the epic mandates rejecting values outside physically plausible ranges. The parse path has no lower-bound guard. Negative-count rejection is explicitly owned by Story 3.5's AC; negative runtimeHours range validation has no owning story yet. Deferred to avoid range logic landing ahead of Story 3.5's counter-wrap semantics.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-validation-robustness
resolution-undo: d873197c6c80419806095fdec9b878935e7a628478cd0305fa551d254cb9d478 2026-08-19 7374617475733a206f70656e

### DW-16: Per-message DB round-trips on MQTT ingest thread without caching

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: TelemetryValidationService.java
severity: medium
reason: `TelemetryValidationService.validate` performs two synchronous JPA lookups (`findByCodeIgnoreCase`, `findByPlantIdAndCodeIgnoreCase`) per inbound message on the QoS-1 ingest thread, with no caching or offload. Acceptable for Phase-1 telemetry volume; cache/backpressure offload belongs with the bounded-queue ingest-worker story (Epic 3 backpressure NFR) rather than Story 3.2.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-validation-robustness
resolution-undo: d873197c6c80419806095fdec9b878935e7a628478cd0305fa551d254cb9d478 2026-08-19 7374617475733a206f70656e

### DW-17: Detached `MachineEntity` with lazy associations returned in `Accepted` result

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: TelemetryValidationService.java
severity: high
reason: `TelemetryValidationService.validate` returns the `MachineEntity` outside any transaction; its `plant`/`machineGroup` associations are LAZY, so Story 3.4's store write that touches `accepted.machine().getPlant()` will throw `LazyInitializationException`. `validate()` is not `@Transactional` and the repository's implicit transaction closes on return. Needs a contract decision (lightweight machine view vs entity fetched with joins kept within a transaction) before Story 3.4.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-validation-robustness
resolution-undo: d873197c6c80419806095fdec9b878935e7a628478cd0305fa551d254cb9d478 2026-08-19 7374617475733a206f70656e

### DW-18: Raw payload logged at INFO on every accepted telemetry message

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: MqttTelemetryIngestHandler.java
severity: low
reason: `MqttTelemetryIngestHandler` logs the full unredacted payload in `mqtt_telemetry_received` at INFO for every accepted message, inflating log volume at telemetry rate. Pre-existing from Story 3.1 (`payload={}` in `handleMessage`), surfaced incidentally by review of Story 3.2 — the natural point to trim accepted-path logging to traceId/topic was missed.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-mqtt-ingest-hardening
resolution-undo: 2aab570a2e12b0e9048c9f1d1c99c10d652ac3874b5f4e13571efd5754194040 2026-08-19 7374617475733a206f70656e

### DW-19: `MqttSubscriptionConfigTest` forced to mock DB-backed `TelemetryValidationService` to keep context alive

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: MqttSubscriptionConfigTest.java
severity: low
reason: The handler's new `TelemetryValidationService` dependency drags DB-touching repositories into `MqttSubscriptionConfigTest`, which must supply a mocked-repo bean purely to construct the handler. With Mockito defaults that bean's `validate()` rejects every message, so the wiring test's handler is non-functional in principle. A `@FunctionalInterface` validator abstraction or splitting handler logging from validation would decouple wiring tests.
status: open

### DW-20: Whitespace/edge tokens in topic segments surface as `unknown_plant`/`unknown_machine` instead of `malformed_topic`

origin: code review of spec-3-2-validate-mqtt-topic-and-base-payload.md
location: TelemetryTopic.java
severity: medium
reason: `TelemetryTopic.parse` only guards `isEmpty()` on segments, so `factory/ GM1/BF-08410/telemetry` and `factory/GM1/ /telemetry` parse successfully and then fail master-data lookup as `unknown_plant`/`unknown_machine`, misdirecting operators away from a malformed-topic root cause. No trimming or whitespace guard in `parse`; such topics can never match master data.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-mqtt-ingest-hardening
resolution-undo: 2aab570a2e12b0e9048c9f1d1c99c10d652ac3874b5f4e13571efd5754194040 2026-08-19 7374617475733a206f70656e

### DW-21: Inactive-machine gate rejects by exclusion rather than inclusion

origin: code review of spec-3-3-reject-inactive-machine-telemetry.md
location: TelemetryValidationService.java
severity: medium
reason: `TelemetryValidationService` rejects only `MachineStatus.INACTIVE` and accepts any other status by default; when the enum grows (e.g. `DECOMMISSIONED`, `SUSPENDED`), telemetry for those machines would be silently accepted. An inclusion-based `status != ACTIVE → reject` would fail closed for future statuses. Deferred: spec intentionally names INACTIVE only; revisit when a new machine status is introduced.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-validation-robustness
resolution-undo: d873197c6c80419806095fdec9b878935e7a628478cd0305fa551d254cb9d478 2026-08-19 7374617475733a206f70656e

### DW-22: Heartbeat-thinning dedupe relies on value-equality until Story 3.9 messageId

origin: code review of spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md
location: TelemetryPersistenceService.java
severity: medium
reason: Dedupe keys on `machineId + running + runtimeHours + counting` (value-equality, TTL `dedupeWindow`) mean a machine reporting a constant heartbeat (same values) at intervals within the window is silently dropped as a duplicate even when each message is distinct in time. The fallback idempotency rule intentionally trades this off until Story 3.9 delivers `messageId`/payload `timestamp`. Tracked here so the thinning semantics are revisited when 3.9 lands.
status: done 2026-08-19
resolution: already resolved: TelemetryPayload.java:12-13 — messageId is now a first-class validated field; TelemetryPersistenceService.java:53 — dedupe key uses messageId, not value-equality

### DW-23: QoS-1 redelivery dedupe is best-effort because `cleanSession(true)` drops in-flight messages

origin: code review of spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md
location: MqttSubscriptionConfig.java
severity: medium
reason: The dedupe SETNX gate only guards against duplicates that actually arrive; with Paho `cleanSession(true)` (Story 3.1 MQTT config), messages in-flight during a broker reconnect are discarded rather than redelivered, so the dedupe key cleanup on write failure cannot fully guarantee at-least-once semantics. `cleanSession(true)` defeats the redelivery net. Belongs to Story 3.1's MQTT connection config decision; revisit if at-least-once becomes a hard requirement.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-mqtt-ingest-hardening
resolution-undo: 2aab570a2e12b0e9048c9f1d1c99c10d652ac3874b5f4e13571efd5754194040 2026-08-19 7374617475733a206f70656e

### DW-24: Follow-up review still recommended for 3-4-persist-accepted-telemetry-to-influxdb-and-redis after the damping cap was spent
origin: review-budget-followup
location: n/a
source_spec: `spec-3-4-persist-accepted-telemetry-to-influxdb-and-redis.md`
severity: low
reason: The follow-up-review damping cap (limits.max_followup_reviews = 1) was spent with the story finalized (status: done, verify green) while the review pass still recommended an independent follow-up. The work was committed by bmad-loop run 20260808-181116-d9d3; this entry preserves the lingering recommendation for a deliberate later review.
status: done 2026-08-19
decision: 2026-08-19 Close — bundles cover it — The telemetry-persistence-atomicity, influx-optional-field-type-safety, and counting-delta-reliability bundles address the substantive gaps; a separate review session adds marginal value.
resolution: closed by human decision: The telemetry-persistence-atomicity, influx-optional-field-type-safety, and counting-delta-reliability bundles address the substantive gaps; a separate review session adds marginal value.
decision: 2026-08-19 Close — bundles cover it — The telemetry-persistence-atomicity, influx-optional-field-type-safety, and counting-delta-reliability bundles address the substantive gaps; a separate review session adds marginal value.

### DW-25: Production-count delta continuity lost across gaps longer than the latest-state TTL

origin: code review of spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md
location: TelemetryPersistenceService.java, CountingDeltaCalculator.java
severity: medium
reason: Story 3.5 computes `countingDelta` from the previous `counting` in the Redis latest hash (`syncro:machine:{machineId}:latest`). That key expires after `latestTtl` (default PT5M), so a message arriving after a gap longer than the TTL finds no previous value and is treated as a first sample (`countingDelta = 0`). A durable per-machine counter-state store was deliberately NOT added to avoid scope creep. Revisit when Epic 4 sparepart consumption needs gap-spanning delta continuity.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-persistence-atomicity
resolution-undo: e392b4fb8e5ec2fa1bc7188ab8b27689c91d143df4b1ab322ec8c1961d1618ab 2026-08-19 7374617475733a206f70656e

### DW-26: Uniform 16-bit wrap rule cannot distinguish genuine counter decrease/reset/out-of-order delivery from a wrap

origin: code review of spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md
location: CountingDeltaCalculator.java
severity: high
reason: `CountingDeltaCalculator` treats any `current < previous` as a 16-bit unsigned wrap (`delta = floorMod(current - previous, 65536)`). A genuine counter reset/re-provision or out-of-order/stale message permanently corrupts the delta chain. Distinguishing wrap from decrease/reset requires a per-machine counter-source configuration that does not exist in Phase 1. Revisit with counter-type configuration or an ordering check (Story 3.9 payload timestamp/messageId) before the delta chain feeds Epic 4.
status: open

### DW-27: Redis latest-write failure after the InfluxDB point is persisted makes the next delta double-count the already-persisted span

origin: code review of spec-3-5-calculate-production-count-delta-with-16-bit-wrap-support.md
location: TelemetryPersistenceService.java
severity: high
reason: `TelemetryPersistenceService.persist` computes the delta from the Redis latest hash `counting` and only updates that hash after the InfluxDB point is written. If `putLatest` fails after the point is persisted, the latest hash still holds the older `counting`, so the next message double-counts the span already written to history. No cheap fix at persist time; a durable counter baseline or chain-source from InfluxDB history is the Epic 4 / ingest-worker scope.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-persistence-atomicity
resolution-undo: e392b4fb8e5ec2fa1bc7188ab8b27689c91d143df4b1ab322ec8c1961d1618ab 2026-08-19 7374617475733a206f70656e

### DW-28: InfluxDB field-type conflict when a configured field's JSON value type varies across samples

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: InfluxTelemetryWriter.java
severity: high
reason: `InfluxTelemetryWriter.addOptionalField` infers Influx field types from the JSON node, so a configured field that alternates representation across samples (e.g. `vibration:2` vs `vibration:2.4`) triggers an InfluxDB field-type conflict that rejects subsequent points and silently drops telemetry. Fixing requires a type-stability strategy that changes the documented storage contract — architecture/Epic 6 scope per the spec's Block If boundary.
status: open
decision: 2026-08-20 Always coerce numeric to Double — Change addOptionalField in InfluxTelemetryWriter.java to always call point.addField(name, node.doubleValue()) for any numeric JSON node (both isIntegralNumber and isFloatingPointNumber), eliminating the Long vs Double type conflict. Update any downstream Flux queries that may rely on integer field semantics. Add a test asserting that integer and float samples for the same field name both write successfully.
decision: 2026-08-19 Always coerce numeric to Double — Change addOptionalField in InfluxTelemetryWriter.java to always call point.addField(name, node.doubleValue()) for any numeric JSON node (both isIntegralNumber and isFloatingPointNumber), eliminating the Long vs Double type conflict. Update any downstream Flux queries that may rely on integer field semantics. Add a test asserting that integer and float samples for the same field name both write successfully.

### DW-29: Config-time reserved-name denylist not future-proof against base-field promotion or InfluxDB system keys

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: TelemetryPayload.java
severity: low
reason: `RESERVED_OPTIONAL_FIELDS` is a static set; a future promotion of a field to the base contract, or a configured name colliding with InfluxDB system keys (`_field`/`_measurement`/`_value`/`_time`), would silently collide with base tags/fields on already-deployed machines. The config-time allowlist is not protected against future base-contract growth; verify InfluxDB 1.8 reserved-key semantics before extending the denylist.
status: open

### DW-30: MachineValidationException handler attributes all validation failures to the `code` field

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: MachineExceptionHandler.java
severity: medium
reason: `MachineExceptionHandler` maps every `MachineValidationException` to `Map.of("code", "Invalid value.")`, so Story 3.6's new config-rule rejections produce a `VALIDATION_ERROR` that blames the `code` field the client never touched. DTO-level failures correctly key the error on `optionalTelemetryFields`, but service-level rule failures route through the pre-existing shared handler's hardcoded `code` key. Fixing requires a field-aware exception/message contract shared by all machine validation paths.
status: open

### DW-31: Stale `optional.*` keys linger in the Redis latest hash after a field is removed from machine config

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: TelemetryPersistenceService.java
severity: low
reason: Removing a field from `machines.optional_telemetry_fields` does not delete previously-written `optional.<name>` keys from the latest hash; they persist until `latestTtl` expiry, so consumers (Stories 3.7/3.8) can render values for no-longer-configured fields. Cleanup would couple machine CRUD to telemetry latest-state; TTL-bounded and low impact.
status: done 2026-08-19
resolution: resolved by sweep bundle dw-telemetry-persistence-atomicity
resolution-undo: e392b4fb8e5ec2fa1bc7188ab8b27689c91d143df4b1ab322ec8c1961d1618ab 2026-08-19 7374617475733a206f70656e

### DW-32: Machine PUT without `optionalTelemetryFields` wipes a machine's configured optional fields

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: MachineService.java
severity: high
reason: `MachineService.update` is full-replace, so a client that omits or sends `null` for `optionalTelemetryFields` on a routine machine edit normalizes to `List.of()` and silently erases the machine's previously configured optional telemetry fields. Fixing requires either null-means-unchanged update semantics or frontend field passthrough coordinated with Stories 3.7/3.8 — a design decision, not a local code fix.
status: open

### DW-33: Sequential per-machine Redis reads on the machine list hydration path

origin: code review of spec-3-7-show-latest-telemetry-dashboard.md
location: MachineController.java
severity: medium
reason: `MachineController` hydrates each machine's latest telemetry with an individual `readLatestAsMap` call; a page of 200 ACTIVE machines issues up to 200 sequential Redis round trips with no batch/pipeline. Acceptable at current plant/fleet scale and the Design Notes explicitly defer batching (target p95 <500ms); candidate for a Redis pipeline/batch read when Epic 3 adds a performance hardening story.
status: open

### DW-34: Dashboard fetch capped at 200 machines with no truncation indicator

origin: code review of spec-3-7-show-latest-telemetry-dashboard.md
location: syncro/apps/web/src/features/telemetry/hooks/useTelemetryDashboardQuery.ts
severity: low
reason: `useTelemetryDashboardQuery` requests `size: 200` and renders whatever returns; if a plant scope ever exceeds 200 ACTIVE machines the dashboard silently drops the remainder with no "showing first N" notice or pagination. The spec's I/O matrix assumes plant-scoped counts well below 200; pagination/virtualization is out of Story 3.7 scope. Revisit when any plant approaches the cap.
status: open

### DW-35: `machineView.ts` generated types missing `latestTelemetry` field

origin: code review of spec-3-7-show-latest-telemetry-dashboard.md
location: syncro/apps/web/src/lib/api/generated/model/machineView.ts
severity: medium
reason: `machineView.ts` lacks the `latestTelemetry` field; the dashboard uses interim manual types in `features/telemetry/types/index.ts` cast from `useListMachines`. Regeneration needs the backend OpenAPI endpoint (`/v3/api-docs`) which was unavailable at implementation time. Tracked so the generated types are regenerated and the interim types removed once the backend contract is regenerated from the live OpenAPI doc.
status: done 2026-08-19
resolution: already resolved: syncro/apps/web/src/lib/api/generated/model/machineView.ts — latestTelemetry field now present in generated types

### DW-36: threshold_percentage CHECK constraint missing in sparepart_alerts

origin: code review of spec-4-2-create-threshold-alert-with-duplicate-prevention.md
location: V19__create_sparepart_alerts.sql
severity: low
reason: V19__create_sparepart_alerts.sql has no CHECK constraint on threshold_percentage column. Domain validation exists at Story 2.6 installation creation layer but DB has no guard against out-of-range values (negative or > 100). Upstream validation exists; deferred.
status: open

### DW-37: evaluateAll and alert creation share one catch block in TelemetryPersistenceService

origin: code review of spec-4-2-create-threshold-alert-with-duplicate-prevention.md
location: TelemetryPersistenceService.java
severity: low
reason: TelemetryPersistenceService.persist() wraps both evaluator.evaluateAll() and alertService.evaluateAndCreateAlerts() in one try/catch(Exception) block. A failure in evaluateAll silently skips alert creation with no separate signal. Cosmetic observability improvement, not functionally blocking.
status: open

### DW-38: auditLogWriter.recordSystem() has no try/catch in alert command service
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertCommandService.java
reason: Pre-existing pattern across codebase — audit failure rolls back via @Transactional; deliberate design.
status: open

### DW-39: Alert list query not invalidated after acknowledge mutation
origin: code review Story 4.4 (2026-08-19)
location: alert-detail-page-content.tsx
reason: AC8 only requires toast feedback; cross-page cache invalidation is UX polish deferred to later.
status: open

### DW-40: Double-submit race — no @Version on SparepartAlertEntity, no frontend debounce
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertCommandService.java, alert-detail-page-content.tsx
reason: Entity in-memory guard prevents corrupt state; optimistic locking is a pre-existing gap across all entities.
status: open
decision: 2026-08-19 Full fix: @Version + disabled button — Add @Version Long version field to SparepartAlertEntity with a migration, propagate OptimisticLockException to a 409 response, and disable the acknowledge button in alert-detail-page-content.tsx while the mutation is in-flight (isPending guard).
decision: 2026-08-19 Full fix: @Version + disabled button — Add @Version Long version field to SparepartAlertEntity with a migration, propagate OptimisticLockException to a 409 response, and disable the acknowledge button in alert-detail-page-content.tsx while the mutation is in-flight (isPending guard).

### DW-41: Empty string reason bypasses nullable contract — no @NotBlank or max-length guard
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertDtos.java, SparepartAlertEntity.java
reason: Pre-existing design across all alert mutations; reason field is intentionally permissive.
status: open

### DW-42: Frontend only handles 409 specifically — 403/404 indistinguishable to user
origin: code review Story 4.4 (2026-08-19)
location: alert-detail-page-content.tsx
reason: AC8 only requires 409-specific message; generic fallback for other status codes is acceptable per spec.
status: done 2026-08-19
resolution: already resolved: alert-detail-page-content.tsx:103-130 — 404 and 403 now handled with distinct UI messages; no longer indistinguishable to the user

### DW-44: notification_jobs unique constraint prevents ROUTING_FAILED→PENDING re-routing

origin: code review Story 5.2 (2026-08-20)
location: syncro/apps/backend/src/main/resources/db/migration/V22__create_notification_jobs.sql
reason: UNIQUE (alert_id, escalation_level) means once a ROUTING_FAILED row exists for a pair, a subsequent PENDING row for the same pair cannot be inserted. If a technician is assigned a WhatsApp number after initial routing failure, a new job cannot be queued without manual deletion of the failed row. Acceptable for Story 5.2 scope — re-routing belongs to a future admin/correction workflow.
status: open

## Deferred from: code review of spec-5-1-waha-template-editor (2026-08-20)

### DW-44: Race condition — concurrent upsert tanpa ON CONFLICT guard
origin: code review Story 5.1 (2026-08-20)
location: syncro/apps/backend/src/main/java/com/syncro/notification/application/WahaTemplateService.java:44
reason: Single-template config entity; concurrent admin edit extremely unlikely in pilot phase. findByTemplateKey + save is not atomic but risk window is negligible at current user load.
status: open

### DW-45: WahaTemplateView tidak expose createdAt
origin: code review Story 5.1 (2026-08-20)
location: syncro/apps/backend/src/main/java/com/syncro/notification/api/WahaTemplateDtos.java
reason: By design — frontend tidak butuh createdAt saat ini. Jika dibutuhkan di masa depan perlu tambah field ke DTO dan frontend.
status: open

## Deferred from: code review of 3-13-configure-mqtt-security-tls-device-auth-acls (2026-08-20)

- **DW-46: Plaintext credentials in auth-bootstrap.csv committed to git** — `auth-bootstrap.csv:2-3` contains literal MQTT passwords for `syncro_backend` and `device_BF-08410_GM1`. Dev-environment pattern consistent with all other infra files; production credential management out of scope for story 3-13.
- **DW-47: Hardcoded SEED_SECRET in docker-entrypoint.sh committed to git** — `docker-entrypoint.sh:37` hardcodes the administrator API key secret. Same dev-infra pattern; secret rotation and production secrets management out of scope.
- **DW-48: Erlang cluster cookie is a weak committed value** — `emqx.conf:4` sets `cookie = "emqxsyncrodev"`. Single-node dev setup; Erlang cluster security out of scope.

## Deferred from: code review of spec-5-4-escalate-alert-notifications-by-responsibility-level (2026-08-20)

- **DW-49: ROUTING_FAILED jobs never re-queried for escalation retry** — `EscalationService.java` — ROUTING_FAILED is a terminal state by design; re-routing workflow belongs to a future admin/correction story. Pre-existing design decision consistent with DW-44 pattern.
- **DW-50: `sentAt` column has no DB NOT NULL constraint despite being required for SENT-status jobs** — `NotificationJobEntity.java:52` — `sentAt` is set by `markSent()` before any job reaches SENT status; NULL-safety is enforced at application layer, not DB layer. Low risk; adding NOT NULL requires a Flyway migration coordinated with existing data.
- **DW-51: Infinite retry — closed/resolved alert leaves job in SENT forever** — `EscalationService.java:66` — AC10 by design requires job to stay SENT when alert is non-OPEN (protects re-open scenario). Permanent loop is theoretical; alert lifecycle cleanup (RESOLVED/DELETED) belongs to a future alert lifecycle story.

## Deferred from: code review of spec-5-6-show-escalation-timeline-and-notification-history (2026-08-20)

- **DW-52: Manual hand-edit of generated file will be overwritten** — `syncro/apps/web/src/lib/api/generated/model/auditLogEntryViewEntityType.ts:20` — `ALERT` added by hand; next `generate:api` will delete unless `openapi.yaml` updated; re-generate after backend boots — deferred, pre-existing generation flow.
- **DW-53: Duplicated DTOs mirrored in syncro.ts and alert-notification-history.tsx** — `syncro/apps/web/src/features/alerts/alert-notification-history.tsx:14` — intentional per Task 8 TODO to stay diff-free until next Orval regen; defer until generation.
- **DW-54: Attempt truncation slice(0,3) and CASE ELSE 99 ordering ambiguity** — `syncro/apps/web/src/features/alerts/alert-notification-history.tsx:373` / `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java:17` — `maxAttempts=3` today, unknown levels go to 99 without secondary sort; deferred, not actionable without schema change.

## Deferred from: code review of spec-5-9-implement-circuit-breaker-for-waha-calls (2026-08-21)

- **DW-55: `@Transactional` held across the WAHA network call (up to 5s per job, 10 jobs/batch)** — `NotificationDispatchService.java:50` — pre-existing transaction boundary, severity worsened by the added timeout; deferred, pre-existing.

### DW-46: DbHealthIndicator getConnection can block up to Hikari connection-timeout (30s)

origin: code review of spec-6-1-expose-dependency-health-checks (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/health/DbHealthIndicator.java:33
reason: matches Spring Boot's own DataSourceHealthIndicator behavior; a bounded acquire (e.g., Future.get(2s)) would require a non-pooled control connection.
status: open

### DW-47: RedisHealthIndicator ping inherits Lettuce command timeout (60s)

origin: code review of spec-6-1-expose-dependency-health-checks (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/health/RedisHealthIndicator.java:31-32
reason: same timeout profile as the replaced auto redisHealthContributor; setting a 2s command timeout requires client-level config.
status: open

### DW-48: MQTT mid-session broker outage not observable by health

origin: code review of spec-6-1-expose-dependency-health-checks (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttConnectionStatus.java:37-44
reason: pre-existing DW-14 TODO — Spring Integration MQTT 7.x emits no mid-session disconnect event; revisit when a connection-lost callback is available.
status: open

## Deferred from: WAHA GOWS 2026.8.1 upgrade verification (2026-08-21)

- **DW-56: GOWS returns HTTP 500 "no LID found" for invalid recipients — trips WAHA circuit breaker** — `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java:145-147` — After engine migration NOWEB → GOWS (image `devlikeapro/waha:gows-2026.8.1`), sending to a phone number not registered on WhatsApp returns HTTP 500 `"no LID found"` instead of a 4xx-class error. `doSend()` throws `WahaHttpStatusException` on any 5xx, which Resilience4j records as a failure — so a single mis-typed/invalid recipient in DB could push the WAHA circuit breaker to OPEN and block delivery of all other notification jobs. Unlike NOWEB, retrying such a job can never succeed. Suggested hardening (future story): treat 500 responses whose body indicates `no LID found` as a deterministic client error — return a failed `Result` without tripping the circuit, mirroring the existing 4xx path. Verified on 2026-08-21: real send to `6282124610363` succeeded (ack DEVICE); invalid `6280000000000` returned 500.
- **DW-57: GOWS represents incoming messages with `@lid` instead of `@c.us`** — n/a (receive-side) — Inbound messages are addressed as `from: 83524312952904@lid` with the real phone number only in `_data.Info.SenderAlt`. Not relevant while Syncro is send-only (`POST /api/sendText`); if a future story adds receive handling (auto-reply, delivery receipts), the `@lid` → phone conversion via LIDs API must be handled — behavior differs from NOWEB.
- **DW-58: WAHA session config still points webhooks to `https://httpbin.org/post`** — WAHA dashboard session `default` — The session's webhook list still posts `session.status` and `message` events to `httpbin.org/post` (leftover test config). No Syncro consumer exists; harmless but should be cleaned from the session config to avoid leaking message events to a third party.

## Deferred from: telemetry pipeline scale analysis for hundreds of machines per plant (2026-08-21)

- **DW-59: Telemetry ingest `workerThreads` is declared but never wired — queue consumed single-threaded** — `syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java:15` declares `ingest.worker-threads` (default 2) but nothing uses it: `TelemetryIngestQueueConfig.java:14` only reads `queueCapacity`, and the `IntegrationFlow` in `MqttSubscriptionConfig.java:53-60` has no explicit `.poller(...)`. The `QueueChannel` is therefore consumed by the default poller (single thread). With hundreds of machines per plant, every message serially performs Redis SETNX dedupe → InfluxDB HTTP write (3 retries + backoff) → Redis HSET → PostgreSQL counter-state save → sparepart lifetime evaluation → alert creation (~30-50ms/message ⇒ max ~20-33 msg/s). Estimate: 300 machines × 1 msg/5s = 60 msg/s ⇒ 1000-capacity queue fills in ~17s → send blocks → backpressure drops messages at EMQX. Fix belongs to a hardening story: wire `workerThreads` into the poller via a TaskExecutor and consider moving alert evaluation off the hot path.
- **DW-60: EMQX 6.2.2 (community) can offload InfluxDB/Redis writes via Data Integration — candidate for correct-course redesign** — `syncro/infra/docker-compose.yml:68` runs `emqx/emqx:6.2.2`. Verified via Management API (API key `syncro-acl-seed`): Rules Engine active (`$events/message_publish`, `$events/message_delivered`, etc.), and connector creation for `influxdb_api_v3` (plus redis/pgsql/mqtt/http/kafka bridge modules, 45 `emqx_bridge_*` libs) succeeds on community edition. This means the backend's per-message InfluxDB write (`InfluxTelemetryWriter`) and Redis latest-state write (`RedisLatestTelemetryWriter`) could move into EMQX rule bridges, dramatically reducing the backend hot path and improving scale for hundreds of machines/plant. Backend would then subscribe to a republished sink topic (e.g. `factory/+/+/processed`) and only do dedupe → counter state → sparepart evaluator → alerts. Note: business logic (sparepart evaluation, alert creation) stays in Java; this is an architecture-level change requiring a correct-course proposal and a PoC (rule + InfluxDB v3 bridge to local InfluxDB on 8181) before committing.
- **DW-61: `cleanSession=true` drops in-flight QoS-1 messages on reconnect — relevant at scale** — `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfig.java:33` — TODO DW-23 already flags this; with hundreds of machines the reconnect window widens and message loss probability grows. Switch to `cleanSession(false)` with a stable clientId once at-least-once delivery is a hard requirement; the Redis SETNX dedupe gate already handles duplicates.

## Deferred from: EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)

Verified directly against the running instance (`syncro-spring-emqx-1`, image `emqx/emqx:6.2.2`, license `community`) via Management API (`/api/v5`) with the `syncro-acl-seed` API key.

**Probe artifacts created during this exploration:**

- **DW-68: Probe artifacts left in EMQX from feature exploration need cleanup** — Running EMQX instance — Schema registry `telemetry-schema` (`POST /api/v5/schema_registry`), queues `que_test` and `syncro-latest-probe` (`POST /api/v5/queues`) were created as probes on 2026-08-21 and could not be removed (DELETE returned 404; queue deletion likely requires the `streams` module enabled, which is `enable=false`). Clean up via dashboard or by enabling streams module; harmless but clutter the EMQX UI.

**Confirmed available & working in community edition:**

- **DW-62: Rules Engine fully active** — `/api/v5/rules` + `/api/v5/rule_events` respond; SQL `SELECT * FROM "factory/+/+/telemetry"` with actions in `{type}:{name}` format. Verified action targets: `console`, `mqtt:forward`, `http:webhook` all accepted. Rule events available include `$events/message_publish`, `$events/message_delivered`, `$events/sys/alarm_activated`, `$events/sys/alarm_deactivated`. → Can offload: republish to sink topics, filtering, simple routing.
- **DW-63: Data Integration (Connectors) works — InfluxDB v3 bridge confirmed** — 45 `emqx_bridge_*` libs installed. Successfully created connector `type=influxdb` with `parameters.influxdb_type=influxdb_api_v3` (+ `token`, `database`, `ping_with_auth`); validation passed, connector instantiated (was `disconnected` only because probe host was unreachable). Same endpoint family covers redis, pgsql, mqtt, http, kafka bridges. → Can offload backend InfluxDB write (`InfluxTelemetryWriter`) and Redis latest-state write (`RedisLatestTelemetryWriter`) to EMQX bridges.
- **DW-64: Schema Registry works — JSON schema created** — `POST /api/v5/schema_registry` accepted a JSON schema (fields: `type=json`, `name`, `description`, `source`). Schema validation (`schema_validation`) and message transformation (`message_transformation`) config keys exist but have no `/api/v5` CRUD endpoint (config-file/ctl only). → Can offload `TelemetryValidationService` JSON field validation to EMQX edge; quarantine-on-invalid would still be backend logic.
- **DW-65: EMQX Queue (MQTT Streams) — last-value mode works** — `POST /api/v5/queues` created a queue with `is_lastvalue=true`, `key_expression=message.from`, `topic_filter=factory/+/+/telemetry`, `data_retention_period=604800000ms` (7d). This gives per-source "latest value" storage inside EMQX. → Candidate to REPLACE Redis `syncro:machine:{id}:latest` hash (per-machine latest telemetry) for the counting-delta read path. Streams module (`streams`) exists but `enable=false` (needs config flip + restart).
- **DW-66: Retained messages, delayed publish, shared subscriptions, auto-subscribe available** — `mqtt.retainer {enable=true, backend=built_in_database}`, `mqtt/delayed {enable=true}`, `shared_subscription=true` (round_robin), `auto_subscribe` config present. Shared subscriptions (`$share/{group}/factory/+/+/telemetry`) enable horizontal scaling of backend consumers — direct mitigation for the DW-59 single-consumer bottleneck.
- **DW-67: NOT available on community edition** — Flow Designer (`/api/v5/flows` 404), Codec (`/api/v5/codecs` 404), Message Transformation REST API (`/api/v5/transformations` 404), standalone Webhooks API (`/api/v5/webhooks` 404 — webhook is only a rule action). These appear to be enterprise-tier or config-only.

**Mapping to Syncro backend offloading (feed the correct-course discussion):**

| Backend component today | EMQX alternative | Verdict |
|---|---|---|
| `MqttTelemetryIngestHandler` + `TelemetryValidationService` | Schema Registry + schema validation at edge | Partially offloadable; quarantine logic stays |
| `InfluxTelemetryWriter` (per-msg HTTP + retry) | InfluxDB v3 bridge via rule | **Offload — removes biggest per-msg IO** |
| `RedisLatestTelemetryWriter` (latest hash) | EMQX Queue last-value / Redis bridge | **Offload — replaces Redis hot path** |
| `QueueChannel` + `workerThreads` (never wired) | Shared subscriptions `$share/...` | Offload concurrency to MQTT layer, OR wire workerThreads (DW-59) |
| `CountingDeltaCalculator` | EMQX rule SQL (previous/current) | Business logic — keep in backend |
| `SparepartLifetimeEvaluator` + alerts | n/a | Business logic — keep in backend |

**Caveats:** every bridge/queue needs a PoC with real traffic; EMQX config changes live in `infra/emqx/etc/emqx.conf` + `docker-entrypoint.sh` and require container restart; InfluxDB bridge must reach InfluxDB via container network name (`influxdb:8181`), not `localhost`; dedupe (`SETNX`) and quarantine semantics must be preserved regardless of where validation/writing happens.

### DW-NEW: Notification worker status is pod-local (per-JVM tracker)

origin: Deferred from: code review of spec-6-3-report-notification-worker-status (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerTracker.java:16
reason: In a multi-replica deployment the endpoint reports only the calling instance's poll liveness. Acceptable for single-instance; documented by design (in-memory, resets on restart). Distributed observability is out of current scope.
status: open

### DW-NEW: WAHA circuit failureRate may serialize as non-finite JSON (NaN) and break actuator parse

origin: Deferred from: code review of spec-6-4-build-super-admin-health-dashboard (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java + syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.ts
reason: Resilience4j Metrics.getFailureRate() returns NaN until minimumNumberOfCalls is reached. If Spring's Jackson writes NaN unquoted, the entire /actuator/health JSON becomes invalid and the frontend hook throws, collapsing all five dependency cards. Frontend defensively treats per-parse failure as an error, but the whole-section collapse is undesirable. Needs verification against the running backend (Jackson QUOTE_NON_NUMERIC_NUMBERS default) and, if confirmed, a backend-side clamp/config plus a per-component-parse isolation on the frontend. Cross-stack, backend-owned; not fixable purely in story 6.4.
status: open

### DW-69: TelemetryIngestTracker monotonic guard can pin freshness LIVE through a backward clock step

- source_spec: _bmad-output/implementation-artifacts/spec-6-5-surface-latest-telemetry-freshness-in-health.md
  summary: TelemetryIngestTracker.recordAccepted() keeps lastAcceptedAt monotonic, so a backward NTP clock step leaves lastAcceptedAt in the "future" and freshness reports LIVE (clock-skew branch) until wall time catches up — masking a genuinely stalled ingest path.
  evidence: TelemetryIngestTracker.java:30 (
ow.isAfter(lastAcceptedAt)) combined with TelemetryFreshnessService.java negative-elapsed LIVE branch; observed during review of the new freshness contract (2026-08-21). Fixing means either accepting an unconditional lastAcceptedAt = now (changes the shared tracker also consumed by the 6.4 ingest worker status) or surfacing skew as its own state — a shared-tracker decision, not this story's code.
