---
title: 'Deferred-work bundle 5: MQTT ingest pipeline hardening — worker threads, cleanSession, wiring-test decoupling'
type: 'feature'
created: '2026-08-22'
baseline_revision: '8310bba'
final_revision: '7c5721f'
status: 'done'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals', 'oversized']
---

<intent-contract>

## Intent

**Problem:** Three open deferred-work items in the MQTT telemetry ingest pipeline: (DW-91) `TelemetryProperties.ingest.workerThreads` (default 2) is declared and validated but never wired — the `QueueChannel` is consumed single-threaded by Spring Integration's default poller, capping throughput at ~20-33 msg/s, so a plant with hundreds of machines backs up the 1000-capacity queue and EMQX starts dropping messages; (DW-93) `MqttSubscriptionConfig` sets `cleanSession(true)`, which discards in-flight QoS-1 messages on reconnect and defeats at-least-once redelivery (the Redis SETNX dedupe gate already handles duplicates that arrive); (DW-19) `MqttSubscriptionConfigTest` is forced to mock DB-backed `TelemetryValidationService`/`TelemetryPersistenceService`/`TelemetryQuarantineService` purely to keep the wiring context loadable, and with Mockito defaults the mocked `validate()` rejects every message — the wiring test's handler is non-functional in principle.

**Approach:** (DW-91) Add a `ThreadPoolTaskExecutor` bean sized by `ingest.workerThreads()` in `TelemetryIngestQueueConfig`, and add a `.poller(...)` with that executor to the `mqttInboundFlow` handle endpoint so the bounded queue is consumed by N worker threads. Because the per-machine counting-delta chain (`readCounting → compute delta → write Influx → putLatest`) is read-modify-write state that must NOT run concurrently for the SAME machine (two concurrent messages for one machine would both read the same previous counting and double-count the delta), add a small striped per-machine serialization helper and wrap the counting critical section in `TelemetryPersistenceService.persist` with it — cross-machine messages still parallelize. (DW-93) Change `options.setCleanSession(true)` → `false`; clientId is already stable (`syncro.mqtt.client-id`), so the broker persists the session and redelivers in-flight QoS-1 messages on reconnect, which the SETNX dedupe gate absorbs. (DW-19) Introduce a `@FunctionalInterface TelemetryValidator` with `Result validate(String topic, String payload)`; `TelemetryValidationService` implements it, `MqttTelemetryIngestHandler` depends on the interface, and the wiring test supplies a lightweight stub instead of a Mockito mock of the DB-backed service.

## Boundaries & Constraints

**Always:**
- DW-91 executor: define the bean in `TelemetryIngestQueueConfig` as `ThreadPoolTaskExecutor` with core/max pool size = `ingest.workerThreads()`, queue capacity = `ingest.queueCapacity()`, a descriptive thread-name prefix (`telemetry-ingest-`), and `waitForTasksToCompleteOnShutdown(true)`. Wire it into `MqttSubscriptionConfig.mqttInboundFlow` via `e.poller(Pollers.fixedDelay(100).taskExecutor(executor).maxMessagesPerPoll(workerThreads))`. Do NOT add a second executor bean elsewhere.
- DW-91 serialization: add a `PerMachineExecution` helper (package `com.syncro.telemetry.infrastructure` or `application` — choose the layer that avoids domain importing infra) exposing `void run(UUID machineId, Runnable criticalSection)` backed by a striped lock array (e.g., 64 locks indexed by `machineId.hashCode() & 63`). Wrap ONLY the counting critical section in `TelemetryPersistenceService.persist` — from the `readCounting` call through the `putLatest`/`counterStateRepo.save` — so per-machine delta reads/writes serialize while different machines run in parallel. The dedupe SETNX gate stays OUTSIDE the lock (it is per-messageId and idempotent). Do not hold the lock across the alert evaluation block.
- DW-91 config test: assert the executor bean's core/max pool size equals `ingest.workerThreads()` and that the poller is configured on the flow (e.g., assert `MqttSubscriptionConfig` produces a flow whose handle endpoint has a poller with the executor).
- DW-93: one-line change `options.setCleanSession(false)` in `MqttSubscriptionConfig.mqttConnectOptions`; keep the stable clientId. Add a test asserting `mqttConnectOptions.isCleanSession() == false`. Keep the dedupe SETNX gate as-is (duplicates that arrive are already absorbed).
- DW-19: new `TelemetryValidator` functional interface with a single method `TelemetryValidationService.Result validate(String topic, String payload)`; `TelemetryValidationService implements TelemetryValidator`; `MqttTelemetryIngestHandler` field type becomes `TelemetryValidator`. Update `MqttSubscriptionConfigTest`'s `@TestConfiguration` to supply a stub bean `(topic, payload) -> new TelemetryValidationService.Result.Rejected("test", null)` instead of `mock(TelemetryValidationService.class)`. Keep the existing `TelemetryValidationServiceTest` (unit) and integration tests green — the service still exposes `validate`.

**Block If:**
- Adding the executor or poller causes Spring Integration context startup failures that cannot be resolved locally → HALT with blocking condition `integration flow wiring failed`.
- The `TelemetryValidator` abstraction requires changing `TelemetryValidationService.Result` (sealed interface) shape or breaks the handler's pattern-match switch → HALT with blocking condition `validator abstraction incompatible with sealed Result`.

**Never:**
- Do NOT change the counting-delta semantics or `CountingDeltaCalculator`.
- Do NOT hold the per-machine lock across the sparepart/alert evaluation block (`evaluator.evaluateAll` / `alertService.evaluateAndCreateAlerts`) — that work must stay outside the critical section.
- Do NOT change `ingest.worker-threads` default or validation in `TelemetryProperties`.
- Do NOT change the MQTT topic filter, QoS, or the SETNX dedupe window.
- Do NOT add a retry loop, scheduler, or new dependencies for DW-91 — the executor + poller + striped lock is the complete fix.
- Do NOT add Lombok/MapStruct or new testing frameworks.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DW-91 default config | `workerThreads=2`, `queueCapacity=1000` | Executor pool size 2; poller uses it; flow consumes queue on 2 threads | No change to dedupe or quarantine semantics |
| DW-91 same-machine concurrent | Two messages for machine M arrive in queue simultaneously | Counting critical section serialized per M; deltas computed sequentially (no double-count) | Lock released even if Influx write throws (finally) |
| DW-91 different-machine concurrent | Two messages for machines A and B | Both process in parallel (different stripes) | No contention |
| DW-91 lock scope | Persist runs; sparepart/alert evaluation pending | Alert evaluation runs OUTSIDE the lock | No deadlock |
| DW-93 broker reconnect | Session drops while QoS-1 message in flight | In-flight message redelivered; SETNX dedupe absorbs the duplicate | No duplicate Influx point |
| DW-93 cleanSession flag | Inspect `MqttConnectOptions` | `isCleanSession() == false` | No error |
| DW-19 wiring test | Spring context loads without DB auto-config | Handler wired with stub validator; test verifies topic/QoS/flow wiring | No DB beans required |
| DW-19 real validation | Production path | `TelemetryValidationService.validate` unchanged behavior | Existing unit + integration tests green |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/TelemetryIngestQueueConfig.java` -- add `ThreadPoolTaskExecutor` bean sized by `ingest.workerThreads()` (DW-91)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfig.java` -- `.poller(...)` with executor on `mqttInboundFlow` handle endpoint; `cleanSession(false)` in `mqttConnectOptions` (DW-91 + DW-93)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryPersistenceService.java` -- wrap counting critical section with per-machine serialization (DW-91)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/PerMachineExecution.java` (NEW) -- striped per-machine lock helper (DW-91)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidator.java` (NEW) -- `@FunctionalInterface` validator abstraction (DW-19)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/TelemetryValidationService.java` -- implement `TelemetryValidator` (DW-19)
- `syncro/apps/backend/src/main/java/com/syncro/telemetry/application/MqttTelemetryIngestHandler.java` -- depend on `TelemetryValidator` instead of `TelemetryValidationService` (DW-19)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/MqttSubscriptionConfigTest.java` -- stub validator bean; assert cleanSession(false) + poller executor (DW-19 + DW-93 + DW-91)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/infrastructure/TelemetryIngestQueueConfigTest.java` -- executor pool-size assertion (DW-91)
- `syncro/apps/backend/src/test/java/com/syncro/telemetry/application/PerMachineExecutionTest.java` (NEW) -- striped lock mutual-exclusion + parallel-different-key test (DW-91)

## Tasks & Acceptance

**Execution:**
- [x] `TelemetryValidator.java` (NEW) -- `@FunctionalInterface public interface TelemetryValidator { TelemetryValidationService.Result validate(String topic, String payload); }` -- DW-19 abstraction
- [x] `TelemetryValidationService.java` -- `public class TelemetryValidationService implements TelemetryValidator`; keep existing `validate` signature -- DW-19
- [x] `MqttTelemetryIngestHandler.java` -- change constructor field `TelemetryValidationService validationService` → `TelemetryValidator validator`; update `validate(...)` call sites -- DW-19
- [x] `PerMachineExecution.java` (NEW) -- striped lock helper: `private static final int STRIPES = 64;` `private final Object[] locks = new Object[STRIPES];` initialized with `new Object` per index; `public void run(UUID machineId, Runnable criticalSection)` computes `locks[(machineId.hashCode() & Integer.MAX_VALUE) % STRIPES]`, `synchronized(lock) { criticalSection.run(); }` -- DW-91 per-machine serialization
- [x] `TelemetryPersistenceService.java` -- inject `PerMachineExecution`; wrap from `long previousCounting = redisLatestWriter.readCounting(...)` through the `counterStateRepo.save(new MachineCounterStateEntity(machineId, currentCounting))` (i.e., the whole read-compute-write block including the Influx write, Redis putLatest, and counter-state save) in `perMachineExecution.run(machineId, () -> { ... })`, preserving existing try/catch + dedupe-cleanup semantics and releasing the lock on exceptions (synchronized releases automatically) -- DW-91
- [x] `TelemetryIngestQueueConfig.java` -- add `@Bean ThreadPoolTaskExecutor telemetryIngestExecutor(TelemetryProperties props)` with corePoolSize/maxPoolSize=`props.ingest().workerThreads()`, queueCapacity=`props.ingest().queueCapacity()`, threadNamePrefix=`telemetry-ingest-`, `setWaitForTasksToCompleteOnShutdown(true)` -- DW-91
- [x] `MqttSubscriptionConfig.java` -- `mqttInboundFlow`: `.handle(handler, e -> e.poller(Pollers.fixedDelay(100).taskExecutor(telemetryIngestExecutor).maxMessagesPerPoll(ingest.workerThreads())))` (add params: `ThreadPoolTaskExecutor telemetryIngestExecutor`, `TelemetryProperties telemetryProperties`); `mqttConnectOptions`: `options.setCleanSession(false)` -- DW-91 + DW-93
- [x] `MqttSubscriptionConfigTest.java` -- replace `mock(TelemetryValidationService.class)` bean with `(topic, payload) -> new TelemetryValidationService.Result.Rejected("test", null)` stub (remove `mock` import if unused); add test asserting `mqttConnectOptions.isCleanSession() == false`; add test asserting the flow's poller uses the executor -- DW-19 + DW-93 + DW-91
- [x] `TelemetryIngestQueueConfigTest.java` -- add test asserting `telemetryIngestExecutor.getCorePoolSize() == props.ingest().workerThreads()` (and max) -- DW-91
- [x] `PerMachineExecutionTest.java` (NEW) -- test 1: two threads running `run(sameMachineId, ...)` do not overlap (use a latched critical section; assert max concurrency for that key is 1); test 2: two threads running `run(differentMachineIdA/B, ...)` DO overlap (assert concurrency 2) -- DW-91

**Acceptance Criteria:**
- Given the configured `ingest.workerThreads = 2`, when the Spring context loads, then the `telemetryIngestExecutor` bean has core and max pool size 2 and the `mqttInboundFlow` handle endpoint's poller uses that executor.
- Given two concurrent messages for the same machine, when both pass the dedupe gate and enter `persist`, then the counting critical section runs serially for that machine (deltas computed sequentially, no double-count), while messages for different machines run in parallel.
- Given the broker drops a connection with an in-flight QoS-1 message, when the client reconnects, then `cleanSession` is false so the broker can redeliver, and the SETNX dedupe gate absorbs the duplicate (no duplicate Influx point).
- Given `MqttConnectOptions` built by `mqttConnectOptions`, then `isCleanSession()` returns false.
- Given the `MqttSubscriptionConfigTest` context, then it loads without DB auto-configuration or DB-backed mocks, and its handler is wired with a stub `TelemetryValidator`.
- Given `TelemetryValidationService` in production, then its `validate(topic, payload)` behavior is unchanged (existing unit + integration tests green), and `MqttTelemetryIngestHandler` accepts any `TelemetryValidator`.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-22 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 2 (medium 1, low 1)
- defer: 0
- reject: 16 (high 0, medium 3, low 13)
- addressed_findings:
  - `[medium]` `[patch]` `ThreadPoolTaskExecutor` set `waitForTasksToCompleteOnShutdown(true)` without `awaitTerminationSeconds` — a stuck poll task on Redis/Influx I/O could hang context shutdown indefinitely. Added `setAwaitTerminationSeconds(30)`.
  - `[low]` `[patch]` `PerMachineExecutionTest.sameMachineCriticalSectionsDoNotOverlap` used bare `Thread.sleep(50)` with no coordination — the scheduler could run the two threads sequentially so `maxActive==1` passes without ever proving mutual exclusion. Added a ready/go `CountDownLatch` pair so both threads attempt entry simultaneously before the assertion.
- reject_findings (dropped silently, summary only):
  - `[medium]` `UUID.hashCode()` stripe routing not contractually stable across JVM versions — harmless for correctness (same machine → same stripe within a JVM); speculative.
  - `[medium]` executor `queueCapacity` == QueueChannel capacity double-buffers, queue-depth metric under-reports — performance/monitoring characteristic, not a correctness bug; acceptable at pilot scale.
  - `[medium]` no metrics registered for the executor (only the QueueChannel) — monitoring gap, out of the spec's "no new dependencies" boundary; the QueueChannel depth/remaining gauges remain the primary signal.
  - `[low]` `countingDeltaHolder` mutable side-channel array — safe because `run()` is synchronous (`synchronized` block, same thread); speculative future-async concern.
  - `[low]` `cleanSession(false)` breaks under horizontal scaling with a shared clientId — the clientId is configured per instance (`syncro.mqtt.client-id`); single-instance assumption is explicit in DW-93; out of scope.
  - `[low]` `cleanSession(false)` reconnect burst overwhelms the queue — the bounded QueueChannel + backpressure handles it; SETNX dedupe absorbs duplicates; latency spike is a performance characteristic.
  - `[low]` `TelemetryValidator` returns `TelemetryValidationService.Result`, coupling the abstraction to the sealed hierarchy — the spec explicitly decided to keep Result where it is; the wiring test no longer mocks the DB-backed service, which is the DW-19 goal.
  - `[low]` `Result.Accepted` carries a JPA `MachineEntity`, leaking persistence into the validation interface — pre-existing design of the Result type, not introduced by DW-19.
  - `[low]` wiring test only exercises the `Rejected` branch of the handler switch — the `Accepted` branch is covered by `MqttTelemetryIngestHandlerTest` (17 tests); the wiring test asserts wiring only.
  - `[low]` `machineOnDifferentStripe` unbounded retry loop in the test — collision probability 1/64 per iteration, terminates practically immediately; speculative.
  - `[low]` `mqttConnectOptions` is a public `@Bean` exposing mutable state — it was already a public `@Bean` before this diff; not introduced by the change.
  - `[low]` `maxMessagesPerPoll=workerThreads` makes poll latency = 100ms + batch processing — intended throttled behavior; under load the bounded queue absorbs; acceptable.
  - `[low]` stripe count 64 hardcoded — documented design choice in the spec with rationale; contention at thousands of machines is future work.
  - `[low]` `deleteDedupeKey` runs under the stripe lock — only on the Influx-write failure path; extends lock hold by one Redis delete; rare and acceptable.
  - `[low]` `counterStateRepo.save` inside the lambda not wrapped — same behavior as the pre-change code; message is considered processed (Influx + latest written); intentional.
  - `[low]` `PerMachineExecution` null-machineId guard redundant — NPE occurs earlier at `dedupeKey` construction inside the handler's outer try/catch.

## Design Notes

- **DW-91 concurrency correctness**: The per-machine counting delta is read-modify-write (`readCounting` → `CountingDeltaCalculator.delta` → Influx point → `putLatest`). With a multi-thread poller, two messages for the SAME machine could read the same `previousCounting` and both compute deltas from it, double-counting the span. The striped lock serializes the critical section per machine only; cross-machine messages take different stripes and run in parallel. The dedupe SETNX gate (per messageId) stays outside the lock — it is idempotent and must remain cheap. The lock is released automatically on exception via `synchronized` (block-scoped), and the existing dedupe-cleanup `deleteDedupeKey` runs inside the try, before the lock exits. Alert evaluation (`evaluator.evaluateAll` + `alertService.evaluateAndCreateAlerts`) stays OUTSIDE the locked region to keep the critical section short.
- **DW-93 cleanSession(false)**: The clientId is bound from `syncro.mqtt.client-id` (stable per instance). With `cleanSession(false)`, EMQX persists the session; QoS-1 messages in flight during a reconnect are redelivered. The Redis SETNX dedupe gate already absorbs duplicates (existing `syncro:machine:{id}:telemetry:dedupe:{messageId}`), so at-least-once semantics hold without double-persisting. The current TODO comment in `mqttConnectOptions` documents exactly this switch.
- **DW-19 abstraction**: `TelemetryValidator` returns `TelemetryValidationService.Result` (the sealed interface stays where it is), so the handler's `switch` pattern-match on `Result.Accepted`/`Result.Rejected` is untouched. `TelemetryValidationService implements TelemetryValidator` means no production behavior change. The wiring test no longer imports Mockito for the DB-backed service (Mockito `mock` import removed if now unused elsewhere in that file).
- **Stripe indexing**: `(machineId.hashCode() & Integer.MAX_VALUE) % STRIPES` avoids negative hash-derived indexes (Java `hashCode` can be negative). 64 stripes is more than enough for cross-machine parallelism while guaranteeing the same machine always maps to the same stripe.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="MqttSubscriptionConfigTest,TelemetryIngestQueueConfigTest,PerMachineExecutionTest"` -- expected: wiring, cleanSession, executor, and serialization tests pass
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="TelemetryValidationServiceTest,MqttTelemetryIngestHandlerTest"` -- expected: validation behavior unchanged
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: no compilation errors

**Manual checks (if no CLI):**
- Inspect `MqttSubscriptionConfig.java` for `cleanSession(false)` and the `.poller(...)` with the executor.
- Inspect `TelemetryPersistenceService.persist` for the `perMachineExecution.run(machineId, ...)` wrap around the counting critical section and that alert evaluation is outside it.
- Inspect `TelemetryIngestQueueConfig` for the executor bean sized by `workerThreads`.

## Auto Run Result

**Status:** done

**Summary:** Bundled three open deferred-work items (DW-91, DW-93, DW-19) in the MQTT telemetry ingest pipeline: (DW-91) wired `TelemetryProperties.ingest.workerThreads` into a `ThreadPoolTaskExecutor` consumed by a `.poller(...)` on the `mqttInboundFlow` handle endpoint, and added a striped `PerMachineExecution` lock so the per-machine counting-delta critical section (readCounting → delta → Influx write → putLatest → counter-state save) serializes per machine — preventing double-counting under concurrent consumption — while different machines parallelize and the dedupe SETNX gate and alert evaluation stay outside the lock; (DW-93) switched `cleanSession(true)` → `false` so the broker redelivers in-flight QoS-1 messages on reconnect (SETNX dedupe absorbs duplicates); (DW-19) introduced a `@FunctionalInterface TelemetryValidator`, made `TelemetryValidationService` implement it, pointed `MqttTelemetryIngestHandler` at the interface, and replaced the wiring test's DB-backed `mock(TelemetryValidationService.class)` with a lightweight stub.

**Files changed:**
- `TelemetryValidator.java` (new) — validator abstraction (DW-19)
- `TelemetryValidationService.java` — implements `TelemetryValidator` (DW-19)
- `MqttTelemetryIngestHandler.java` — depends on `TelemetryValidator` (DW-19)
- `PerMachineExecution.java` (new) — striped per-machine lock (DW-91)
- `TelemetryPersistenceService.java` — wraps counting critical section in `perMachineExecution.run` (DW-91)
- `TelemetryIngestQueueConfig.java` — `telemetryIngestExecutor` bean sized by workerThreads (+ awaitTermination patch) (DW-91)
- `MqttSubscriptionConfig.java` — `.poller(...)` with executor; `cleanSession(false)` (DW-91 + DW-93)
- `MqttSubscriptionConfigTest.java` — stub validator bean, cleanSession(false) + executor tests (DW-19 + DW-93 + DW-91)
- `TelemetryIngestQueueConfigTest.java` — executor pool-size test (DW-91)
- `PerMachineExecutionTest.java` (new) — same-machine mutual exclusion + cross-machine parallelism tests (DW-91)
- `TelemetryPersistenceServiceTest.java` — constructor gains `new PerMachineExecution()` (required for compile)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 2 patches applied (executor awaitTermination, test latch coordination), 0 deferrals, 16 rejected as noise (performance characteristics, out-of-scope deployment concerns, pre-existing design, or speculative).

**Follow-up review recommendation:** false — patches were localized and low-consequence; tests verified green.

**Verification performed:**
- `MqttSubscriptionConfigTest` (5/5) PASS — cleanSession(false), poller executor, topic/QoS wiring
- `TelemetryIngestQueueConfigTest` (7/7) PASS — executor pool size = workerThreads
- `PerMachineExecutionTest` (2/2) PASS — same-machine serialized, cross-machine parallel
- `TelemetryValidationServiceTest` (21/21) + `MqttTelemetryIngestHandlerTest` (17/17) PASS — validation behavior unchanged
- `mvn test-compile` PASS

**Residual risks:**
- Stripe-count 64 is hardcoded; contention grows only at thousands of machines (documented, future work).
- Executor queue double-buffering means the QueueChannel depth gauge under-reports total in-flight backlog (acceptable at pilot scale).
- `cleanSession(false)` assumes a single instance per clientId (explicit DW-93 assumption).