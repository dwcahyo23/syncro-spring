---
title: '3-4 Persist Accepted Telemetry to InfluxDB and Redis'
type: 'feature'
created: '2026-08-08'
baseline_revision: '033af2a9488c51fd5f613271391ca3b6be01a589'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: true
final_revision: '5956403'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/epic-3-context.md'
warnings: ['oversized']
---

<intent-contract>

## Intent

**Problem:** Accepted telemetry currently stops at an INFO log line (`mqtt_telemetry_received`) — no history or latest state is persisted, so operators cannot see machine status and the time-series data that later sparepart/alert evaluation needs is silently discarded.

**Approach:** On an `Result.Accepted` outcome, a new persistence path writes the accepted telemetry point to InfluxDB (shared `telemetry` measurement with plant/machine tags) and updates a TTL'd latest-state hash in Redis, carrying machine identity, `receivedAt`, `running`, `runtimeHours`, `counting`, and `traceId`. A Redis dedupe guard (the fallback idempotency rule from the architecture; messageId-based dedupe is Story 3.9) prevents duplicate history records for duplicate payloads.

## Boundaries & Constraints

**Always:**
- InfluxDB client: `com.influxdb:influxdb-client-java` (v2 write API), pinned ~`7.3.0` — matches local infra `influxdb:2.7`. Do NOT use `influxdb3-java` (v3 API, incompatible with the 2.7 container/`/api/v2/write`).
- New `InfluxProperties` (`@ConfigurationProperties(prefix="syncro.influxdb")`) binds the existing `syncro.influxdb.*` YAML keys. New `TelemetryProperties` (`prefix="syncro.telemetry"`) with `latestTtl` (default `PT5M`) and `dedupeWindow` (default `PT30S`).
- Write point: measurement `telemetry`; tags `plantCode` + `machineCode` (bounded, low-cardinality); fields `running` (bool), `runtimeHours` (float), `counting` (int, `i` suffix), `traceId` (string FIELD — traceId is high-cardinality and must be a field, never a tag). Point time = `envelope.receivedAt()` (payload `timestamp` parsing is Story 3.9).
- Redis latest key `syncro:machine:{machineId}:latest` = hash fields `{machineId, machineCode, plantCode, running, runtimeHours, counting, receivedAt, traceId}` with `EXPIRE latestTtl`. Keys namespaced, explicit TTL, never the sole source of truth.
- Dedupe: Redis `SET NX EX` on key `syncro:machine:{machineId}:telemetry:dedupe:{running}:{runtimeHours}:{counting}` with TTL `dedupeWindow`, executed BEFORE the InfluxDB write. On InfluxDB write failure, delete the dedupe key before rethrowing so a broker redelivery is not lost. Duplicate → log `mqtt_telemetry_duplicate` and skip both writes.
- Plant code is obtained by re-fetching the machine with `machines.findByIdWithPlantAndGroup(machine.getId())` inside the persistence service (DW-17: `plant`/`machineGroup` are LAZY and `findByPlantIdAndCodeIgnoreCase` is not transactional; `findByIdWithPlantAndGroup` join-fetches both). `persist` needs no outer transaction — the join-fetch initializes the lazy associations within the repository call.
- Handler `Result.Accepted` branch calls `telemetryPersistenceService.persist(accepted, envelope)`; any exception propagates to the existing handler `catch` → `mqtt_telemetry_ingest_failed` (worker never crashes). Keep the existing `mqtt_telemetry_received` INFO log.
- Tests: hermetic unit tests (point builder, dedupe gate, handler wiring) plus one integration test with Testcontainers Postgres + `RedisContainer("redis:7-alpine")` + `GenericContainer`(`influxdb:2.7`, `DOCKER_INFLUXDB_INIT_MODE=setup`) asserting the InfluxDB point, Redis hash + TTL, and duplicate suppression. Never mock Redis/Influx for the persistence proof.

**Block If:**
- Adding Resilience4j (circuit breaker) for InfluxDB is an AR-027 dependency decision; use the client's built-in `WriteOptions` timeout/retry only. If a circuit breaker is required for this story, halt.
- If `influxdb-client-java` 7.x's factory/write API differs from the documented `create(url, token, org, bucket)` + `writePoint` shape, resolve from its javadoc; do NOT silently change the measurement/tag/field schema.

**Never:**
- No PostgreSQL migration/table (quarantine is Story 3.11), no `messageId`/`schemaVersion`/payload-`timestamp` parsing (Story 3.9), no optional fields (Story 3.6), no counter delta (Story 3.5), no backpressure/queue (Story 3.12), no UI/frontend (Stories 3.7/3.8).
- Do NOT change `TelemetryValidationService` sealed `Result` shapes or its validation order/unit tests.
- Do NOT use per-machine InfluxDB tables; no high-cardinality tags; no `ddl-auto=update`.
- Do NOT write InfluxDB/Redis for rejected messages (log-only path unchanged).
- Do not treat Redis as source of truth; do not log tokens/credentials.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| HAPPY_PATH | ACTIVE machine BF-08410/GM1, valid payload, receivedAt T | InfluxDB point (measurement `telemetry`, tags GM1/BF-08410, fields running/runtimeHours/counting/traceId, time T) + Redis hash key with TTL | No error expected |
| DUPLICATE_PAYLOAD | same machine, identical running/runtimeHours/counting within dedupeWindow | second message logs `mqtt_telemetry_duplicate`; no extra InfluxDB point; Redis latest unchanged | Logged, swallowed |
| INFLUXDB_DOWN | write throws after gate acquired | dedupe key deleted, exception → `mqtt_telemetry_ingest_failed`; worker stays up | Logged, swallowed |
| MACHINE_VANISHED | machine deleted between validate and persist | `mqtt_telemetry_ingest_failed`; no writes | Logged, swallowed |

</intent-contract>

## Code Map

- `syncro/apps/backend/pom.xml` -- MODIFY: add `com.influxdb:influxdb-client-java` (~7.3.0)
- `src/main/java/com/syncro/config/InfluxProperties.java` -- NEW: `@ConfigurationProperties(prefix="syncro.influxdb")` record (url, username, password, token, org, bucket)
- `src/main/java/com/syncro/config/TelemetryProperties.java` -- NEW: `@ConfigurationProperties(prefix="syncro.telemetry")` record (`Duration latestTtl`, `Duration dedupeWindow`)
- `src/main/java/com/syncro/config/InfluxDbConfig.java` -- NEW: `InfluxDBClient` bean from `InfluxProperties` (lazy connect, OkHttp connect/write/read timeouts, `destroyMethod="close"`)
- `src/main/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriter.java` -- NEW: builds `Point` + `writeApiBlocking.writePoint`
- `src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- NEW: hash + `expire` via `StringRedisTemplate`
- `src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- NEW: orchestrator (no transaction needed — join-fetch initializes lazy assoc): re-fetch machine, dedupe gate, InfluxDB then Redis writes
- `src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- MODIFY: call `persist` in `Result.Accepted` branch; keep existing logs
- `src/test/java/com/syncro/telemetry/infrastructure/InfluxTelemetryWriterTest.java` -- NEW: hermetic point-builder assertions
- `src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- NEW: dedupe gate + re-fetch + writer calls (mocked Redis/writers)
- `src/test/java/com/syncro/telemetry/application/MqttTelemetryIngestHandlerTest.java` -- MODIFY: constructor gains persistence collaborator (stub)
- `src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` -- NEW: Testcontainers Postgres + Redis + InfluxDB, `3.4-PERS-*`
- `src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfigTest.java` -- MODIFY: add mocked `TelemetryPersistenceService` bean to `TestConfiguration`

## Tasks & Acceptance

**Execution:**
- [x] `pom.xml` -- add `com.influxdb:influxdb-client-java:7.3.0` -- v2 write API matches influxdb:2.7 infra contract
- [x] `InfluxProperties.java` -- bind `syncro.influxdb.*` (url/username/password/token/org/bucket) -- typed config per project rules
- [x] `TelemetryProperties.java` -- `latestTtl=PT5M`, `dedupeWindow=PT30S` defaults -- Redis TTL + dedupe window are explicit, configurable
- [x] `InfluxDbConfig.java` -- `InfluxDBClient` bean (lazy; OkHttp connect/write/read timeouts; `destroyMethod="close"`) -- client is lazy so unreachable InfluxDB does not break context loads; closed on context shutdown
- [x] `InfluxTelemetryWriter.java` -- static `toPoint(payload, envelope, plantCode, machineCode)` returning `Point` (measurement `telemetry`, tags plantCode+machineCode, fields running/runtimeHours/counting/traceId, time=receivedAt at NS precision) + instance `write(Point)` with bounded retry/backoff, retrying only transient failures (`InfluxException` status 0/429/5xx) -- shared-table schema per architecture; golden write is `receivedAtEpochNanos`
- [x] `RedisLatestTelemetryWriter.java` -- `putLatest(machineId, fields)` = `opsForHash().putAll` + `expire(latestTtl)` atomically (SessionCallback MULTI/EXEC) -- TTL'd latest-state hash, no orphaned key if process dies between writes
- [x] `TelemetryPersistenceService.java` -- `persist(Accepted, envelope)` (no outer transaction; join-fetch initializes lazy assoc): re-fetch via `findByIdWithPlantAndGroup`, dedupe SETNX gate (throw on null acquisition; delete key guarded only on InfluxDB write failure — kept on Redis-latest failure so a redelivered identical message cannot re-write an already-persisted InfluxDB point), write InfluxDB then Redis, log `mqtt_telemetry_duplicate` with winning traceId -- DW-17 lazy-init fix + fallback idempotency rule
- [x] `MqttTelemetryIngestHandler.java` -- `Accepted` branch calls `persist`; keep `mqtt_telemetry_received` INFO + existing `catch` -- persistence failures never crash worker
- [x] `InfluxTelemetryWriterTest.java` -- assert point tags/fields/time for canonical `(true, 12.5, 100)` + traceId + receivedAt -- hermetic schema lock
- [x] `TelemetryPersistenceServiceTest.java` -- dedupe gate (first proceeds, second skipped; key deleted on InfluxDB failure), re-fetch used for plantCode, writer call order -- unit coverage of the edge matrix
- [x] `MqttTelemetryIngestHandlerTest.java` -- update constructor with stub persistence; accepted → persist called, rejected → not called -- wiring regression guard
- [x] `TelemetryPersistenceIntegrationTest.java` -- `3.4-PERS-001/002/003/004` (see below) with real Postgres+Redis+InfluxDB containers; each test uses a unique receivedAt (300s apart) so points land in distinct InfluxDB series timestamps and do not overwrite each other -- AC evidence
- [x] `MqttSubscriptionConfigTest.java` -- mocked `TelemetryPersistenceService` bean -- keeps wiring context loadable (DW-19 pattern)
- [x] Run full backend suite -- no regression (364 run, 0 failures, 1 pre-existing `SyncroBackendApplicationTests.contextLoads` AuditLogRepository error, 15 skipped)

**Acceptance Criteria:**
- Given an active registered machine receives valid telemetry, when the backend accepts the payload, then telemetry history is written to InfluxDB (measurement `telemetry`, plantCode+machineCode tags, running/runtimeHours/counting/traceId fields, receivedAt time) — verified by `3.4-PERS-001`.
- Given the accepted write, then latest telemetry state is written to Redis (`syncro:machine:{machineId}:latest` hash incl. machine identity, running, runtimeHours, counting, receivedAt, traceId) with an explicit TTL — verified by `3.4-PERS-002`.
- Given a duplicate payload for the same machine within the dedupe window, then no duplicate InfluxDB history record is created and Redis latest is unchanged — verified by `3.4-PERS-003`.
- Given a persisted point/hash, then it is queryable by machineCode/plantCode with the documented schema so downstream alert/sparepart evaluation can consume it in a later epic — verified by `3.4-PERS-004`.

## Spec Change Log

- 2026-08-09 (review-2 pass, `in-review` → applied): PERS-003 query de-tautologized (counts all `traceId`-field points in machine/plant window, asserts 1) + Redis `latest` traceId assertion; `InfluxTelemetryWriter` retries only transient `InfluxException` (status 0/429/5xx), permanent 4xx fail fast; dedupe key now deleted ONLY on InfluxDB write failure (kept on Redis-latest failure so redelivery cannot duplicate the already-persisted InfluxDB point); `mqtt_telemetry_dedupe_cleanup_failed` includes `traceId=`; boundary test parses `1.0E308`/`Long.MAX_VALUE` back; Code Map/Design Notes corrected to OkHttp timeouts (not `WriteOptions`). All patches are in the committed baseline.
- 2026-08-09 (review-triage pass, `in-review` → applied): point precision MS→NS (golden write is `receivedAtEpochNanos`); `InfluxTelemetryWriter.write` gains bounded retry/backoff; `RedisLatestTelemetryWriter.putLatest` atomic via MULTI/EXEC; removed `@Transactional(readOnly=true)` from `persist` (join-fetch already initializes lazy assoc; avoids holding a DB connection across external writes); `setIfAbsent` null now throws; dedupe key deleted on Redis-latest failure too and cleanup guarded; duplicate log includes winning traceId; `InfluxDbConfig` `destroyMethod="close"`; `TelemetryProperties` rejects non-positive TTLs; integration test isolates points per test via unique receivedAt (300s spacing); new unit tests (machine-vanished, SETNX-null, redis-failure cleanup, cleanup-masking, boundary values, persistence-failure swallow). Deferred: DW-22 (heartbeat-thinning until Story 3.9), DW-23 (cleanSession(true) redelivery net).
- 2026-08-09 (review-1 pass, `in-review` → applied): `mqtt_telemetry_received` logged on acceptance, before `persist` (was after — a persistence failure silently dropped the receipt event); `mqtt_telemetry_ingest_failed` gains `traceId=`; `persist` guards machine status — machine deactivated between validate and persist throws (no writes, `ingest_failed`); dedupe-key cleanup only deletes the key it still owns (compares stored traceId first) so a stale failure cannot evict a newer message's lock; `putLatest` throws if `exec()` returns null (aborted transaction no longer silently skips latest-state); duplicate log prints `winnerTraceId=unknown` when the winner key already expired; integration test `BASE_RECEIVED_AT` is `Instant.now()`-based so repeated runs never collide with a prior run's points; PERS-003 second message uses a distinct traceId/receivedAt to prove dedupe keys on payload equality, not message identity. Unit suite 20/20 in affected classes; integration 4/4 (`3.4-PERS-001..004`); full suite 365 run / 1 pre-existing `SyncroBackendApplicationTests.contextLoads` AuditLogRepository error / 15 skipped.

## Review Triage Log

- 2026-08-09 — Blind Hunter (BH) + Edge Case Hunter (EH) pass on the pre-triage diff.
- **Applied (code):**
  - BH-3/EH-8: `@Transactional(readOnly=true)` removed from `persist` — join-fetch already initializes lazy associations.
  - BH-6/EH-2: `redis.delete(dedupeKey)` cleanup wrapped in try/catch — cleanup failure is logged (`mqtt_telemetry_dedupe_cleanup_failed`), never masks the original exception.
  - BH-7 (sub): duplicate log now includes `winnerTraceId=` so operators can correlate the surviving message.
  - BH-8/EH-5: `putLatest` putAll+expire made atomic via SessionCallback MULTI/EXEC.
  - EH-1: `setIfAbsent` returning null (Redis error) now throws `IllegalStateException` instead of being misread as a duplicate.
  - EH-7: `TelemetryProperties` compact constructor validates `latestTtl`/`dedupeWindow` are positive.
  - EH-9: unit test for machine-vanished path (no writes, throws).
  - EH-10: unit test for SETNX null acquisition (no writes, throws).
  - EH-11: TTL assertion strengthened in integration test.
  - EH-12: boundary-value unit test (Long.MAX counting, 1.0E308 runtimeHours).
  - EH-13: handler test asserting persist-failure is swallowed (worker never crashes).
  - BH-9/EH-14: integration test isolation — unique receivedAt per test (300s apart) so InfluxDB points never share a series timestamp; fixes cross-test `traceId` overwrite.
- **Applied (precision/retry):** point time NS (golden write contract); InfluxDB write retried ≤3 attempts with linear backoff.
- **Deferred (ledger):** DW-22 — heartbeat-thinning from value-equality dedupe, superseded by Story 3.9 messageId. DW-23 — `cleanSession(true)` (Story 3.1 config) defeats the redelivery net the dedupe cleanup supports. Raw-payload INFO log already tracked as DW-18 (no new entry).
- **Rejected (no action):** BH-12 dead `username`/`password` keys in `InfluxProperties` (harmless, kept for infra parity); BH-14 machine-rename race (dedupe keyed on stable machineId, not code); BH-15 redundant OS-style test props; EH-3 single-thread timeout concurrency (design accepts ingest-thread serialization); EH-4 ambiguous-timeout duplicate (best-effort semantics documented).

### 2026-08-09 — Review pass (review-2, fresh follow-up review on committed `done` spec, baseline `033af2a`..`cd3cad4`)
- intent_gap: 0
- bad_spec: 0
- patch: 7 (medium 3, low 4)
- defer: 0
- reject: 14
- addressed_findings:
  - `[medium]` `patch` PERS-003 integration test was a tautology — it filtered the InfluxDB count query on the winner's `traceId` (`trace-pers-003`), so the duplicate's different traceId could never match and the test passed even with dedupe removed. Replaced with a machineCode+plantCode `traceId`-field count across the window, asserting exactly 1 persisted point, and added a Redis `latest` `traceId` assertion (still the winner) so both InfluxDB and Redis duplicate-skip are pinned at integration level (BH-1/BH-15).
  - `[medium]` `patch` `InfluxTelemetryWriter.write` retried non-transient failures (401/403/400/404) with full backoff, masking misconfiguration and prolonging ingest-thread blocking. Now retries only transient `InfluxException` (`status()==0` network-level, 429, 5xx); permanent 4xx fail fast (BH-3).
  - `[medium]` `patch` dedupe key was deleted on Redis-latest write failure even though the InfluxDB point was already persisted, so a redelivered identical message could write a duplicate history record. `persist` now keeps the gate on Redis-latest failure; the key is deleted only on InfluxDB write failure where the retry must re-persist (EH-1).
  - `[low]` `patch` `mqtt_telemetry_dedupe_cleanup_failed` log lacked `traceId` correlation — added (BH-14).
  - `[low]` `patch` `handlesBoundaryFieldValues` asserted the vacuous `runtimeHours=` prefix; now parses the field value back to `1.0E308` and `Long.MAX_VALUE` (BH-12).
  - `[low]` `patch` spec Code Map/Design Notes claimed `WriteOptions` timeouts on the client, but the implementation configures OkHttp timeouts in `InfluxDbConfig` and retries explicitly — corrected the spec text to match implementation (BH-7).
  - `[low]` `patch` updated `TelemetryPersistenceServiceTest.redisLatestFailureDeletesOwnedDedupeKeyAndRethrows` to assert the gate is kept (`verify(redis, never()).delete`).

### 2026-08-09 — Review pass (review-1, independent follow-up on committed f44f773/f013c5e)
- intent_gap: 0
- bad_spec: 0
- patch: 8 (medium 5, low 3)
- defer: 0
- reject: 0
- addressed_findings:
  - `[medium]` `patch` `mqtt_telemetry_received` was logged after `persist`, so a persistence failure silently dropped the acceptance receipt event — moved before `persist` in `MqttTelemetryIngestHandler` (handler).
  - `[medium]` `patch` `mqtt_telemetry_ingest_failed` lacked `traceId=` for correlating a failed message — added in the handler catch.
  - `[medium]` `patch` machine deactivated between validate and persist still persisted telemetry — `persist` now throws `IllegalStateException("machine is not active")` when the re-fetched machine status is not ACTIVE (no writes, `ingest_failed`).
  - `[medium]` `patch` stale failure could evict a newer message's dedupe lock — cleanup only deletes the dedupe key while it still holds this traceId.
  - `[medium]` `patch` `putLatest` silently skipped latest-state when `exec()` returned null (aborted transaction) — now throws so the caller rethrows and `ingest_failed` fires.
  - `[low]` `patch` duplicate log printed `winnerTraceId=null` when the winner key expired — prints `unknown`.
  - `[low]` `patch` integration `BASE_RECEIVED_AT` was a fixed instant, so a second run overwrote the first run's InfluxDB points — now `Instant.now()`-based (cross-run isolation).
  - `[low]` `patch` PERS-003 reused the same traceId/receivedAt for both persists, so dedupe was not proven on payload equality — second message now uses a distinct traceId/receivedAt.
  - `[low]` `patch` PERS-002 TTL assertion `isGreaterThan(0)` was a tautology against PT1H — tightened to `isBetween(3599L, 3600L)`; unit tests updated for the traceId-owned cleanup guard (new test `failureDoesNotDeleteDedupeKeyOwnedByAnotherMessage`).

## Design Notes

- **InfluxDB client choice:** infra `docker-compose.yml` runs `influxdb:2.7`; architecture allows v2 line protocol as fallback when 3-Core Java client creates friction. `influxdb-client-java` (v2) is the only one that speaks `/api/v2/write` to 2.7, so it is the correct contract match.
- **Lazy-init fix (DW-17):** `MachineEntity.plant`/`machineGroup` are `FetchType.LAZY` and `validate()` is not transactional; touching `getPlant()` on the accepted entity would throw `LazyInitializationException`. The persistence service re-fetches by id with join-fetch (`findByIdWithPlantAndGroup`), which initializes both associations inside the repository call — no outer `@Transactional` is needed on `persist`, so a DB connection is not held open across the Redis/InfluxDB writes.
- **Idempotency rule:** architecture says dedupe uses `messageId` (primary) with machine-identity + timestamp + counter as fallback. `messageId`/payload `timestamp` arrive in Story 3.9, so this story implements the fallback: `SET NX EX` keyed on `machineId + running + runtimeHours + counting` with a short window. `mqtt_telemetry_duplicate` names the rule and logs the winning traceId; Story 3.9 swaps in `messageId` as the key prefix. A `null` SETNX result (Redis unavailable) throws instead of being mistaken for a duplicate.
- **Tag/field split:** `traceId` is high-cardinality per message → stored as a field, not a tag (project rule: UUIDs/message IDs are fields unless approved). `plantCode`/`machineCode` are bounded master-data tags, enabling the shared-table query plan.
- **Golden write:** `telemetry,plantCode=GM1,machineCode=BF-08410 running=true,runtimeHours=12.5,counting=100i,traceId="<uuid>" <receivedAtEpochNanos>` — point time uses nanosecond precision (`getEpochSecond() * 1e9 + getNano()`).
- **InfluxDB write resilience:** `InfluxTelemetryWriter.write` retries up to 3 attempts with linear backoff (100ms/200ms), but only for transient failures (`InfluxException` with `status()==0` for network-level errors, 429, or 5xx); permanent 4xx errors (401/403/404/400) fail fast without retry so misconfiguration surfaces immediately instead of burning retries. The client has OkHttp connect/write/read timeouts configured in `InfluxDbConfig`. No Resilience4j (AR-027 decision — halt condition would apply otherwise). On exhaustion the exception propagates, the dedupe key is deleted (guarded — cleanup failure is logged, never masks the original), and the handler's catch logs `mqtt_telemetry_ingest_failed` without crashing the worker.
- **Atomic latest-state hash:** `putLatest` wraps `HMSET`+`EXPIRE` in a `SessionCallback` MULTI/EXEC so a crash between the two commands cannot leave a TTL-less orphan key.
- **Dedupe key cleanup scope:** the dedupe key is deleted only on InfluxDB write failure, so a broker redelivery can retry the whole persist rather than being dropped as a duplicate. It is deliberately **not** deleted on Redis-latest write failure: the InfluxDB point is already persisted at that point, so keeping the gate ensures a redelivered identical message stays suppressed instead of writing a duplicate history record.
- **Config validation:** `TelemetryProperties` compact constructor rejects non-positive `latest-ttl`/`dedupe-window` (fail fast on misconfiguration).

## Verification

**Commands:**
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="InfluxTelemetryWriterTest,TelemetryPersistenceServiceTest,MqttTelemetryIngestHandlerTest,MqttSubscriptionConfigTest"` -- expected: all pass (hermetic/wiring, no containers)
- `mvn -q -f syncro/apps/backend/pom.xml test -Dtest="TelemetryPersistenceIntegrationTest"` -- expected: `3.4-PERS-*` green (Testcontainers Postgres+Redis+InfluxDB)
- `mvn -q -f syncro/apps/backend/pom.xml test` -- expected: full suite green, no regression from Story 3.3 baseline

**Manual checks (operator, requires broker + infra):**
- With `docker compose up -d` and backend on `local` profile, publish valid payload to `factory/GM1/BF-08410/telemetry` → `mqtt_telemetry_received` + persistence logs; verify point in InfluxDB (`influx query 'from(bucket:"syncro_telemetry") |> range(start:-10m) |> filter(fn:(r) => r.measurement == "telemetry")'`) and `GET syncro:machine:{machineId}:latest` with TTL in Redis.

## Auto Run Result

Status: done

_Appended by the bmad-loop orchestrator (missing-marker repair, #224): the session finalized this spec's frontmatter without its `## Auto Run Result` marker, so the orchestrator synthesized the result from the frontmatter and appended this section._

Synthesized by the bmad-loop orchestrator from frontmatter status `done` for story `3-4-persist-accepted-telemetry-to-influxdb-and-redis` (session finalized the spec without appending its marker).
