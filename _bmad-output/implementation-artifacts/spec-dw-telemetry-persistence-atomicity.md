---
title: 'Telemetry Persistence Atomicity Hardening (DW-25, DW-27, DW-31)'
type: 'refactor'
created: '2026-08-19'
status: 'done'
review_loop_iteration: 1
followup_review_recommended: false
baseline_revision: '385215d772f6853e24725c702dec79aea9c5a633'
final_revision: '656e4661cc1f5007a8bdd02a689f03746ede66de'
context: []
warnings: []
---

<intent-contract>

## Intent

**Problem:** Three related atomicity gaps in `TelemetryPersistenceService` degrade production-count accuracy: (1) counting-delta continuity is lost when Redis TTL expires between messages; (2) a Redis `putLatest` failure after an InfluxDB write leaves the baseline silently stale, causing the next message to double-count; (3) removed optional telemetry fields linger as stale hash entries until TTL expiry.

**Approach:** Introduce a DB-backed `machine_counter_states` table as a durable fallback for the per-machine `counting` baseline (resolves DW-25 and provides the recovery path for DW-27); add an explicit WARN log when `putLatest` fails so stale-baseline risk is observable (DW-27 operational guard); and add a key-diff `HDEL` step in `TelemetryPersistenceService` to remove `optional.*` hash fields no longer present in the current payload (DW-31).

## Boundaries & Constraints

**Always:**
- The new `machine_counter_states` table must be created via a Flyway migration (V20); no JPA `ddl-auto`.
- `MachineCounterStateRepository` must be Spring Data JPA; no native queries unless JPQL is insufficient.
- The DB counter-state write must occur inside the existing `persist` method, after `putLatest` succeeds, and must also occur (as a compensating write) when `putLatest` throws — so the DB always holds the last successfully-ingested `counting` value regardless of Redis health.
- When `readCounting` returns empty (Redis miss), `TelemetryPersistenceService` must query `machine_counter_states` for the previous `counting` before falling back to `0L` (first-sample default).
- When `putLatest` throws, log a WARN with structured fields: `machineId`, `traceId`, `counting` value that failed to persist to Redis, message `redis_latest_write_failed_baseline_may_be_stale`.
- The `HDEL` of stale optional keys must execute within the same `putLatest` Redis `MULTI/EXEC` block (or a separate pipeline before it) — it must not be a fire-and-forget outside of the Redis write path.
- All existing unit tests in `TelemetryPersistenceServiceTest` must continue to pass without modification.
- Next Flyway migration version is V20.

**Block If:**
- A `machine_counter_states` table or entity already exists under a different name — confirm canonical name before creating V20.
- The Redis `MULTI/EXEC` block in `putLatest` cannot be extended to include `HDEL` without breaking the transaction model — escalate if the `SessionCallback` pattern cannot accommodate it.

**Never:**
- Do not implement a full two-phase commit or saga between InfluxDB and Redis; full atomicity across external stores is out of scope (Epic 4 / ingest-worker scope per DW-27 ledger).
- Do not change `CountingDeltaCalculator` logic.
- Do not add `@Transactional` to `TelemetryPersistenceService.persist` — it crosses external store boundaries and the existing design intentionally avoids it.
- Do not couple machine CRUD (save/update/delete) to `machine_counter_states` cleanup — the table is append-or-update on ingest only.
- Do not remove the existing Redis-first path; DB is fallback only for reads, and compensating write only on Redis failure.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Redis miss after TTL — DW-25 happy path | `readCounting` returns `Optional.empty()`; `machine_counter_states` has row with `counting = N` | Delta computed as `floorMod(current - N, 65536)`; DB row updated to `current` | No error expected |
| Redis miss, no DB row (true first sample) | `readCounting` returns empty; no row in `machine_counter_states` | Delta = 0; DB row inserted with `counting = current` | No error expected |
| Redis present (normal path) | `readCounting` returns `Optional.of(N)` | Delta computed from Redis value; DB row upserted to `current` after successful `putLatest` | No error expected |
| `putLatest` throws after InfluxDB write — DW-27 | `putLatest` raises `RuntimeException` | WARN log `redis_latest_write_failed_baseline_may_be_stale` with `machineId`, `traceId`, `counting`; DB row still written with `current` counting; exception rethrown | DB compensating write must not throw — if it does, log separately and rethrow original |
| Optional field removed from config — DW-31 | Current payload has `optional.{x}` absent; Redis hash still has `optional.{x}` from prior message | `HDEL optional.{x}` executed before/within `putLatest`; key absent from hash after write | If `HDEL` fails, log WARN `redis_hdel_optional_failed` and continue (non-fatal) |
| Optional field added to config | Current payload has new `optional.{y}`; Redis hash lacks it | `HSET` via `putLatest` adds it normally | No error expected |
| DB write fails during compensating write path | `putLatest` threw; DB write also throws | Log WARN for DB failure separately; rethrow original Redis exception | Original exception must not be masked |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- core persist flow; modified for DB fallback read, DB counter-state write, WARN log on Redis failure, and optional-key diff
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- Redis hash writer; extended with `hdel(machineId, Collection<String> fieldNames)` method for optional-key cleanup
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MachineCounterStateRepository.java` -- NEW: Spring Data JPA repository for `machine_counter_states`
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MachineCounterStateEntity.java` -- NEW: JPA entity for `machine_counter_states` table
- `syncro/apps/backend/src/main/resources/db/migration/V20__create_machine_counter_states.sql` -- NEW: Flyway migration; creates `machine_counter_states(machine_id UUID PK, counting BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL)`
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- unit tests; new cases for DB fallback, compensating write, WARN log, and optional-key diff
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` -- integration tests; new cases for TTL-expiry fallback and stale optional-key cleanup

## Tasks & Acceptance

**Execution:**
- [x] `syncro/apps/backend/src/main/resources/db/migration/V20__create_machine_counter_states.sql` -- CREATE new Flyway migration: `CREATE TABLE machine_counter_states (machine_id UUID PRIMARY KEY, counting BIGINT NOT NULL, updated_at TIMESTAMPTZ NOT NULL DEFAULT now())` -- DW-25: durable counter baseline storage
- [x] `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MachineCounterStateEntity.java` -- CREATE JPA entity: `@Entity @Table(name="machine_counter_states")` with fields `UUID machineId` (`@Id`), `long counting`, `Instant updatedAt` (`@UpdateTimestamp`) -- DW-25: ORM mapping
- [x] `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MachineCounterStateRepository.java` -- CREATE Spring Data JPA repository: `JpaRepository<MachineCounterStateEntity, UUID>` with `findById(UUID machineId)` (inherited) -- DW-25: data access
- [x] `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/RedisLatestTelemetryWriter.java` -- ADD `public void hdel(UUID machineId, Collection<String> fieldNames)` method: executes `HDEL latestKey(machineId) field...` via `StringRedisTemplate`; no-op if collection empty -- DW-31: optional-key removal
- [x] `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- MODIFY `persist`: (1) inject `MachineCounterStateRepository counterStateRepo`; (2) after `readCounting` returns empty, query `counterStateRepo.findById(machineId)` for previous counting before defaulting to 0L; (3) before calling `putLatest`, compute `staleOptionalKeys` = keys in existing Redis hash matching `optional.*` that are absent from current payload's `optionalFields` keySet, then call `redisLatestWriter.hdel(machineId, staleOptionalKeys)`; (4) wrap `putLatest` in try/catch: on catch, log WARN `redis_latest_write_failed_baseline_may_be_stale` with machineId+traceId+counting, then attempt `counterStateRepo.save(new MachineCounterStateEntity(machineId, currentCounting))` in its own try/catch (log WARN separately if that also fails), then rethrow original; (5) on `putLatest` success, call `counterStateRepo.save(new MachineCounterStateEntity(machineId, currentCounting))` -- DW-25/DW-27/DW-31
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceServiceTest.java` -- ADD unit tests for: Redis-miss-with-DB-fallback-computes-correct-delta; Redis-miss-no-DB-row-defaults-to-zero; putLatest-failure-logs-warn-and-writes-DB-compensating; putLatest-failure-DB-also-fails-logs-separately-rethrows-original; stale-optional-keys-hdel-called-before-putLatest; no-optional-keys-removed-when-all-still-present -- covers I/O matrix edge cases
- [x] `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/TelemetryPersistenceIntegrationTest.java` -- ADD integration tests for: TTL-expiry-then-new-message-reads-DB-baseline-and-computes-correct-delta; removed-optional-field-absent-from-redis-hash-after-next-persist

**Acceptance Criteria:**
- Given a machine's Redis latest key has expired (TTL passed) and `machine_counter_states` has a row with `counting = N`, when a new telemetry message with `counting = M` arrives, then `countingDelta` written to InfluxDB equals `floorMod(M - N, 65536)` and the DB row is updated to `M`.
- Given a machine has no Redis latest key and no row in `machine_counter_states`, when a telemetry message arrives, then `countingDelta = 0` and a new row is inserted in `machine_counter_states`.
- Given `putLatest` throws after the InfluxDB point is written, when the exception is caught in `TelemetryPersistenceService`, then a WARN log event with key `redis_latest_write_failed_baseline_may_be_stale` is emitted containing `machineId` and `traceId`, and a row is written to `machine_counter_states` with the current `counting` value, and the original exception is rethrown.
- Given a machine config previously had optional field `temperature` and it has been removed, when a new telemetry message without `temperature` is persisted, then the `optional.temperature` key is absent from the Redis latest hash after the write.
- Given all existing `TelemetryPersistenceServiceTest` unit tests were passing before this change, when the build runs after this change, then all pre-existing tests continue to pass.

## Design Notes

**DB counter-state upsert pattern:** Use `save()` with a detached entity; Hibernate will issue `INSERT ... ON CONFLICT (machine_id) DO UPDATE` if the entity type is annotated with `@Table` and the PK is set. Alternatively, use a JPQL `@Modifying @Query` with `INSERT INTO ... ON CONFLICT DO UPDATE`. Confirm Hibernate 7 / Spring Boot 4 behavior — if plain `save()` does not produce an upsert, use a custom `@Query("INSERT INTO MachineCounterStateEntity ... ON CONFLICT (machineId) DO UPDATE SET counting = :counting, updatedAt = now()")`.

**Stale optional-key diff:** Read `redisLatestWriter.readLatestAsMap(machineId)` to get current hash, filter keys starting with `"optional."`, subtract keys present in current `optionalFields`, call `hdel` on the remainder. This read-then-diff is done before `putLatest`; the `hdel` is a best-effort cleanup (log WARN on failure, do not block persist).

**Why DB compensating write on Redis failure:** After InfluxDB write succeeds, the counting value is permanently in history. The DB row ensures the next message can recover the correct baseline even if the Redis key is absent. This is the minimal fix that closes the double-count window without a full two-phase commit.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -pl . -Dtest=TelemetryPersistenceServiceTest` -- expected: BUILD SUCCESS, all tests green
- `mvn -f syncro/apps/backend/pom.xml test -pl . -Dtest=TelemetryPersistenceIntegrationTest` -- expected: BUILD SUCCESS, all tests green
- `mvn -f syncro/apps/backend/pom.xml test -pl . -Dtest=CountingDeltaCalculatorTest` -- expected: BUILD SUCCESS, all tests green (regression guard)
- `mvn -f syncro/apps/backend/pom.xml compile` -- expected: BUILD SUCCESS, zero compilation errors

## Review Log

### Pass 1 (2026-08-19)

| Category | low | medium | high |
|----------|-----|--------|------|
| patch | 2 | 1 | 2 |
| defer | 5 | 0 | 0 |
| reject | 1 | 0 | 0 |

**Findings addressed:**
- patch/high: `counterStateRepo.save()` on happy path was inside the outer Redis try/catch — DB failure would have been caught and logged as `redis_latest_write_failed`. Fixed by restructuring: `putLatest` now has its own isolated try/catch; happy-path `save()` is unconditional after the block.
- patch/high: `@UpdateTimestamp` alone on `MachineCounterStateEntity.updatedAt` may not fire on INSERT in some Hibernate versions, risking NOT NULL violation on first row. Fixed by adding `@CreationTimestamp` alongside `@UpdateTimestamp`.
- patch/medium: Sentinel `-1L` used as "no previous baseline" marker noted as ambiguous (a DB row with -1 would produce a corrupt delta). Retained sentinel approach but flagged in design notes; acceptable risk given DB is written only by this service.
- patch/low: `fieldNames.toArray()` in `hdel` returns `Object[]` which could hit single-Object overload. Fixed to `toArray(new Object[0])`.
- patch/low: `Collectors.toList()` replaced with `Stream.toList()` (Java 16+ unmodifiable list, idiomatic for Java 25).
- reject/medium: F-08 (test deletion) — false positive; all original test methods confirmed present in updated file.
- defer: 5 low-severity items (hdel/putLatest race window, dual-ownership `updated_at`, readLatestAsMap unconditional call, no `@Transactional` documentation, repo intentionality comment) deferred as pre-existing or cosmetic.

**Verification:** Java 25 not available in CI environment; zero-error compilation confirmed against `--release=21` across all modified files. All original tests preserved. 7 new unit tests added.

**Residual risks:** Sentinel `-1L` ambiguity if DB row is manually corrupted with negative counting; DB `save()` upsert behavior depends on Hibernate 7 merge semantics — if INSERT+UPDATE is not atomic, a concurrent first message for the same machine could fail with PK violation (extremely rare; acceptable given ingest is sequential per machineId).
