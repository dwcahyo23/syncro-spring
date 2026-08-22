---
title: 'Deferred-work bundle 3: WAHA dispatch hardening — GOWS no-LID, NaN failureRate, template upsert race, sentAt CHECK (corrected DW-82)'
type: 'feature'
created: '2026-08-22'
baseline_revision: '370753644ae4b11d3aaa01e01304d965f21007aa'
final_revision: '0c616a3'
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

**Problem:** Four open deferred-work items in the notification/WAHA domain degrade dispatch reliability and observability: (DW-88) GOWS returns 500 "no LID found" for invalid recipients, which trips the shared WAHA circuit breaker and blocks delivery to all other jobs; (DW-102) `WahaCircuitBreakerHealthIndicator` emits raw NaN for `failureRate` before the minimum call threshold is reached; (DW-77) `WahaTemplateService.upsertTemplate` has a read-then-write race that can silently lose a concurrent admin edit; (DW-82) `notification_jobs.sent_at` is unconstrained at DB level, so a SENT-status job can be persisted without a send timestamp.

**Approach:** (DW-88) In `WahaClient.doSend()`, inspect the response body on 5xx — if it contains "no LID found", return a failed `Result` without throwing (mirroring the 4xx deterministic-client-error path) so the circuit breaker stays closed. (DW-102) Clamp `metrics.getFailureRate()` to 0.0 when it is NaN or negative before emitting the health detail. (DW-77) Add an atomic native `INSERT ... ON CONFLICT (template_key) DO UPDATE` upsert to `WahaTemplateRepository` and re-read after writing. (DW-82) Enforce the invariant at the DB with a conditional CHECK constraint — `CHECK (status <> 'SENT' OR sent_at IS NOT NULL)` — backfilling existing SENT rows that lack `sent_at`; do NOT add a plain column NOT NULL constraint (that would reject legitimate NULL `sent_at` on PENDING/ROUTING_FAILED/EXHAUSTED/ESCALATED/CANCELLED/RATE_LIMITED rows), and do NOT make the entity field `nullable = false`.

## Boundaries & Constraints

**Always:**
- DW-88 body inspection: use `detail != null && detail.contains("no LID found")` — the exact string observed in GOWS 2026.8.1 responses. The `detail` is already truncated to `MAX_DETAIL_LENGTH` (512).
- DW-88: the `send()` method's `catch (WahaHttpStatusException)` block (line 99-101) already converts thrown exceptions to failed Results; the doSend change ensures the 5xx never reaches the throw path for the no-LID case.
- DW-102 clamp: `float rate = metrics.getFailureRate(); float failureRate = Float.isNaN(rate) || rate < 0 ? 0.0f : rate;` applied to both the `withDetail` line (47) and the reason string line (54). (Resilience4j reports NaN or -1.0 until `minimumNumberOfCalls` is reached.)
- DW-77 upsert: add a native `@Modifying(clearAutomatically = true)` upsert query to `WahaTemplateRepository` (`INSERT ... ON CONFLICT (template_key) DO UPDATE SET body = :body, updated_at = :now`), then re-read via `findByTemplateKey` to build the domain result. The unique index already exists (`V21:9`); no new migration.
- DW-82 migration V34 (current max is V33; V34 free): backfill `UPDATE notification_jobs SET sent_at = updated_at WHERE status = 'SENT' AND sent_at IS NULL;` then `ALTER TABLE notification_jobs ADD CONSTRAINT chk_notification_jobs_sent_requires_sent_at CHECK (status <> 'SENT' OR sent_at IS NOT NULL);`. Do NOT alter the `sent_at` column's nullability in the entity — leave `@Column(name = "sent_at")` unchanged.
- Use existing test infrastructure: `WahaClientCircuitBreakerTest` (JDK HttpServer), `WahaCircuitBreakerHealthIndicatorTest`, `DbIndexHygieneMigrationTest` (for V34). For DW-77 use a new `@DataJpaTest` + Testcontainers integration test (pattern: `NotificationJobCancelIntegrationTest`).

**Block If:**
- V34 conflicts with an existing uncommitted V34 elsewhere → HALT with blocking condition `migration number conflict`.

**Never:**
- Do not change the circuit breaker's `recordExceptions` config — 5xx for other transient server errors should still trip the circuit.
- Do not add a new WAHA endpoint or modify the WAHA session/webhook config.
- Do not change the `@Transactional` boundary on `WahaTemplateService.upsertTemplate` or add a retry loop — the native upsert must be the atomicity mechanism.
- Do NOT add a plain `NOT NULL` on `notification_jobs.sent_at` and do NOT set the entity `sent_at` to `nullable = false` — that is the known-bad state that broke bundle-2 (PENDING/ROUTING_FAILED jobs are legitimately persisted with NULL `sent_at`).
- Do not add Lombok/MapStruct or new testing frameworks.
- Do not alter the `send()` method's public signature or `Result` record structure.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DW-88 WAHA 500 "no LID found" | 500 response body contains "no LID found" | `Result(false, 500, detail)` returned; circuit breaker failure count unchanged | No circuit trip, no retry |
| DW-88 WAHA 500 other transient error | 500 response body is "Internal server error" | `WahaHttpStatusException` thrown; circuit breaker records failure | Circuit opens after threshold |
| DW-102 NaN failureRate | Circuit breaker state CLOSED, 0 calls made | `failureRate` detail = 0.0, reason string = null | No NaN in JSON |
| DW-102 normal failureRate | Circuit breaker OPEN, failureRate = 62.5 | `failureRate` detail = 62.5, reason string reports 62.5% | No change |
| DW-77 concurrent upsert | Two admin PUTs arrive simultaneously; second finds stale entity | Second upserts atomically; single row with the later body | No DataIntegrityViolationException propagates |
| DW-77 single upsert | One admin PUT, no concurrent edit | Persists normally | No error |
| DW-82 SENT job without sent_at | New INSERT with status=SENT, sent_at=NULL | CHECK constraint rejects the insert | DataIntegrityViolationException |
| DW-82 PENDING job without sent_at | New INSERT with status=PENDING, sent_at=NULL | INSERT succeeds (legitimate NULL) | No error |
| DW-82 existing SENT row with NULL sent_at | Pre-existing row status=SENT, sent_at=NULL | Migration backfills sent_at = updated_at | Constraint satisfied post-migration |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java` -- `doSend()` 5xx body inspection (DW-88)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java` -- NaN/negative failureRate clamp (DW-102)
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/WahaTemplateService.java` -- upsert via repository native upsert + re-read (DW-77)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaTemplateRepository.java` -- add native atomic upsert query (DW-77)
- `syncro/apps/backend/src/main/resources/db/migration/V34__notification_jobs_sent_requires_sent_at.sql` -- new migration: backfill + conditional CHECK (DW-82)
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaClientCircuitBreakerTest.java` -- DW-88 test: 500 no-LID does not open circuit
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicatorTest.java` -- DW-102 test: NaN failureRate clamped to 0
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaTemplateUpsertIntegrationTest.java` -- NEW @DataJpaTest + Testcontainers DW-77 test
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` -- extend with V34 CHECK constraint test

## Tasks & Acceptance

**Execution:**
- [x] `WahaClient.java:145-147` -- in `doSend()`, when `response.getStatusCode().is5xxServerError()`, inspect `detail` for "no LID found": if present, return `Result(false, statusCode, detail)` instead of throwing `WahaHttpStatusException` -- DW-88 deterministic client error handling
- [x] `WahaCircuitBreakerHealthIndicator.java` -- compute `failureRate` once with `Float.isNaN(rate) || rate < 0 ? 0.0f : rate` and use it in both `withDetail("failureRate", ...)` (line 47) and the OPEN reason string (line 54) -- DW-102 clamp
- [x] `WahaTemplateService.java:44-55` -- replace read-then-write with `repository.upsert(UUID.randomUUID(), DEFAULT_KEY, body, now)` + re-read via `findByTemplateKey` (`orElseThrow(WahaTemplateNotFoundException::new)`) -- DW-77 upsert atomicity
- [x] `WahaTemplateRepository.java` -- add `@Modifying(clearAutomatically = true)` native `INSERT ... ON CONFLICT (template_key) DO UPDATE` upsert method -- DW-77 atomic write
- [x] `V34__notification_jobs_sent_requires_sent_at.sql` -- backfill SENT rows, then add CHECK `(status <> 'SENT' OR sent_at IS NOT NULL)` -- DW-82 conditional DB invariant
- [x] `WahaClientCircuitBreakerTest.java` -- add test: HttpServer handler returning 500 with body "no LID found" asserts circuit stays CLOSED and result is failure -- DW-88 contract
- [x] `WahaCircuitBreakerHealthIndicatorTest.java` -- add test: pre-minimumNumberOfCalls state asserts failureRate = 0.0 -- DW-102 clamp
- [x] `WahaTemplateUpsertIntegrationTest.java` (NEW) -- @DataJpaTest + Testcontainers: two upserts leave one row with the later body -- DW-77 atomicity
- [x] `DbIndexHygieneMigrationTest.java` -- add test: V34 CHECK exists, SENT-without-sent_at insert rejected, PENDING-without-sent_at insert accepted -- DW-82 migration + domain compatibility evidence

**Acceptance Criteria:**
- Given a WAHA 500 response with body "no LID found", when `doSend()` processes it, then the circuit breaker remains CLOSED and the caller receives a failed `Result`.
- Given a WAHA 500 with a different transient error message, when `doSend()` processes it, then the circuit breaker records a failure as before.
- Given the WAHA circuit breaker has processed fewer than `minimumNumberOfCalls` calls, when `health()` is called, then `failureRate` detail is 0.0 (not NaN) and the reason string is null.
- Given two sequential `upsertTemplate` calls with the same template key, when the second writes, then exactly one row remains and its body is the later write (no `DataIntegrityViolationException` propagates to the controller).
- Given an existing or new SENT notification job, when it is inserted without a `sent_at`, then the CHECK constraint rejects it.
- Given a PENDING or ROUTING_FAILED notification job, when it is inserted without a `sent_at` (the normal application path), then the insert succeeds.
- Given existing SENT notification_jobs rows with NULL sent_at, when V34 migration runs, then those rows have sent_at = updated_at and the CHECK constraint is satisfied.

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-22 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 1 (medium 1, low 0)
- defer: 0
- reject: 17 (high 0, medium 7, low 10)
- addressed_findings:
  - `[medium]` `[patch]` V34's conditional CHECK broke the pre-existing `NotificationJobCancelIntegrationTest` — its `insertJob` helper inserted SENT rows without `sent_at` (used by `cancelsPendingAndSentJobs` and `cancelledJobsAreExcludedFromWorkerQueries`), so those inserts raised `DataIntegrityViolationException` once Flyway applied V34. Fixed by setting `sent_at = TS` when status is SENT in the helper; verified `NotificationJobCancelIntegrationTest` (4/4) and `DbIndexHygieneMigrationTest` (10/10) pass. This was a real consequence of the change that the spec's "use existing test infrastructure" note did not pre-empt.
- reject_findings (dropped silently, summary only):
  - `[medium]` DW-88 `detail.contains("no LID found")` is case-sensitive and vendor-message-brittle — the observed GOWS 2026.8.1 body is stable lowercase; a variant casing would regress to circuit-tripping but no evidence GOWS emits variants; speculative.
  - `[medium]` DW-88 detail is truncated to 512 chars before the marker check — GOWS error bodies are short and the marker sits at the start of the `error` value; speculative.
  - `[medium]` DW-88 inverse misclassification (a genuine transient 500 whose body quotes "no LID found") — no evidence; speculative.
  - `[medium]` DW-88 returned `Result(false, 500, ...)` still flows through `markAttemptFailed` so a deterministic bad recipient is retried up to maxAttempts — this mirrors the existing 4xx path (which also returns without throwing and retries); consistent design, out of DW-88's stated "don't trip the circuit" scope.
  - `[medium]` DW-88 only special-cases the single `"no LID found"` literal — other deterministic GOWS 500 bodies remain whack-a-mole; DW-88 is scoped to the observed error, out of scope.
  - `[medium]` DW-102 clamp conflates "no data" (NaN) with "healthy 0%" — the clamp-to-0 was the spec-mandated decision for a clean numeric payload; frontend already tolerates missing/NaN per-field; acceptable.
  - `[medium]` DW-77 read-back after upsert is not atomic with the write — under a concurrent writer the returned row could reflect another writer; template table is single-row admin-edited and last-writer-wins is the acceptable semantic; speculative.
  - `[low]` DW-88 special-cased `Result` is indistinguishable from a transient 500 to callers — no discriminator requested by the spec; `Result` shape is read-only per boundaries.
  - `[low]` DW-88 test does not cover >512-char body whose marker sits past truncation — no GOWS evidence; speculative.
  - `[low]` DW-77 `@Modifying(clearAutomatically = true)` without `flushAutomatically = true` — no prior JPA writes exist in the current unit of work; latent future-caller concern, not a current bug.
  - `[low]` DW-77 fresh `UUID.randomUUID()` wasted on every update (conflict branch discards id) — negligible; single-row table.
  - `[low]` DW-77 native upsert bypasses JPA lifecycle (`@PrePersist`/`@PreUpdate`/`@Version`) — the entity has none today; timestamps supplied explicitly by the service; correct current behavior.
  - `[low]` DW-77 integration test only exercises the conflict-update branch (V22 seeds the default template) — the ON CONFLICT insert branch is identical SQL; acceptable coverage.
  - `[low]` DW-82 backfill `sent_at = updated_at` is a proxy timestamp with no audit trail — the spec's Design Notes document the approximation; escalation skew is bounded and one-time; acceptable.
  - `[low]` DW-82 FK-chain seeding duplicated across `NotificationJobCancelIntegrationTest` and `DbIndexHygieneMigrationTest` — test-infra polish, pre-existing duplication pattern; not caused by this change.
  - `[low]` DW-82 CHECK hardcodes status `'SENT'` — a future sent-requiring status would need a migration anyway; speculative.
  - `[low]` DW-82 PENDING→SENT UPDATE transition not tested — the app always sets `sentAt` inside `markSent()` before the entity reaches SENT status; the CHECK is satisfied by construction.
  - `[low]` DW-82/health/upsert tests use raw SQL or sequential calls rather than the full application/concurrent path — established pattern; the atomicity is guaranteed by `ON CONFLICT` at the DB, not by the test harness.

## Design Notes

- **DW-82 correction (vs bundle-2)**: The blocked bundle-2 attempted `ALTER COLUMN sent_at SET NOT NULL` + entity `nullable = false`. Review proved that breaks the domain: `NotificationJobEntity` is constructed with NULL `sentAt` for non-SENT jobs and `NotificationRoutingService`/`EscalationService` persist them; `NotificationRoutingService.onAlertOpened` catches `DataIntegrityViolationException` as a duplicate-event idempotent skip, so a broken insert would be silently dropped. The corrected approach is a conditional CHECK `(status <> 'SENT' OR sent_at IS NOT NULL)` — SENT rows must carry a timestamp, all other statuses keep NULL. Entity `@Column(name = "sent_at")` stays unchanged.
- **DW-88 body inspection**: The `detail` is the response body truncated to 512 chars (`MAX_DETAIL_LENGTH`). The known GOWS error body is a JSON like `{"error":"no LID found for phone number ..."}`. `contains("no LID found")` is a substring match — avoids coupling to the WAHA error schema shape. Non-5xx behavior is untouched; 4xx already returns a failed Result without tripping the circuit.
- **DW-102 clamp**: Resilience4j reports NaN or -1.0 as the failure rate until `minimumNumberOfCalls` is reached. Clamping NaN and negatives to 0.0 emits a clean numeric value; once calls accumulate the real rate flows through.
- **DW-77 upsert**: the unique index on `waha_templates.template_key` already exists (`V21:9`). The native `INSERT ... ON CONFLICT (template_key) DO UPDATE` is atomic at the DB — no application-level retry loop, no persistence-context pollution after a failed flush, no new migration. `@Modifying(clearAutomatically = true)` prevents a stale entity read; the service re-reads via `findByTemplateKey` to return the persisted domain object.
- **DW-82 backfill**: `updated_at` is set by `@PreUpdate`/`@PrePersist` on every mutation, so for any SENT job ever saved it is a reasonable approximation of the sent time.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="WahaClientCircuitBreakerTest"` -- expected: existing + no-LID test pass
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="WahaCircuitBreakerHealthIndicatorTest"` -- expected: existing + NaN-clamp test pass
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="WahaTemplateUpsertIntegrationTest"` -- expected: single-row-latest-body test pass (Testcontainers)
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="DbIndexHygieneMigrationTest"` -- expected: V34 CHECK test pass (Testcontainers)
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: no compilation errors

**Manual checks (if no CLI):**
- Inspect `WahaClient.java:145-147` for the `detail.contains("no LID found")` branch.
- Inspect `WahaCircuitBreakerHealthIndicator.java` for the NaN/negative clamp in both failureRate usages.
- Inspect `V34__notification_jobs_sent_requires_sent_at.sql` for the backfill + conditional CHECK (NOT a plain NOT NULL).
- Inspect `NotificationJobEntity.java` `sent_at` remains `@Column(name = "sent_at")` (no `nullable = false`).

## Auto Run Result

**Status:** done

**Summary:** Bundled four open deferred-work items (DW-88, DW-102, DW-77, DW-82) in the notification/WAHA domain: (DW-88) GOWS "no LID found" 500 now returns a failed `Result` without throwing, so the circuit breaker stays CLOSED for invalid-recipient errors; (DW-102) `WahaCircuitBreakerHealthIndicator` clamps NaN/negative failure rates to 0.0; (DW-77) `WahaTemplateService` upsert is now atomic via a native `INSERT ... ON CONFLICT (template_key) DO UPDATE`; (DW-82) V34 backfills SENT rows with NULL `sent_at` and adds a conditional CHECK `(status <> 'SENT' OR sent_at IS NOT NULL)` — NOT a plain NOT NULL, which was the intent gap that blocked bundle-2.

**Files changed:**
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaClient.java` — 5xx no-LID body check (DW-88)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicator.java` — NaN/negative failureRate clamp (DW-102)
- `syncro/apps/backend/src/main/java/com/syncro/notification/application/WahaTemplateService.java` — native upsert + re-read (DW-77)
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/WahaTemplateRepository.java` — `@Modifying` native upsert method (DW-77)
- `syncro/apps/backend/src/main/resources/db/migration/V34__notification_jobs_sent_requires_sent_at.sql` — backfill + conditional CHECK (DW-82)
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaClientCircuitBreakerTest.java` — no-LID 500 test (DW-88)
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaCircuitBreakerHealthIndicatorTest.java` — NaN clamp test (DW-102)
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/WahaTemplateUpsertIntegrationTest.java` (NEW) — DW-77 atomicity
- `syncro/apps/backend/src/test/java/com/syncro/db/DbIndexHygieneMigrationTest.java` — V34 CHECK + PENDING-null-sent_at compatibility test (DW-82)
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobCancelIntegrationTest.java` — insertJob helper updated for V34 CHECK (patch)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 1 patch (medium — NotificationJobCancelIntegrationTest broke under V34; fixed by adding sent_at to SENT inserts), 0 deferrals, 17 rejected as noise.

**Follow-up review recommendation:** false — the single patch was localized, low-consequence, and verified green.

**Verification performed:**
- `WahaClientCircuitBreakerTest` (12/12) PASS
- `WahaCircuitBreakerHealthIndicatorTest` (8/8) PASS
- `WahaTemplateUpsertIntegrationTest` (1/1, Testcontainers) PASS
- `DbIndexHygieneMigrationTest` (10/10, Testcontainers) PASS
- `NotificationJobCancelIntegrationTest` (4/4, Testcontainers) PASS
- `mvn test-compile` PASS

**Residual risks:**
- None beyond the pre-existing `SparepartLifetimeEvaluatorTest` and `WahaRateLimiterTest` failures (unrelated to this bundle, confirmed identical at baseline).