# Deferred Work

## Decision Queue (sweep 2026-08-23)

Semua entri open yang tersisa butuh keputusan manusia, KECUALI DW-121 (bundle siap). Urutan bebas; pilih satu lalu jalankan resolve/dev-auto.

| Tema | DW | Pilihan ringkas |
|---|---|---|
| Clock/tracker policy | DW-26, DW-69, DW-70, DW-72 | monotonic-reset / surface-skew-state / accept-transient |
| Deep-link machine resolution | DW-71 | by-id route / plant-scoped route |
| Seed & fixture pinning | DW-73, DW-74 | extract pure derivation component / accept literal pinning |
| Secrets management | DW-78, DW-79, DW-80 | vault atau env-injection / scoped-ignore + docs / biarkan dev-only |
| Health timeout desain | DW-46, DW-47 | bounded-acquire executor / client-level timeout config / terima default Spring |
| EMQX offload correct-course | DW-92 | PoC bridge InfluxDB/Redis / tunda ke epic berikutnya / tolak |
| WAHA disclaimer vs evidence | DW-112 | tulis ulang disclaimer / sesuaikan teks evidence |
| InfluxDB legacy buckets | DW-114 | reset bucket dev-CI / script migrasi double |
| Bundle siap eksekusi | DW-121 | escape pada findCodesByPrefix + tes |


### DW-1: MQTT credentials configured but local EMQX MQTT auth not enforced

origin: migrated from legacy ledger ("Deferred from: code review of 1-3-initialize-spring-boot-backend-skeleton (2026-05-25)"), 2026-08-07
location: syncro backend MQTT/EMQX broker configuration
reason: Local broker MQTT authentication belongs to later EMQX security/auth configuration scope, not Story 1.3 backend skeleton.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 1 (EMQX MQTT auth, optimistic locking, Influx coercion) — local MQTT password default aligned with auth-bootstrap.csv; enforcement proven by MqttAuthEnforcementIntegrationTest (real EMQX Testcontainer)
decision: 2026-08-19 Bundle it now — Create a dev session that enables EMQX password authentication in docker-compose (EMQX_AUTH__MNESIA__PASSWORD_HASH or built-in DB), updates application.yml MQTT client credentials, and verifies the Spring MQTT client connects successfully with auth enforced.
decision: 2026-08-19 Bundle it now — Create a dev session that enables EMQX password authentication in docker-compose (EMQX_AUTH__MNESIA__PASSWORD_HASH or built-in DB), updates application.yml MQTT client credentials, and verifies the Spring MQTT client connects successfully with auth enforced.

### DW-2: Add durable UI state evidence for AC11

origin: migrated from legacy ledger ("Deferred from: code review of 2-2-manage-plant-scoped-machine-groups (2026-05-27)"), 2026-08-07
location: syncro/apps/web/src/features/master-data/machine-groups/machine-group-management.tsx:176
reason: Current code/browser evidence is acceptable for this review, but component tests or captured trace for empty/loading/error/read-only/forbidden/validation states would make regression proof stronger.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 8 — component suite already covered all six states (pre-existing work surfaced during bundle); bundle added hardening tests: Retry refetches both lists, read-only rows expose View only badges

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
status: done 2026-08-23
resolution: resolved by deferred-work bundle 12 — 7 audit-log web it.skip revived (P0 pagination-reset gap fixed in product) and all 4 backend ATDD scaffold classes activated (15/15 green) after repairing stale fixtures/wiring; revival surfaced a real production bug (actor LIKE missing ESCAPE clause) fixed with a one-line change

### DW-9: Missing optimistic locking on Installation entity

origin: migrated from legacy ledger ("Deferred from: code review of 2-6-install-spareparts-on-machines-with-lifetime-baseline.md (2026-06-05)"), 2026-08-07
location: Installation entity (backend)
reason: Missing optimistic locking on Installation entity — deferred, pre-existing.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 1 — @Version added to MachineSparepartInstallationEntity (V32) with ObjectOptimalLockingFailureException → 409 CONCURRENT_MODIFICATION mapping
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
status: done 2026-08-23
resolution: resolved by deferred-work bundle 5 — @FunctionalInterface TelemetryValidator decouples the handler/wiring test from the DB-backed service; MqttSubscriptionConfigTest supplies a lightweight stub

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
status: done 2026-08-23
resolution: resolved by deferred-work bundle 1 — addOptionalField always coerces numeric JSON nodes via doubleValue() (InfluxTelemetryWriter.java:51); int/float same-field unit + real-Influx tests added
decision: 2026-08-20 Always coerce numeric to Double — Change addOptionalField in InfluxTelemetryWriter.java to always call point.addField(name, node.doubleValue()) for any numeric JSON node (both isIntegralNumber and isFloatingPointNumber), eliminating the Long vs Double type conflict. Update any downstream Flux queries that may rely on integer field semantics. Add a test asserting that integer and float samples for the same field name both write successfully.
decision: 2026-08-19 Always coerce numeric to Double — Change addOptionalField in InfluxTelemetryWriter.java to always call point.addField(name, node.doubleValue()) for any numeric JSON node (both isIntegralNumber and isFloatingPointNumber), eliminating the Long vs Double type conflict. Update any downstream Flux queries that may rely on integer field semantics. Add a test asserting that integer and float samples for the same field name both write successfully.

### DW-29: Config-time reserved-name denylist not future-proof against base-field promotion or InfluxDB system keys

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: TelemetryPayload.java
severity: low
reason: `RESERVED_OPTIONAL_FIELDS` is a static set; a future promotion of a field to the base contract, or a configured name colliding with InfluxDB system keys (`_field`/`_measurement`/`_value`/`_time`), would silently collide with base tags/fields on already-deployed machines. The config-time allowlist is not protected against future base-contract growth; verify InfluxDB 1.8 reserved-key semantics before extending the denylist.
status: done 2026-08-23
resolution: already resolved: MachineService.java:265 rejects optional-field names starting with "_" — covering every InfluxDB system key (_field/_measurement/_value/_time) — and lines 39-40 pin all current base fields; remaining "future promotion" concern is governance, not code

### DW-30: MachineValidationException handler attributes all validation failures to the `code` field

origin: code review of spec-3-6-support-optional-machine-telemetry-fields.md
location: MachineExceptionHandler.java
severity: medium
reason: `MachineExceptionHandler` maps every `MachineValidationException` to `Map.of("code", "Invalid value.")`, so Story 3.6's new config-rule rejections produce a `VALIDATION_ERROR` that blames the `code` field the client never touched. DTO-level failures correctly key the error on `optionalTelemetryFields`, but service-level rule failures route through the pre-existing shared handler's hardcoded `code` key. Fixing requires a field-aware exception/message contract shared by all machine validation paths.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 11 — MachineValidationException carries fieldErrors; all eight throw sites annotated (required fields collected together, optionalTelemetryFields reasons quote the entry, page/size/sort/code blame their own param); handler passes map verbatim; frontend form renders keys in-place

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
decision: 2026-08-23 Interactive resolve (bmad-loop-resolve method) — Option A chosen: null = leave stored config untouched; explicit [] clears; populated list replaces. Server-side fix protects ALL clients; FE passthrough unnecessary for safety.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 14 — update() preserves stored config when the JSON key is absent; validateCommand split so required-field collection still runs; integration tests pin all three semantics (absent=preserve / []=clear / list=replace); no existing expectation broke

### DW-33: Sequential per-machine Redis reads on the machine list hydration path

origin: code review of spec-3-7-show-latest-telemetry-dashboard.md
location: MachineController.java
severity: medium
reason: `MachineController` hydrates each machine's latest telemetry with an individual `readLatestAsMap` call; a page of 200 ACTIVE machines issues up to 200 sequential Redis round trips with no batch/pipeline. Acceptable at current plant/fleet scale and the Design Notes explicitly defer batching (target p95 <500ms); candidate for a Redis pipeline/batch read when Epic 3 adds a performance hardening story.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 9 — readLatestBatch (executePipelined) + latestTelemetryBatch power the LIST endpoint with one round trip; pairing directly tested; GET paths unchanged

### DW-34: Dashboard fetch capped at 200 machines with no truncation indicator

origin: code review of spec-3-7-show-latest-telemetry-dashboard.md
location: syncro/apps/web/src/features/telemetry/hooks/useTelemetryDashboardQuery.ts
severity: low
reason: `useTelemetryDashboardQuery` requests `size: 200` and renders whatever returns; if a plant scope ever exceeds 200 ACTIVE machines the dashboard silently drops the remainder with no "showing first N" notice or pagination. The spec's I/O matrix assumes plant-scoped counts well below 200; pagination/virtualization is out of Story 3.7 scope. Revisit when any plant approaches the cap.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 8 — truncation notice (aria-live) derived from totalElements > items.length, truthful for exact-fit pages and server-capped responses alike; singular/plural copy fixed

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
status: done 2026-08-23
resolution: resolved by deferred-work bundle 4 — V35__sparepart_alerts_threshold_check.sql adds CHECK (threshold_percentage BETWEEN 0 AND 100) with in/out-of-range migration tests

### DW-37: evaluateAll and alert creation share one catch block in TelemetryPersistenceService

origin: code review of spec-4-2-create-threshold-alert-with-duplicate-prevention.md
location: TelemetryPersistenceService.java
severity: low
reason: `TelemetryPersistenceService.persist` wraps both evaluator.evaluateAll() and alertService.evaluateAndCreateAlerts() in one try/catch(Exception) block. A failure in evaluateAll silently skips alert creation with no separate signal. Cosmetic observability improvement, not functionally blocking.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 9 — catches split with distinct markers sparepart_lifetime_evaluation_failed vs sparepart_alert_evaluation_failed; persisted INFO test-enforced

### DW-38: auditLogWriter.recordSystem() has no try/catch in alert command service
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertCommandService.java
reason: Pre-existing pattern across codebase — audit failure rolls back via @Transactional; deliberate design.
status: skipped 2026-08-23
resolution: skipped - deliberate design - failure intentionally rolls back via Transactional

### DW-39: Alert list query not invalidated after acknowledge mutation
origin: code review Story 4.4 (2026-08-19)
location: alert-detail-page-content.tsx
reason: AC8 only requires toast feedback; cross-page cache invalidation is UX polish deferred to later.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 8 — acknowledge/resolve/resolve-override invalidate getListAlertsQueryKey(); mutation-wiring test pins list refresh + detail refetch

### DW-40: Double-submit race — no @Version on SparepartAlertEntity, no frontend debounce
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertCommandService.java, alert-detail-page-content.tsx
reason: Entity in-memory guard prevents corrupt state; optimistic locking is a pre-existing gap across all entities.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 1 — @Version on SparepartAlertEntity (V33), OOLFE → 409 CONCURRENT_MODIFICATION in alert exception handler; acknowledge isPending guard verified
decision: 2026-08-19 Full fix: @Version + disabled button — Add @Version Long version field to SparepartAlertEntity with a migration, propagate OptimisticLockException to a 409 response, and disable the acknowledge button in alert-detail-page-content.tsx while the mutation is in-flight (isPending guard).
decision: 2026-08-19 Full fix: @Version + disabled button — Add @Version Long version field to SparepartAlertEntity with a migration, propagate OptimisticLockException to a 409 response, and disable the acknowledge button in alert-detail-page-content.tsx while the mutation is in-flight (isPending guard).

### DW-41: Empty string reason bypasses nullable contract — no @NotBlank or max-length guard
origin: code review Story 4.4 (2026-08-19)
location: SparepartAlertDtos.java, SparepartAlertEntity.java
reason: Pre-existing design across all alert mutations; reason field is intentionally permissive.
status: skipped 2026-08-23
resolution: skipped - intentionally permissive empty reason across alert mutations

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
status: skipped 2026-08-23
resolution: skipped - re-routing belongs to unnamed future admin/correction workflow

### DW-77: Race condition — concurrent upsert tanpa ON CONFLICT guard

origin: migrated from legacy ledger ("code review of spec-5-1-waha-template-editor (2026-08-20)"), 2026-08-22
location: syncro/apps/backend/src/main/java/com/syncro/notification/application/WahaTemplateService.java:44
severity: low
reason: Single-template config entity; concurrent admin edit extremely unlikely in pilot phase. findByTemplateKey + save is not atomic but risk window is negligible at current user load.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 3 — WahaTemplateService.upsertTemplate uses atomic repository.upsert (native INSERT ... ON CONFLICT) instead of findByTemplateKey + save

### DW-45: WahaTemplateView tidak expose createdAt
origin: code review Story 5.1 (2026-08-20)
location: syncro/apps/backend/src/main/java/com/syncro/notification/api/WahaTemplateDtos.java
reason: By design — frontend tidak butuh createdAt saat ini. Jika dibutuhkan di masa depan perlu tambah field ke DTO dan frontend.
status: skipped 2026-08-23
resolution: skipped - by design - createdAt not needed by frontend today

### DW-78: Plaintext credentials in auth-bootstrap.csv committed to git

origin: migrated from legacy ledger ("code review of 3-13-configure-mqtt-security-tls-device-auth-acls (2026-08-20)"), 2026-08-22
location: auth-bootstrap.csv:2-3
severity: high
reason: auth-bootstrap.csv:2-3 contains literal MQTT passwords for syncro_backend and device_BF-08410_GM1. Dev-environment pattern consistent with all other infra files; production credential management out of scope for story 3-13.
status: open

### DW-79: Hardcoded SEED_SECRET in docker-entrypoint.sh committed to git

origin: migrated from legacy ledger ("code review of 3-13-configure-mqtt-security-tls-device-auth-acls (2026-08-20)"), 2026-08-22
location: docker-entrypoint.sh:37
severity: high
reason: docker-entrypoint.sh:37 hardcodes the administrator API key secret. Same dev-infra pattern; secret rotation and production secrets management out of scope.
status: open

### DW-80: Erlang cluster cookie is a weak committed value

origin: migrated from legacy ledger ("code review of 3-13-configure-mqtt-security-tls-device-auth-acls (2026-08-20)"), 2026-08-22
location: emqx.conf:4
severity: medium
reason: emqx.conf:4 sets cookie = "emqxsyncrodev". Single-node dev setup; Erlang cluster security out of scope.
status: open

### DW-81: ROUTING_FAILED jobs never re-queried for escalation retry

origin: migrated from legacy ledger ("code review of spec-5-4-escalate-alert-notifications-by-responsibility-level (2026-08-20)"), 2026-08-22
location: EscalationService.java
severity: medium
reason: ROUTING_FAILED is a terminal state by design; re-routing workflow belongs to a future admin/correction story. Pre-existing design decision consistent with DW-44 pattern.
status: skipped 2026-08-23
resolution: skipped - AC10 by design - SENT preserved for reopen tracking; lifecycle cleanup is a future story

### DW-82: `sentAt` column has no DB NOT NULL constraint despite being required for SENT-status jobs

origin: migrated from legacy ledger ("code review of spec-5-4-escalate-alert-notifications-by-responsibility-level (2026-08-20)"), 2026-08-22
location: NotificationJobEntity.java:52
severity: low
reason: `sentAt` is set by `markSent()` before any job reaches SENT status; NULL-safety is enforced at application layer, not DB layer. Low risk; adding NOT NULL requires a Flyway migration coordinated with existing data.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 3 (corrected approach) — V34 adds chk_notification_jobs_sent_requires_sent_at CHECK (status <> 'SENT' OR sent_at IS NOT NULL) instead of plain NOT NULL

### DW-83: Infinite retry — closed/resolved alert leaves job in SENT forever

origin: migrated from legacy ledger ("code review of spec-5-4-escalate-alert-notifications-by-responsibility-level (2026-08-20)"), 2026-08-22
location: EscalationService.java:66
severity: low
reason: AC10 by design requires job to stay SENT when alert is non-OPEN (protects re-open scenario). Permanent loop is theoretical; alert lifecycle cleanup (RESOLVED/DELETED) belongs to a future alert lifecycle story.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 6 — findSentJobsDueForEscalation joins SparepartAlertEntity filtering status=OPEN; closed-alert SENT jobs no longer re-polled (job stays SENT for reopen tracking; EscalationService untouched)

### DW-84: Manual hand-edit of generated file will be overwritten

origin: migrated from legacy ledger ("code review of spec-5-6-show-escalation-timeline-and-notification-history (2026-08-20)"), 2026-08-22
location: syncro/apps/web/src/lib/api/generated/model/auditLogEntryViewEntityType.ts:20
severity: medium
reason: `ALERT` added by hand; next `generate:api` will delete unless `openapi.yaml` updated; re-generate after backend boots — deferred, pre-existing generation flow.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 10 — client regenerated against live backend (ALERT pre-verified in /v3/api-docs); auditLogEntryViewEntityType now generator-produced, empty re-regen diff

### DW-85: Duplicated DTOs mirrored in syncro.ts and alert-notification-history.tsx

origin: migrated from legacy ledger ("code review of spec-5-6-show-escalation-timeline-and-notification-history (2026-08-20)"), 2026-08-22
location: syncro/apps/web/src/features/alerts/alert-notification-history.tsx:14
severity: low
reason: intentional per Task 8 TODO to stay diff-free until next Orval regen; defer until generation.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 10 — local DTO mirrors deleted; component adopts generated types via normalizeJob boundary; new tests pin rendering

### DW-86: Attempt truncation slice(0,3) and CASE ELSE 99 ordering ambiguity

origin: migrated from legacy ledger ("code review of spec-5-6-show-escalation-timeline-and-notification-history (2026-08-20)"), 2026-08-22
location: syncro/apps/web/src/features/alerts/alert-notification-history.tsx:373 / syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java:17
severity: low
reason: `maxAttempts=3` today, unknown levels go to 99 without secondary sort; deferred, not actionable without schema change.
status: done 2026-08-23
resolution: split resolution — backend ordering half already resolved (findByAlertIdOrderByEscalationOrder has `else 99 end asc, j.createdAt asc` secondary sort); frontend slice(0,3) removed by deferred-work bundle 10 with render tests

### DW-87: `@Transactional` held across the WAHA network call (up to 5s per job, 10 jobs/batch)

origin: migrated from legacy ledger ("code review of spec-5-9-implement-circuit-breaker-for-waha-calls (2026-08-21)"), 2026-08-22
location: NotificationDispatchService.java:50
severity: high
reason: `NotificationDispatchService.java:50` — pre-existing transaction boundary, severity worsened by the added timeout; deferred, pre-existing.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 6 — @Transactional removed from dispatch(); TransactionTemplate wraps each DB write phase so the WAHA call runs outside any transaction (no pooled-connection hold)

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

### DW-88: GOWS returns HTTP 500 "no LID found" for invalid recipients — trips WAHA circuit breaker

origin: migrated from legacy ledger ("WAHA GOWS 2026.8.1 upgrade verification (2026-08-21)"), 2026-08-22
location: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java:145-147
severity: high
reason: After engine migration NOWEB → GOWS (image `devlikeapro/waha:gows-2026.8.1`), sending to a phone number not registered on WhatsApp returns HTTP 500 `"no LID found"` instead of a 4xx-class error. `doSend()` throws `WahaHttpStatusException` on any 5xx, which Resilience4j records as a failure — so a single mis-typed/invalid recipient in DB could push the WAHA circuit breaker to OPEN and block delivery of all other notification jobs. Unlike NOWEB, retrying such a job can never succeed. Suggested hardening (future story): treat 500 responses whose body indicates `no LID found` as a deterministic client error — return a failed `Result` without tripping the circuit, mirroring the existing 4xx path. Verified on 2026-08-21: real send to `6282124610363` succeeded (ack DEVICE); invalid `6280000000000` returned 500.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 3 — WahaClient treats GOWS HTTP 500 "no LID found" as a deterministic client failure (Result, no circuit trip) mirroring the 4xx path (WahaClient.java:146-151)

### DW-89: GOWS represents incoming messages with `@lid` instead of `@c.us`

origin: migrated from legacy ledger ("WAHA GOWS 2026.8.1 upgrade verification (2026-08-21)"), 2026-08-22
location: n/a (receive-side)
severity: low
reason: Inbound messages are addressed as `from: 83524312952904@lid` with the real phone number only in `_data.Info.SenderAlt`. Not relevant while Syncro is send-only (`POST /api/sendText`); if a future story adds receive handling (auto-reply, delivery receipts), the `@lid` → phone conversion via LIDs API must be handled — behavior differs from NOWEB.
status: skipped 2026-08-23
resolution: skipped - receive-side irrelevant while Syncro is send-only

### DW-90: WAHA session config still points webhooks to `https://httpbin.org/post`

origin: migrated from legacy ledger ("WAHA GOWS 2026.8.1 upgrade verification (2026-08-21)"), 2026-08-22
location: WAHA dashboard session `default`
severity: medium
reason: The session's webhook list still posts `session.status` and `message` events to `httpbin.org/post` (leftover test config). No Syncro consumer exists; harmless but should be cleaned from the session config to avoid leaking message events to a third party.
status: done 2026-08-23
resolution: already resolved (verified live): GET /api/sessions/default shows webhooks=null — httpbin webhook no longer present (reset during the NOWEB->GOWS engine migration); no other session exists

### DW-91: Telemetry ingest `workerThreads` is declared but never wired — queue consumed single-threaded

origin: migrated from legacy ledger ("telemetry pipeline scale analysis for hundreds of machines per plant (2026-08-21)"), 2026-08-22
location: syncro/apps/backend/src/main/java/com/syncro/config/TelemetryProperties.java:15
severity: high
reason: `TelemetryProperties.java:15` declares `ingest.worker-threads` (default 2) but nothing uses it: `TelemetryIngestQueueConfig.java:14` only reads `queueCapacity`, and the `IntegrationFlow` in `MqttSubscriptionConfig.java:53-60` has no explicit `.poller(...)`. The `QueueChannel` is therefore consumed by the default poller (single thread). With hundreds of machines per plant, every message serially performs Redis SETNX dedupe → InfluxDB HTTP write (3 retries + backoff) → Redis HSET → PostgreSQL counter-state save → sparepart lifetime evaluation → alert creation (~30-50ms/message ⇒ max ~20-33 msg/s). Estimate: 300 machines × 1 msg/5s = 60 msg/s ⇒ 1000-capacity queue fills in ~17s → send blocks → backpressure drops messages at EMQX. Fix belongs to a hardening story: wire `workerThreads` into the poller via a TaskExecutor and consider moving alert evaluation off the hot path.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 5 — ThreadPoolTaskExecutor wired into the IntegrationFlow poller (MqttSubscriptionConfig.java:62) sized by ingest.workerThreads; striped PerMachineExecution serializes the per-machine counting-delta chain while cross-machine messages parallelize

### DW-92: EMQX 6.2.2 (community) can offload InfluxDB/Redis writes via Data Integration — candidate for correct-course redesign

origin: migrated from legacy ledger ("telemetry pipeline scale analysis for hundreds of machines per plant (2026-08-21)"), 2026-08-22
location: syncro/infra/docker-compose.yml:68
severity: medium
reason: `syncro/infra/docker-compose.yml:68` runs `emqx/emqx:6.2.2`. Verified via Management API (API key `syncro-acl-seed`): Rules Engine active (`$events/message_publish`, `$events/message_delivered`, etc.), and connector creation for `influxdb_api_v3` (plus redis/pgsql/mqtt/http/kafka bridge modules, 45 `emqx_bridge_*` libs) succeeds on community edition. This means the backend's per-message InfluxDB write (`InfluxTelemetryWriter`) and Redis latest-state write (`RedisLatestTelemetryWriter`) could move into EMQX rule bridges, dramatically reducing the backend hot path and improving scale for hundreds of machines/plant. Backend would then subscribe to a republished sink topic (e.g. `factory/+/+/processed`) and only do dedupe → counter state → sparepart evaluator → alerts. Note: business logic (sparepart evaluation, alert creation) stays in Java; this is an architecture-level change requiring a correct-course proposal and a PoC (rule + InfluxDB v3 bridge to local InfluxDB on 8181) before committing.
status: open

Mapping to Syncro backend offloading (feed the correct-course discussion):

| Backend component today | EMQX alternative | Verdict |
|---|---|---|
| `MqttTelemetryIngestHandler` + `TelemetryValidationService` | Schema Registry + schema validation at edge | Partially offloadable; quarantine logic stays |
| `InfluxTelemetryWriter` (per-msg HTTP + retry) | InfluxDB v3 bridge via rule | **Offload — removes biggest per-msg IO** |
| `RedisLatestTelemetryWriter` (latest hash) | EMQX Queue last-value / Redis bridge | **Offload — replaces Redis hot path** |
| `QueueChannel` + `workerThreads` (never wired) | Shared subscriptions `$share/...` | Offload concurrency to MQTT layer, OR wire workerThreads (DW-91) |
| `CountingDeltaCalculator` | EMQX rule SQL (previous/current) | Business logic — keep in backend |
| `SparepartLifetimeEvaluator` + alerts | n/a | Business logic — keep in backend |

Caveats: every bridge/queue needs a PoC with real traffic; EMQX config changes live in `infra/emqx/etc/emqx.conf` + `docker-entrypoint.sh` and require container restart; InfluxDB bridge must reach InfluxDB via container network name (`influxdb:8181`), not `localhost`; dedupe (`SETNX`) and quarantine semantics must be preserved regardless of where validation/writing happens.

### DW-93: `cleanSession=true` drops in-flight QoS-1 messages on reconnect — relevant at scale

origin: migrated from legacy ledger ("telemetry pipeline scale analysis for hundreds of machines per plant (2026-08-21)"), 2026-08-22
location: syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfig.java:33
severity: medium
reason: TODO DW-23 already flags this; with hundreds of machines the reconnect window widens and message loss probability grows. Switch to `cleanSession(false)` with a stable clientId once at-least-once delivery is a hard requirement; the Redis SETNX dedupe gate already handles duplicates.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 5 — setCleanSession(false) (MqttSubscriptionConfig.java:33) with stable clientId; broker redelivers in-flight QoS-1 messages, SETNX dedupe absorbs duplicates

### DW-94: Probe artifacts left in EMQX from feature exploration need cleanup

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: Running EMQX instance (schema registry `telemetry-schema`; queues `que_test`, `syncro-latest-probe`)
severity: low
reason: Schema registry `telemetry-schema` (`POST /api/v5/schema_registry`), queues `que_test` and `syncro-latest-probe` (`POST /api/v5/queues`) were created as probes on 2026-08-21 and could not be removed (DELETE returned 404; queue deletion likely requires the `streams` module enabled, which is `enable=false`). Clean up via dashboard or by enabling streams module; harmless but clutter the EMQX UI.
status: done 2026-08-23
resolution: resolved live — dashboard login (Bearer) used; schema `telemetry-schema` DELETEd; the earlier queue-DELETE 404 was a wrong route (list=/queues but per-item ops=/queue/{name}, confirmed against /api-spec.json); streams.enable flipped true at runtime via `emqx ctl conf load`, both probe queues DELETEd, then enable restored to false. Final state: schema_registry empty, queues empty

### DW-95: Rules Engine fully active

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX `/api/v5/rules` + `/api/v5/rule_events`
severity: low
reason: `/api/v5/rules` + `/api/v5/rule_events` respond; SQL `SELECT * FROM "factory/+/+/telemetry"` with actions in `{type}:{name}` format. Verified action targets: `console`, `mqtt:forward`, `http:webhook` all accepted. Rule events available include `$events/message_publish`, `$events/message_delivered`, `$events/sys/alarm_activated`, `$events/sys/alarm_deactivated`. → Can offload: republish to sink topics, filtering, simple routing.
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-96: Data Integration (Connectors) works — InfluxDB v3 bridge confirmed

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX `/api/v5/connectors` (type=influxdb, influxdb_api_v3)
severity: low
reason: 45 `emqx_bridge_*` libs installed. Successfully created connector `type=influxdb` with `parameters.influxdb_type=influxdb_api_v3` (+ `token`, `database`, `ping_with_auth`); validation passed, connector instantiated (was `disconnected` only because probe host was unreachable). Same endpoint family covers redis, pgsql, mqtt, http, kafka bridges. → Can offload backend InfluxDB write (`InfluxTelemetryWriter`) and Redis latest-state write (`RedisLatestTelemetryWriter`) to EMQX bridges.
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-97: Schema Registry works — JSON schema created

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX `/api/v5/schema_registry`
severity: low
reason: `POST /api/v5/schema_registry` accepted a JSON schema (fields: `type=json`, `name`, `description`, `source`). Schema validation (`schema_validation`) and message transformation (`message_transformation`) config keys exist but have no `/api/v5` CRUD endpoint (config-file/ctl only). → Can offload `TelemetryValidationService` JSON field validation to EMQX edge; quarantine-on-invalid would still be backend logic.
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-98: EMQX Queue (MQTT Streams) — last-value mode works

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX `/api/v5/queues`
severity: low
reason: `POST /api/v5/queues` created a queue with `is_lastvalue=true`, `key_expression=message.from`, `topic_filter=factory/+/+/telemetry`, `data_retention_period=604800000ms` (7d). This gives per-source "latest value" storage inside EMQX. → Candidate to REPLACE Redis `syncro:machine:{id}:latest` hash (per-machine latest telemetry) for the counting-delta read path. Streams module (`streams`) exists but `enable=false` (needs config flip + restart).
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-99: Retained messages, delayed publish, shared subscriptions, auto-subscribe available

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX config (`mqtt.retainer`, `mqtt/delayed`, `shared_subscription`, `auto_subscribe`)
severity: low
reason: `mqtt.retainer {enable=true, backend=built_in_database}`, `mqtt/delayed {enable=true}`, `shared_subscription=true` (round_robin), `auto_subscribe` config present. Shared subscriptions (`$share/{group}/factory/+/+/telemetry`) enable horizontal scaling of backend consumers — direct mitigation for the DW-91 single-consumer bottleneck.
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-100: NOT available on community edition — Flow Designer, Codec, Message Transformation REST API, standalone Webhooks API

origin: migrated from legacy ledger ("EMQX 6.2.2 feature deep-dive for backend offloading (2026-08-21)"), 2026-08-22
location: EMQX community edition
severity: low
reason: Flow Designer (`/api/v5/flows` 404), Codec (`/api/v5/codecs` 404), Message Transformation REST API (`/api/v5/transformations` 404), standalone Webhooks API (`/api/v5/webhooks` 404 — webhook is only a rule action). These appear to be enterprise-tier or config-only.
status: skipped 2026-08-23
resolution: skipped - informational capability observation, not a defect

### DW-101: Notification worker status is pod-local (per-JVM tracker)

origin: Deferred from: code review of spec-6-3-report-notification-worker-status (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationWorkerTracker.java:16
reason: In a multi-replica deployment the endpoint reports only the calling instance's poll liveness. Acceptable for single-instance; documented by design (in-memory, resets on restart). Distributed observability is out of current scope.
status: skipped 2026-08-23
resolution: skipped - documented by design (single-instance pilot)

### DW-102: WAHA circuit failureRate may serialize as non-finite JSON (NaN) and break actuator parse

origin: Deferred from: code review of spec-6-4-build-super-admin-health-dashboard (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java + syncro/apps/web/src/features/system-health/hooks/use-actuator-health-query.ts
reason: Resilience4j Metrics.getFailureRate() returns NaN until minimumNumberOfCalls is reached. If Spring's Jackson writes NaN unquoted, the entire /actuator/health JSON becomes invalid and the frontend hook throws, collapsing all five dependency cards. Frontend defensively treats per-parse failure as an error, but the whole-section collapse is undesirable. Needs verification against the running backend (Jackson QUOTE_NON_NUMERIC_NUMBERS default) and, if confirmed, a backend-side clamp/config plus a per-component-parse isolation on the frontend. Cross-stack, backend-owned; not fixable purely in story 6.4.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 3 — failureRate NaN/negative clamped to 0 before serialization (WahaCircuitBreakerHealthIndicator.java:41)

### DW-69: TelemetryIngestTracker monotonic guard can pin freshness LIVE through a backward clock step

origin: code review of spec-6-5-surface-latest-telemetry-freshness-in-health.md (2026-08-21)
location: TelemetryIngestTracker.java:30 (now.isAfter(lastAcceptedAt)) combined with TelemetryFreshnessService.java negative-elapsed LIVE branch
severity: medium
reason: TelemetryIngestTracker.recordAccepted() keeps lastAcceptedAt monotonic, so a backward NTP clock step leaves lastAcceptedAt in the "future" and freshness reports LIVE (clock-skew branch) until wall time catches up — masking a genuinely stalled ingest path. Fixing means either accepting an unconditional lastAcceptedAt = now (changes the shared tracker also consumed by the 6.4 ingest worker status) or surfacing skew as its own state — a shared-tracker decision, not this story's code.
source_spec: _bmad-output/implementation-artifacts/spec-6-5-surface-latest-telemetry-freshness-in-health.md
status: open

### DW-70: Stale-machine evidence cannot distinguish Redis read failure from never-received telemetry

origin: Deferred from: code review of spec-6-6-link-health-failures-to-operational-evidence (2026-08-21)
location: syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryStaleMachineService.java + LatestTelemetryQueryService.java
reason: LatestTelemetryQueryService returns null for read failure, empty Redis hash, and malformed receivedAt alike, so during a Redis outage every ACTIVE machine is reported as never-received OFFLINE evidence (whole fleet listed, "No telemetry received") — a definitive false state during exactly the incident the panel exists for. Fixing requires extending the shared read API (outcome-distinguishing wrapper) also consumed by MachineController hydration (6.4 surface) — a deliberate contract decision. Mitigation today: a Redis outage independently shows the Redis dependency card DOWN and degrades the overall banner.
status: open

### DW-71: Machine code is unique per plant only, so code-only deep links are ambiguous across plants

origin: Deferred from: code review of spec-6-6-link-health-failures-to-operational-evidence (2026-08-21)
location: syncro/apps/web/src/features/system-health/components/system-health-page.tsx (stale-machine list href) + syncro/apps/backend/src/main/java/com/syncro/machine/infrastructure/MachineRepository.java:69
reason: Uniqueness is (plant_id, lower(code)) while the machine hub resolves codes globally via findByCodeIgnoreCase; with the same code in two plants, both stale-machine rows link to one URL and the hub throws IncorrectResultSizeDataAccessException. Pre-existing hub-resolution limitation (route /dashboard/master-data/machines/[machineCode] predates 6-6 and breaks for cross-plant duplicates from any entry point). Fix needs a by-id or plant-scoped resolution decision; Phase-1 pilot is single-plant (GM1). 6-6 patch mitigated determinism only (machineId sort tie-break, encodeURIComponent).
status: open

### DW-72: Wall-clock jumps silently reset or mis-attribute windowed data-quality counts

origin: Deferred from: code review of spec-6-7-implement-data-quality-panel-and-latency-indicator (2026-08-22)
location: syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryDataQualityTracker.java (currentMinute()/increment())
reason: The minute-bucket ring is keyed on wall-clock epoch minutes. A backward NTP step re-tags buckets as time re-passes minutes (counts re-accumulate into fresh windows), and a forward jump larger than the ring leaves every bucket stale-tagged so all windowed counts read zero mid-run; the lock-free count() can also pair a reclaimed bucket's new count with the old minute across the four counters. Operator impact is a transient wrong panel during exactly the kind of host clock incident an NTP fix is. Fixing means a monotonic-minute guard (treat regression as full reset) plus a documented reset-on-jump policy — a cross-tracker decision (TelemetryIngestTracker has the mirror-image monotonic guard issue, DW-69). Mitigation: counts are observability-only, reset on restart by design, and the latency sample ages out with the window.
status: open

### DW-73: Pilot seed sparepart code/label cannot detect SparepartService algorithm drift

origin: Deferred from: code review of spec-7-1-create-canonical-pilot-seed-data (2026-08-22)
location: syncro/apps/backend/src/main/resources/db/seed/pilot-seed.sql + syncro/apps/backend/src/test/java/com/syncro/db/PilotSeedTest.java vs com.syncro.sparepart.application.SparepartService (bomPrefix/codePart/nextBomCode/sparepartLabel)
reason: The canonical sparepart code `BF-08410GM1ELEPLCWEC000` and label `Electric · PLC · Wecon · LX5` exist as duplicated literals in the seed SQL, the test constant, and the test assertion; nothing cross-checks them against the live SparepartService algorithm. If bomPrefix/codePart/sparepartLabel ever changes (e.g. dash stripping, separator change), PilotSeedTest stays green while the seed silently diverges from backend-generated codes — defeating AC 7.1-3's "backend-exact generated code". Cross-checking requires invoking SparepartService from the hermetic JDBC test, which needs the Spring context (or extracted static helpers) the test deliberately avoids; the algorithm is also indirectly pinned by SparepartServiceIntegrationTest for other machines. Fix needs a decision: extract pure code/label derivation into a testable component or accept literal pinning.
status: open

### DW-74: Fixture boundary math mirrors the evaluator; seed-linked constants are literals

origin: Deferred from: code review of spec-7-2-provide-pilot-mqtt-payload-fixtures (2026-08-22)
location: syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PilotMqttPayloadFixtureTest.java vs com.syncro.sparepart/application SparepartLifetimeEvaluator + SparepartAlertService + db/seed/pilot-seed.sql
reason: assertBoundaryMath delegates the counter delta to production CountingDeltaCalculator, but the percentage (x100, scale 2, HALF_UP) and the alert comparator semantics (fires at >=, SparepartAlertService.java:83) are a verified inline mirror because SparepartLifetimeEvaluator requires a MachineSparepartInstallationRepository and cannot run hermetically; BASELINE_COUNTER/EXPECTED_PRODUCTION_COUNT/THRESHOLD_PERCENTAGE are also literals copied from the pilot-seed.sql installation row. If the evaluator formula/rounding, the comparator direction, or the seed installation values (1000/0/90) change, PilotMqttPayloadFixtureTest stays green while the fixtures encode the wrong side of the real alert boundary. Fix requires a production refactor (extract a pure consumed-percentage function) or parsing the values from the seed file - both beyond an additive story scope. Same family as DW-73.
status: open

### DW-75: Validated backend binary not reproducible from committed repo (JwtTokenService untracked)

origin: Deferred from: code review of spec-7-4-validate-telemetry-before-threshold-does-not-create-alert (2026-08-22)
location: syncro/apps/backend/src/main/java/com/syncro/auth/application/JwtTokenService.java + syncro/.gitignore:14 (*token*)
reason: The live backend whose before-threshold behavior story 7-4 validates was compiled from a working tree containing JwtTokenService.java, but that file is untracked and gitignored by the `*token*` pattern, so a fresh checkout or bmad-loop worktree cannot compile the exact binary that produced the accepted-publish evidence. This is an evidence-integrity caveat on every live-stack story until fixed: validation evidence derives from a non-reproducible build. The residual-risk note in story 7-4 documents the defect honestly but does not surface the integrity caveat explicitly. Fix needs a decision: narrow the gitignore pattern (e.g. `*.token`, `secrets/*`, `*.env`) so the auth source file is tracked, or restructure auth to read secrets from a gitignored config/`application-local.yml`. Out of scope for a no-source-change validation story.
decision: 2026-08-23 Interactive resolve (bmad-loop-resolve method) — Option (a) chosen: narrowed both root and syncro gitignore token patterns to token FILES (*.token plus explicit infra/influxdb/admin-token); JwtTokenService verified secret-free (HMAC key injected via SyncroProperties.secret()) and now tracked.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 17 — JwtTokenService.java tracked; clean test-compile from tracked sources proven; admin-token file remains ignored via explicit path

### DW-76: Spec-documented admin credential stale vs live .env; secrets may drift from spec

origin: Deferred from: code review of spec-7-4-validate-telemetry-before-threshold-does-not-create-alert (2026-08-22)
location: syncro/apps/backend/src/main/resources/application*.yml / syncro/.env (SYNCRO_AUTH_LOCAL_ADMIN_PASSWORD) + spec story boundaries (UI access section)
reason: The story spec documents `admin@syncro.dev / syncro-admin-dev` as the UI access credential, but the live local stack runs a different password from `syncro/.env`, which is gitignored and version-controlled out of band. The dev recorded the deviation transparently (literal redacted per review finding) and the used credential is within the spec's allowed set, but spec text and live reality can silently drift for any future manual/UI verification, and (per DW-75 family) the spec text cannot be verified against source. Fix needs a decision: reconcile spec text with live defaults (documentation update) or make the seed/bootstrap own the credential deterministically. Deferred to the 7-7 documentation story; 7-4 itself will additionally redact the literal password per review patch.
status: open

### DW-103: No regression test protects V31 fix

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: audit-write path tests / ck_audit_log_entity_type migration
severity: high
reason: no test asserts the audit-write path for ALERT create/acknowledge/resolve or that ck_audit_log_entity_type includes 'ALERT'; the defect would silently regress. Needs follow-up story.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 4 — AuditLogWiringIntegrationTest proves recordSystem(ALERT) persists against the migrated DB and ck_audit_log_entity_type includes 'ALERT'

### DW-104: Constraint list duplicated in V16+V31

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: V16__*.sql, V31__*.sql
severity: medium
reason: a future migration recreating ck_audit_log_entity_type from V16's list (omitting 'ALERT') silently re-introduces the exact failure mode this story fixed.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 4 — DbIndexHygieneMigrationTest pins the exact 8-item allowed entity_type list from pg_constraint, so any drift fails CI

### DW-105: verify-pilot NOTIFICATION section has no FAIL path

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: verify-pilot script (NOTIFICATION section)
severity: medium
reason: job count/status/level/trace_id are print-only; "NOTIFICATION PASS" is cosmetic. "Exactly one job" rests on agent-run SQL. Pre-existing 7-3 tooling limitation.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 7 — NOTIFICATION verdicts enforce status-enum membership, TECHNICIAN-presence, and alert↔job trace equality

### DW-106: Alert status=OPEN not asserted by verify-pilot

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: verify-pilot script (ALERT section)
severity: medium
reason: ALERT section only FAILs when consumed_percentage_snapshot != 90.00. Pre-existing 7-3 tooling limitation.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 7 — status membership (OPEN/ACKNOWLEDGED) asserted with corruption FAIL; opt-in -ExpectAlertStatus pins the stage and warns when unevaluated

### DW-107: traceId cross-correlation human-read, not tool-enforced

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: verify-pilot script
severity: low
reason: verify-pilot never compares alert trace_id against job trace_id. Pre-existing 7-3 tooling limitation.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 7 — tool-enforced trace inheritance FAIL (mismatch and empty-trace cases); error_detail newline-flattened in SQL to keep row parsing sound

### DW-108: Job idempotency key not independently exercised; no unique index on idempotency_key

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: uq_notification_jobs_alert_level / idempotency_key
severity: medium
reason: duplicate-job prevention depends entirely on the alert dedup guard (uq_notification_jobs_alert_level on alert_id+escalation_level, not idempotency_key). Pre-existing system design.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 4 — V36 adds unique index uq_notification_jobs_idempotency_key with duplicate/distinct-key migration tests

### DW-109: V31 DROP CONSTRAINT without IF EXISTS

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: V31__*.sql
severity: low
reason: fails if constraint manually renamed/removed; editing applied migration would change Flyway checksum. Standard Flyway convention, low risk for sanctioned schemas.
status: skipped 2026-08-23
resolution: skipped - standard Flyway convention, low risk

### DW-110: Counter drift baseline not asserting counting=890

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: verify-pilot script (counter baseline)
severity: low
reason: before-threshold publish on drifted counter records wrap-around delta. 7-4 established 890 baseline; low risk.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 7 — non-canonical counter values WARN with drift guidance + -ExpectCounting pinning hint; counting>900 without an active alert now warns about missing threshold-crossing evidence

### DW-111: Quarantine matrix path not executable

origin: migrated from legacy ledger ("code review of spec-7-5-validate-threshold-alert-and-waha-notification-job (2026-08-22)"), 2026-08-22
location: verify-pilot script
severity: medium
reason: verify-pilot hard-FAILs on any telemetry_quarantine row, contradicting the spec's "document as pre-existing" path. Pre-existing script constraint.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 7 — -QuarantineWarnOnly switch downgrades quarantine findings to WARN; default FAIL behavior unchanged

### DW-112: WAHA disclaimer contradicts the transcribed 7-6 evidence — spec-level resolution needed

origin: migrated from legacy ledger ("code review of spec-7-7-document-pilot-validation-proof (2026-08-22)"), 2026-08-22
location: syncro/docs/pilot-validation.md §6/§9
severity: medium
reason: WAHA disclaimer (pilot-validation.md §6/§9) contradicts the transcribed 7-6 evidence ("message WAS delivered to WhatsApp before cancellation" for a placeholder number). The disclaimer is spec-mandated (DW-88 / don't-claim-delivery constraint), so the doc correctly implements the spec; the tension needs spec-level resolution, not a doc fix.
status: open

### DW-114: Existing integer-typed InfluxDB optional fields will conflict on first Double write after DW-28 deploy

origin: review of spec-deferred-work-bundle, 2026-08-22
location: InfluxTelemetryWriter.java, existing InfluxDB bucket
severity: medium
reason: Telemetry optional fields previously persisted as integer (Long) will now receive Double writes. InfluxDB rejects field-type changes within a measurement, so a bucket with `rpm=1200i` rejects the first `rpm=1200.0` point. The DW-28 decision explicitly changed the storage contract, but did not address existing data. Dev/CI buckets can be reset; production/preview needs a bucket rewrite or migration plan.
status: open

### DW-115: DW-28 Double coercion loses precision for integral optional fields above 2^53

origin: review of spec-deferred-work-bundle, 2026-08-22
location: InfluxTelemetryWriter.java:50-51
severity: low
reason: `node.doubleValue()` rounds integral values above 2^53. Telemetry `counting` is 16-bit (max 65535) and optional fields are typically small sensor measurements, so this is a data-fidelity protection for future large-counter optional fields. The DW-28 decision mandated Double coercion; this is a residual tradeoff note.
status: skipped 2026-08-23
resolution: skipped - residual documented tradeoff of DW-28 Double coercion

### DW-116: EMQX auth test uses heredoc entrypoint, bypassing compose ACL seeding and leaking file contents

origin: review of spec-deferred-work-bundle, 2026-08-22
location: MqttAuthEnforcementIntegrationTest.java
severity: low
reason: The Testcontainer overrides the EMQX entrypoint with a shell heredoc that writes emqx.conf + auth-bootstrap.csv into the container, skipping the repo's docker-entrypoint.sh (ACL seeding via management API). The conf and CSV contents appear in the container command line. Future hardening: mount via volume when Docker Desktop path handling permits. Also repo-relative paths (`../../infra/...`) are fragile across IDE/CI launchers.
status: skipped 2026-08-23
resolution: skipped - environment-blocked on Docker Desktop volume path handling

### DW-122: Alert 409 handler catches ObjectOptimisticLockingFailureException from any persistence op in the alert controller

origin: review of spec-deferred-work-bundle, 2026-08-22
location: SparepartAlertExceptionHandler.java:44-48
severity: low
reason: The alert handler maps any OOLFE under the alert controller to "Alert was modified concurrently." In practice, only the alert entity has @Version and is saved via entity manager in the acknowledge/resolve/override path; notification job bulk updates bypass @Version, and audit writes are append-only. The message is accurate for the dominant case. Theoretically scoped to the alert entity type only.
status: skipped 2026-08-23
resolution: skipped - theoretical scope note; handler message accurate for the dominant case

### DW-117: Orval client input pinned to mutable live endpoint

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-10.md`
summary: generate:api pulls from http://localhost:8080/v3/api-docs at regen time with no committed OpenAPI snapshot, so generated types are unreproducible from a fresh checkout and reflect whatever backend build happens to be running.
evidence: orval.config.ts:5 input URL + clean:true; bundle-10 regen produced +2503/-564 lines reflecting drift accumulated since the previous regen; only ALERT enum was pre-verified explicitly.
decision: 2026-08-23 Interactive resolve (bmad-loop-resolve method) — Option A chosen: committed openapi.json snapshot consumed by orval, with npm run generate:snapshot to refresh from the live backend when the contract changes intentionally.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 16 — scripts/fetch-openapi.mjs writes the snapshot (with ALERT regression guard), orval input switched to ./openapi.json; regen-from-snapshot verified zero diff on the generated tree

### DW-118: Reserved-name/dedupe matching is case-sensitive across the optional-field chain

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-11.md`
summary: RESERVED_OPTIONAL_FIELDS exact-matches while the pattern allows uppercase and TelemetryPayload.parse is case-sensitive too, so config "COUNTING" passes validation and persists optional.COUNTING shadowing base counting; case-differing duplicates likewise evade dedupe and over-count.
evidence: MachineService.java optionalFieldRejectionReason uses Set.contains on lowercase set; OPTIONAL_FIELD_PATTERN permits [A-Za-z0-9_]+; TelemetryPayload.java:133 parse is case-sensitive; persist writes "optional." + entry key verbatim.
decision: 2026-08-23 Interactive resolve (bmad-loop-resolve method) — Option A chosen: case-insensitive guards for reserved-name matching and duplicate detection (Locale.ROOT lowercase comparison); user casing preserved in storage; storage/parse layers untouched.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 15 — reserved check and dedupe now compare case-insensitively, closing the COUNTING-shadow and case-duplicate evasion holes; underscore rule already blocks InfluxDB system keys; tests pin case-insensitive rejection and first-casing collapse

### DW-119: Machine form has no dedicated slot for optionalTelemetryFields validation errors

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-11.md`
summary: Backend now emits fieldErrors.optionalTelemetryFields with precise reasons, but machine-management.tsx renders field slots only for plantId/machineGroupId/code/status, so config rejections surface only via generic toast/formError.
evidence: machine-management.tsx:215 stores response.fieldErrors into state; dialog body renders error paragraphs solely beside the four named inputs; no reference to optionalTelemetryFields anywhere in the component.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 13 — role=alert form-level slot renders fieldErrors.optionalTelemetryFields beside formError; note: machines feature has no dedicated component-test harness yet (sibling machine-groups has one), and no current UI path submits the field (governed by DW-32)

### DW-113: .gitignore negation re-includes the whole syncro/docs/screenshots/ subtree, not just pilot/

origin: migrated from legacy ledger ("code review of spec-7-7-document-pilot-validation-proof (2026-08-22)"), 2026-08-22
location: syncro/.gitignore (screenshots negation)
severity: low
reason: the `!pilot/` line is redundant given the parent re-include, so the whole `syncro/docs/screenshots/` subtree is re-included rather than just `pilot/`. User-approved change; tightening to pilot/-only scope is optional future work.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 12 — replaced blanket parent negation with scoped 4-rule set; check-ignore verified docs-non-pilot ignored / pilot tracked / foreign screenshots dirs ignored

### DW-120: Machine/MachineGroup search LIKE lacks ESCAPE declaration (same defect class as fixed audit actor filter)

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-12.md`
summary: AuditLogRepository actor filtering returned zero rows for logins containing _ or % because normalizeActor escapes LIKE wildcards but the JPQL lacked an escape declaration; MachineRepository.java:21,38 and MachineGroupRepository.java:17 use the same bare like :search with identically escaping services (MachineService.normalizeSearch, MachineGroupService) - machine/machine-group search by terms containing underscore likely matches zero rows today.
reason: MachineRepository.java:21,38 and MachineGroupRepository.java:17 use the same bare like :search with identically escaping services (MachineService.normalizeSearch, MachineGroupService) - machine/machine-group search by terms containing underscore likely matches zero rows today.
evidence: bundle-12 revival proved the audit variant empirically (yusuf_dev exact search returned 0 before adding escape '\'); SparepartRepository already declares escape '\\' making the three repositories inconsistent.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 13 — escape '\' added to all three predicates; tests pin underscore-literal match, %-no-widening, backslash term, and name-clause coverage in both integration suites

### DW-121: SparepartRepository.findCodesByPrefix - BOM prefix LIKE has no ESCAPE declaration

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-14.md`
summary: bomPrefix derives from machine.getCode() which legally contains _; the bare like concat(prefix,'%') let _ act as a single-char wildcard during BOM-series collision detection, inflating maxSeries and skipping sequence numbers for sibling machines differing only at an underscore position.
evidence: SparepartRepository findCodesByPrefix had no escape clause and no prefix escaping; MACHINE_CODE_PATTERN admits [._-]; bundle-12 proved the identical mechanism empirically on the audit actor filter.
decision: implemented directly by deferred-work bundle 14.
status: done 2026-08-23
resolution: resolved by deferred-work bundle 14 — prefix escaped (\ % _) before the query, escape '\' declared in JPQL, repository renamed to findCodesByEscapedPrefix with contract javadoc; red/green collision test (MCHAA vs MCH_A) pins the behavior

### DW-123: LIKE-escape chain duplicated across five call sites

source_spec: `_bmad-output/implementation-artifacts/spec-deferred-work-bundle-14.md`
summary: The identical three-replace escape chain (\ -> \\, % -> \%, _ -> \_) now exists in SparepartService.nextBomCode, MachineService.normalizeSearch, MachineGroupService.normalizeSearch, AuditLogService.normalizeActor, and SparepartService bomCodeForUpdate-adjacent paths - drift hazard if one copy is later fixed or reordered.
evidence: grep shows the same replace triple in four services; order (backslash first) is load-bearing and only documented at some sites.
status: open
- source_spec: spec-8-1-add-garage-object-storage-and-backend-integration.md
  summary: Add an automated Testcontainers-based Garage integration test proving real S3 semantics (path-style addressing, region/credential acceptance, HeadBucket) before or with Story 8.4's image API.
  evidence: Review found all 8-1 tests mock S3Client/S3Presigner; correct wiring is the story's core deliverable but is only verified by one-off manual live-stack checks, so a regression (e.g. dropping pathStyleAccessEnabled) would keep CI green until manual testing.
- source_spec: spec-8-1-add-garage-object-storage-and-backend-integration.md
  summary: Introduce a shared mechanism (test-scope config fragment or base support class) so full-context tests stop hand-pinning six env vars per external service (now GARAGE_* x6 on top of REDIS_/INFLUXDB_/MQTT/WAHA pins in 19 files).
  evidence: Review flagged the growing copy-paste burden; every new external service forces identical edits across all @SpringBootTest files and a missed file dies on confusing placeholder-resolution errors.

- source_spec: `spec-8-2-manage-sparepart-material-code-and-lead-time.md`
  summary: The generated API client directory (`apps/web/src/lib/api/generated/`) is gitignored while partially force-added, so every Orval regeneration silently orphans newly created model files from commits — a fresh checkout breaks tsc. Decide a durable policy (commit a force-add helper script, or keep a tracked manifest, or stop ignoring the dir) before the next regeneration.
  evidence: Review found 34 untracked+ignored generated model files referenced by tracked index.ts/syncro.ts (sparepartProcurementRequest.ts among them) after this story's regeneration.

- source_spec: '_bmad-output/implementation-artifacts/spec-8-3-manage-estimated-price-entries-with-currency-and-kurs.md'
  summary: SparepartLifetimeEvaluatorTest fails at baseline with 2 Mockito UnnecessaryStubbingException errors (strict stubs at lines 120/134/135), unrelated to 8-3; fix the stubs or mark strictness off.
  evidence: Full Sparepart* suite run during 8-3 showed 2 Errors; file is unmodified by 8-3, fails standalone, and has zero import overlap with price-entry code.

- source_spec: `_bmad-output/implementation-artifacts/spec-8-5-configure-shift-schedule-with-machine-override.md`
  summary: Frontend cannot know a user's job-scope level, so below-LEADER MANAGE users see enabled shift/procurement editors and only discover denial via the server's 403 JOB_SCOPE_REQUIRED on submit; expose effective job scope from an auth endpoint so all Epic-8 mutation screens can render true read-only state.
  evidence: Blind Hunter review of 8-5 (finding: AC 8.5-4 read-only-for-below-LEADER only achievable with role flag); same limitation shipped in stories 8-2/8-3/8-4 which gate on applicationRole + static LEADER hint; JobScopeService lives purely server-side.

- source_spec: `_bmad-output/implementation-artifacts/spec-8-5-configure-shift-schedule-with-machine-override.md`
  summary: Integration-test helpers latestAuditEntryFor/auditCount stream the whole audit_log table and order only by createdAt, making assertions O(entire-table) and nondeterministic when two audits share a timestamp; introduce a bounded/ordered query or sequence tiebreaker shared across suites.
  evidence: Edge-prone pattern cloned into ShiftConfigServiceIntegrationTest from SparepartImageServiceIntegrationTest; works at current table sizes but degrades as suites accumulate audits.

### DW-124: Projection cache eviction failure silently leaves the 8-7 alert evaluation reading a stale cached view

- source_spec: `_bmad-output/implementation-artifacts/spec-8-7-raise-procurement-risk-alert-within-lead-time-window.md`
  summary: TelemetryPersistenceService.persist evicts the per-machine projection cache before the 8-7 alert evaluation, but ProjectionRedisCache.evictMachine swallows Redis errors, so on eviction failure the subsequent cache.get returns the pre-message view and the alert is evaluated against stale telemetry with no staleness signal in the alert path.
  evidence: ProjectionRedisCache.evictMachine logs projection_cache_evict_failed and continues; 8-7's evaluateAndCreateProcurementRiskAlerts reads through the same cache with no freshness check; a successful stale read is not distinguishable from a fresh one.
  status: open

### DW-125: Teams machine-link picker hard-capped at limit 200

- source_spec: `_bmad-output/implementation-artifacts/spec-9-2-cross-plant-teams.md`
  summary: MachineManager fetches machines with `limit: 200` and derives both the linkable set and the plant filter from that single truncated page; fleets beyond 200 machines (or a plant whose machines fall outside page one) cannot be linked via UI and get no truncation indicator.
  evidence: team-management.tsx machineParams `{ plantId: ..., limit: 200 }` with plant options derived by iterating only the fetched items; proper fix is a server-side search/paginated picker shared across master-data dialogs.
  status: open

### DW-126: Optimistic-lock conflicts on teams surface as raw 500

- source_spec: `_bmad-output/implementation-artifacts/spec-9-2-cross-plant-teams.md`
  summary: TeamEntity carries `@Version`; concurrent PUT/DELETE of the same team raises ObjectOptimisticLockingFailureException which TeamExceptionHandler does not map, so lost-update races return 500 instead of 409/retry signal.
  evidence: TeamExceptionHandler maps domain exceptions only; sections/machines handlers share the same systemic gap — fix globally in one pass rather than per-module.
  status: open

### DW-127: Combined Maven verification command flaky under Testcontainers context caching

- source_spec: `_bmad-output/implementation-artifacts/spec-9-2-cross-plant-teams.md`
  summary: Running multiple AbstractPostgresIntegrationTest classes in one surefire JVM fails later classes with connection-refused because the shared static container restarts on a new mapped port while the cached Spring context keeps the first port; each class passes individually.
  evidence: Story 9-2 Debug Log; pre-existing quirk also affects 9-1 integration suites. Fix belongs to test infra (reuse-friendly container lifecycle or per-class datasource), not to story code.
  status: open

### DW-128: audit_log immutable-update trigger does not cover id and plant_id columns

- source_spec: `_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md`
  summary: V16's BEFORE UPDATE OF column list omitted id and plant_id; V44 faithfully recreated that list (plus decision_id), so UPDATE audit_log SET plant_id/id remains possible despite the immutability intent.
  evidence: V44__add_audit_decision_id.sql trigger column-for-column matches V16's original list; surfaced by Edge Case Hunter on 9-3. Fix = follow-up migration recreating the trigger to cover every column.
  status: open

### DW-129: Health/read posture under healthy OPA is inverted once enforced-paths include public probes

- source_spec: `_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md`
  summary: Rego grants only SUPER_ADMIN, so anonymous health probes on enforced paths get 403 while OPA is healthy and 200 only when it is down — the degraded-allowlist alone cannot express "always-public".
  evidence: Blind Hunter + Edge Case Hunter convergence on 9-3; harmless today (empty enforced-paths) but 9.5 must add an always-public allowance (rego rule or bypass list distinct from degraded-allowlist) before populating enforcement.
  status: open

### DW-130: Sparse traffic during an OPA hang pays full client timeout per request

- source_spec: `_bmad-output/implementation-artifacts/spec-9-3-opa-infrastructure.md`
  summary: With minimum-number-of-calls=5, low-volume periods never trip the circuit breaker, so a hung (not refusing) OPA yields sustained 5s stalls per authz call until volume accumulates.
  evidence: OpaClient mirrors WahaClient's R4j tuning (house pattern); consider lower min-calls or a failure-rate-based timeout budget for the opa breaker specifically when enforcement goes live in 9.5.
  status: open

### DW-131: WAHA template mutation gate follows uniform SUPER_ADMIN || MANAGER_MAINTENANCE allow-list instead of legacy deny-list

- source_spec: `_bmad-output/implementation-artifacts/spec-9-4-role-taxonomy-migration.md`
  summary: The legacy gate (`if (role == VIEWER) throw`) only worked because non-VIEWER roles did not exist; mapping it literally to `!= AUDITOR` after the taxonomy lands would silently grant template mutation to the seven new identity roles (TECHNICIAN, STAFF_MAINTENANCE, SECTION_LEADER, MAINTENANCE_LEADER, INVENTORY_MAINTENANCE, STOREKEEPER, PRODUCTION_LEADER). Review converged (Blind Hunter R3 + Edge Case Hunter R6) on the fail-closed uniform allow-list; consequence: pilot technician.gm1 gets 403 on WAHA template PUT, as intended. Revisit only if a future story deliberately grants template management to an identity role.
  evidence: spec-9-4 Review Triage Log R3/R6; WahaTemplateServiceTest confirms AUDITOR-403 and MANAGER_MAINTENANCE-200.
  status: resolved

### DW-132: Decision-log persistence does a synchronous DB commit per enforcement decision

- source_spec: `_bmad-output/implementation-artifacts/spec-9-5-opa-enforcement-on-maintenance-endpoints.md`
  summary: Every PolicyDecisionPoint.evaluate() writes one authz_decisions row on the hot request path (its own transaction per record), doubling DB round-trips per enforced request and making a slow DB able to trip the lowered OPA breaker.
  evidence: DecisionLogService.record() → repository.save() (SimpleJpaRepository, one tx each), called synchronously from PDP.evaluate; surfaced by Blind Hunter on 9-5. Fix = async/queue-backed batch write or a write-behind buffer, revisit when enforcement traffic is measured.
  status: open

### DW-133: Decision-log tab renders an empty surface on small screens (no mobile card variant)

- source_spec: `_bmad-output/implementation-artifacts/spec-9-5-opa-enforcement-on-maintenance-endpoints.md`
  summary: decision-log-tab.tsx wraps its table in `hidden md:block`; on mobile only the pagination renders, unlike the audit-log page which has both desktop table and card variants.
  evidence: Edge Case Hunter on 9-5. Fix = add a card-based mobile layout mirroring audit-log-table.tsx.
  status: open

### DW-134: Workorder month prefix derives from UTC clock, not the plant-local timezone

- source_spec: `_bmad-output/implementation-artifacts/spec-10-1-workorder-schema-and-categories.md`
  summary: WorkOrderIdGenerator derives WO-YYMM from Clock.systemUTC() (TimeConfig), so at a UTC+7 plant workorders created 00:00-07:00 local on the 1st carry the previous month's prefix.
  evidence: Blind Hunter on 10-1; ProjectionProperties already models plantTimezone, so the UTC derivation is inconsistent. Fix = derive prefix from YearMonth.now(clock.withZone(plantZoneId)) or document UTC semantics.
  status: open

### DW-135: WorkorderIdExhaustedException has no @ExceptionHandler mapping until story 10-2

- source_spec: `_bmad-output/implementation-artifacts/spec-10-1-workorder-schema-and-categories.md`
  summary: nextId() throwing WorkorderIdExhaustedException surfaces as a generic Spring 500; no endpoint can trigger it yet (generator only exercised in tests).
  evidence: Edge Case Hunter on 10-1. Fix = add an @ExceptionHandler(WorkorderIdExhaustedException.class) returning 503 + code when 10-2 introduces the create-workorder endpoint.
  status: open

### DW-136: New workorder lock finders are only exercised through Mockito stubs

- source_spec: `_bmad-output/implementation-artifacts/spec-10-3-status-lifecycle-and-on-procurement.md`
  summary: WorkOrderRepository.findByParentIdForUpdate / findByIdForUpdate (@Lock PESSIMISTIC_WRITE) have no Testcontainers-level test asserting real FOR UPDATE SQL or lock behavior.
  evidence: Blind Hunter on 10-3; 10-2 set the precedent of a migration test class, but DW-127 (multi-integration-class quirk) kept this story to mock-based tests. Fix = one integration test loading both finders against PostgreSQL when the DW-127 workaround is revisited.
  status: open

### DW-137: Malformed JWT subject can 500 executor-gated mutations instead of 401/403

- source_spec: `_bmad-output/implementation-artifacts/spec-10-3-status-lifecycle-and-on-procurement.md`
  summary: WorkOrderService.isExecutor calls UUID.fromString(user.id()) which throws IllegalArgumentException on a non-UUID subject; transition() is the first executor-gated endpoint so the surface widened.
  evidence: Blind Hunter on 10-3; same pattern exists in create/assign since 10-2. Fix = parse defensively once at the auth boundary (or map IllegalArgumentException to 401) across all three methods together.
  status: open

### DW-138: Derived ON_PROCUREMENT enter-branch has no coverage against the shipped port bean

- source_spec: `_bmad-output/implementation-artifacts/spec-10-3-status-lifecycle-and-on-procurement.md`
  summary: recomputeProcurementState enter-to-ON_PROCUREMENT is unit-tested only via a fake SparepartRequestReadinessPort; the shipped NoopSparepartRequestReadinessPort makes derivation a structural no-op until Epic 12 swaps the bean.
  evidence: Blind Hunter on 10-3. Fix = when Epic 12 implements the port, add an application test wiring the real adapter plus its first request-transition event into recomputeProcurementState.
  status: open

### DW-139: V49 btree_gist extension needs superuser in managed PostgreSQL

- source_spec: `_bmad-output/implementation-artifacts/spec-10-4-repair-sessions-and-mttr.md`
  summary: V49 runs CREATE EXTENSION IF NOT EXISTS btree_gist (first extension install in the migration chain) for the gist EXCLUDE overlap constraint; local postgres:17-alpine applies it (migration test passes), but managed PostgreSQL (RDS/Cloud SQL/Supabase) requires a superuser pre-deployment grant before Flyway runs.
  evidence: Blind Hunter on 10-4. Fix = document the extension prerequisite in the deploy runbook, or split the extension creation into a manual pre-deployment step.
  status: open

### DW-140: WorkOrderEvidenceService has no Garage integration test

- source_spec: `_bmad-output/implementation-artifacts/spec-10-5-evidence-and-technical-drawings.md`
  summary: Evidence CRUD is covered by Mockito unit tests (WorkOrderEvidenceServiceTest) and a schema migration test, but no Spring slice/Testcontainers test exercises the real ObjectStorageService + PostgreSQL interaction (key format, content-type handling, FK behavior under rollback, store-succeeds-save-fails orphan window) — 8-4's SparepartImageServiceIntegrationTest is the precedent.
  evidence: Blind Hunter on 10-5. Fix = add WorkOrderEvidenceServiceIntegrationTest mirroring SparepartImageServiceIntegrationTest when evidence flows are exercised end-to-end.
  status: open
