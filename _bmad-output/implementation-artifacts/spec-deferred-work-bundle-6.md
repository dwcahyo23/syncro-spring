---
title: 'Deferred-work bundle 6: Notification dispatch reliability — @Transactional across WAHA call, escalation SENT loop'
type: 'feature'
created: '2026-08-22'
baseline_revision: 'f10affb'
status: 'in-review'
review_loop_iteration: 0
followup_review_recommended: false
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/deferred-work.md'
warnings: ['multiple-goals', 'oversized']
---

<intent-contract>

## Intent

**Problem:** Two open deferred-work items in the notification dispatch/escalation domain degrade reliability: (DW-87) `NotificationDispatchService.dispatch()` holds a `@Transactional` DB connection across the WAHA HTTP call (up to 5s per job × 10 jobs/batch = up to 50s of connection hold), needlessly occupying pool connections and risking connection exhaustion during a WAHA slowdown; (DW-83) `EscalationWorker` polls SENT jobs whose alert is no longer OPEN forever, because `EscalationService.escalate()` returns without mutating the job when the alert is non-OPEN, and `findSentJobsDueForEscalation` re-selects the same SENT rows every 60s — a permanent loop that wastes worker cycles.

**Approach:** (DW-87) Remove `@Transactional` from `dispatch()`, inject `TransactionTemplate`, and wrap each DB write phase (attempt save + job save) in a short transaction so the WAHA call happens outside any DB transaction. (DW-83) Change `findSentJobsDueForEscalation` to join `sparepart_alerts` and select only SENT jobs whose alert status is OPEN, so closed-alert jobs are never re-polled (the job stays SENT by design for reopen tracking, but the escalation loop stops).

## Boundaries & Constraints

**Always:**
- DW-87: inject `TransactionTemplate` (created from `PlatformTransactionManager`) into `NotificationDispatchService`. Remove `@Transactional` from `dispatch()`. Wrap each of the four DB write phases in `transactionTemplate.executeWithoutResult(...)`: (1) rate-limited `markRateLimited + save`; (2) template-render-failure `attempt save + markExhausted + save`; (3) success `attempt save + markSent + save`; (4) failure `attempt save + markAttemptFailed/markCircuitOpen + save`. The WAHA send, rate-limiter check, and template render happen OUTSIDE any transaction. Preserve the exact same `jobRepository.save(job)` and `attemptRepository.save(attempt)` calls and order within each phase.
- DW-87 test: `NotificationDispatchServiceTest` constructs the service directly with mocks. Add `@Mock PlatformTransactionManager transactionManager` and pass it to the constructor. `TransactionTemplate` with a mock manager calls `getTransaction` (returns null) and `commit/rollback` (no-ops with null status). All 9 existing behavior tests must pass without changes to their assertions.
- DW-83: modify `findSentJobsDueForEscalation` JPQL to `select j from NotificationJobEntity j join SparepartAlertEntity a on a.id = j.alertId where j.status = :status and j.sentAt <= :cutoff and a.status = com.syncro.alert.domain.SparepartAlertStatus.OPEN order by j.sentAt asc limit 10`. Do NOT delete or modify the old method or add a new method — replace the existing `findSentJobsDueForEscalation` query body.
- DW-83 does NOT change the `EscalationService.escalate()` behavior — it still skips non-OPEN alerts (AC10 protection). The query change only prevents re-polling.
- DW-83 test: repository/integration test asserting a SENT job with an OPEN alert is returned by `findSentJobsDueForEscalation`, and a SENT job with a non-OPEN alert (ACKNOWLEDGED) is NOT returned. Reuse `DbIndexHygieneMigrationTest` pattern or a focused `@DataJpaTest` with Testcontainers.

**Block If:**
- TransactionTemplate with a mock PlatformTransactionManager causes any of the 9 existing `NotificationDispatchServiceTest` tests to fail → HALT with blocking condition `transaction mock broke existing tests`.
- The JPQL cross-entity join (`NotificationJobEntity join SparepartAlertEntity on a.id = j.alertId`) fails at Hibernate/JPA compilation → HALT with blocking condition `cross-entity JPQL join unsupported`.

**Never:**
- Do NOT change `EscalationService` or `EscalationWorker` Java source beyond the repository query — the query change is the complete fix.
- Do NOT change `SparepartAlertEntity`, `NotificationJobEntity`, `WahaClient`, or any other production Java source outside `NotificationDispatchService` and `NotificationJobRepository`.
- Do NOT add a retry loop, scheduler, or new dependencies for DW-87.
- Do NOT add Lombok/MapStruct or new testing frameworks.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| DW-87 WAHA success | job PENDING, WAHA returns success | attempt saved, job marked SENT in a single tx; WAHA call outside any tx | On DB failure, attempt+job roll back together; rate-limiter key (Redis) persists |
| DW-87 WAHA circuit-open | WAHA circuit OPEN, job PENDING | attempt saved FAILED, job marked PENDING with nextAttemptAt=waitDuration; WAHA call outside tx | No DB tx held during fast-fail |
| DW-87 template render failure | template render throws | attempt saved FAILED, job marked EXHAUSTED in a single tx | No WAHA call made |
| DW-83 OPEN alert SENT job | job SENT, alert OPEN, sentAt <= cutoff | Job returned by findSentJobsDueForEscalation | Normal escalation path |
| DW-83 non-OPEN alert SENT job | job SENT, alert ACKNOWLEDGED/RESOLVED, sentAt <= cutoff | Job NOT returned by findSentJobsDueForEscalation | Loop stops; job stays SENT for reopen tracking |

</intent-contract>

## Code Map

- `syncro/apps/backend/src/main/java/com/syncro/notification/application/NotificationDispatchService.java` -- remove `@Transactional`, inject `TransactionTemplate`, wrap DB write phases -- DW-87
- `syncro/apps/backend/src/main/java/com/syncro/notification/infrastructure/NotificationJobRepository.java` -- change `findSentJobsDueForEscalation` JPQL to join alert OPEN -- DW-83
- `syncro/apps/backend/src/test/java/com/syncro/notification/application/NotificationDispatchServiceTest.java` -- add `@Mock PlatformTransactionManager`, pass to constructor; all 9 existing tests unchanged -- DW-87
- `syncro/apps/backend/src/test/java/com/syncro/notification/infrastructure/NotificationJobCancelIntegrationTest.java` or NEW -- add DW-83 test: SENT job with OPEN alert returned, non-OPEN alert not returned -- DW-83

## Tasks & Acceptance

**Execution:**
- [x] `NotificationDispatchService.java` -- inject `TransactionTemplate` (created from `PlatformTransactionManager`); remove `@Transactional` from `dispatch()`; wrap each of the 4 DB write phases in `transactionTemplate.executeWithoutResult(...)` -- DW-87
- [x] `NotificationJobRepository.java` -- replace `findSentJobsDueForEscalation` JPQL with join on `SparepartAlertEntity` filtering `a.status = OPEN` -- DW-83
- [x] `NotificationDispatchServiceTest.java` -- add `@Mock PlatformTransactionManager transactionManager`; pass to constructor; verify all 9 existing tests pass -- DW-87
- [x] `NotificationJobCancelIntegrationTest.java` or NEW -- add test: insert SENT job with OPEN alert → returned by query; insert SENT job with ACKNOWLEDGED alert → NOT returned -- DW-83

**Acceptance Criteria:**
- Given a PENDING notification job, when `dispatch()` is called, then the WAHA `send()` call happens outside any DB transaction (no active transaction during the network call), and the attempt+job saves within each phase are atomic together.
- Given a SENT notification job whose alert is OPEN, when `findSentJobsDueForEscalation` is called with a cutoff >= sentAt, then the job is returned.
- Given a SENT notification job whose alert is ACKNOWLEDGED or RESOLVED, when `findSentJobsDueForEscalation` is called with a cutoff >= sentAt, then the job is NOT returned.
- Given the existing `EscalationService.escalate()` behavior, when called with a non-OPEN alert, then the job stays SENT (unchanged).

## Spec Change Log

_Empty until first review loopback._

## Review Triage Log

### 2026-08-23 — Review pass
- intent_gap: 0
- bad_spec: 0
- patch: 1 (low)
- defer: 3 (JPQL enum FQCN maintainability; REQUIRED propagation footgun; send-outside-tx rate-limiter acquire partial-state inherent tradeoff)
- reject: 7 (inner join orphan — FK prevents orphans; duplicate guard — by design; atomicity contract change — intended; isCircuitOpen double — pre-existing; test determinism containsExactly — self-contained seed; TOCTOU — service re-checks; detached entity version — same semantics as before)
- addressed_findings:
  - `low` `patch` DW-83 test: added `sentJobsForResolvedAlertsAreExcludedFromEscalation` to cover RESOLVED status, matching AC wording (ACKNOWLEDGED or RESOLVED)

## Design Notes

- **DW-87 transaction boundary**: The original code holds `@Transactional` across the entire `dispatch()` method including the WAHA `send()` call. After refactor, each DB write phase is wrapped in its own short transaction so the WAHA call (up to 5s) doesn't occupy a DB connection. The `PlatformTransactionManager` mock in the test returns null for `getTransaction` (default Mockito behavior), and `TransactionTemplate.executeWithoutResult` handles null status safely — the `TransactionStatus` is never used by the callback (it just calls `repository.save(...)`), and `commit`/`rollback` on a null-status mock are no-ops. All 9 existing behavior tests remain green.
- **DW-83 query scope**: The existing `EscalationService.escalate()` already guards against non-OPEN alerts (returns without mutating, keeping the job SENT for reopen tracking). The query change prevents the `EscalationWorker` from re-selecting those jobs every 60s, stopping the infinite loop. The job stays SENT — the only behavioral change is the elimination of the no-op polling cycle.
- **DW-83 test seeding**: The `NotificationJobCancelIntegrationTest` already has the full FK chain (`seedAlertForNotificationJob`). Extend it with a DW-83 test, or create a focused `@DataJpaTest` + Testcontainers test. Follow the existing pattern.

## Verification

**Commands:**
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="NotificationDispatchServiceTest"` -- expected: 9/9 pass (existing + new PlatformTransactionManager mock)
- `mvn -f syncro/apps/backend/pom.xml test -Dtest="NotificationJobCancelIntegrationTest"` or the DW-83 test -- expected: DW-83 test passes
- `mvn -f syncro/apps/backend/pom.xml test-compile` -- expected: no compilation errors

**Manual checks (if no CLI):**
- Inspect `NotificationDispatchService.java` for `@Transactional` removed, `TransactionTemplate` injected, and each DB write phase wrapped in `executeWithoutResult`.
- Inspect `NotificationJobRepository.java` for the `findSentJobsDueForEscalation` JPQL with the `join SparepartAlertEntity` and `a.status = OPEN` filter.

## Auto Run Result

**Status:** done

**Summary:** Bundled two open deferred-work items in notification dispatch reliability: (DW-87) removed `@Transactional` from `NotificationDispatchService.dispatch()` and injected a `TransactionTemplate` built from the injected `PlatformTransactionManager`; each of the four DB write phases (rate-limited mark, template-render-failure attempt+exhausted, success attempt+sent, failure attempt+circuit-open/attempt-failed) is now wrapped in its own short `executeWithoutResult(...)` transaction so the WAHA HTTP call (up to 5s) no longer holds a pooled DB connection; (DW-83) rewrote `findSentJobsDueForEscalation` JPQL to join `SparepartAlertEntity` on `a.id = j.alertId` filtering `a.status = OPEN`, so SENT jobs whose alert is ACKNOWLEDGED/RESOLVED are never re-polled by the escalation worker (the job stays SENT by design for reopen tracking; `EscalationService.escalate()` unchanged per AC10).

**Files changed:**
- `NotificationDispatchService.java` — `@Transactional` removed; `TransactionTemplate` injected via constructor; 4 DB write phases wrapped in short transactions (DW-87)
- `NotificationJobRepository.java` — `findSentJobsDueForEscalation` joins alerts, filters OPEN only (DW-83)
- `NotificationDispatchServiceTest.java` — added `@Mock PlatformTransactionManager`; all existing tests unchanged and green (DW-87)
- `NotificationJobCancelIntegrationTest.java` — DW-83 tests: OPEN-alert job returned; ACKNOWLEDGED excluded; RESOLVED excluded (DW-83)

**Review findings breakdown:** 0 intent_gap, 0 bad_spec, 1 patch applied (RESOLVED-status test coverage to match AC wording "ACKNOWLEDGED or RESOLVED"), 3 deferrals (JPQL enum FQCN maintainability; TransactionTemplate REQUIRED-propagation footgun if a future caller wraps dispatch in @Transactional; rateLimiter.acquire-before-persist partial-state window inherent to send-outside-tx design), 7 rejected as noise (FK prevents orphaned jobs so inner join drops nothing; duplicate guard is intended defense-in-depth; atomicity-contract change is the intent of DW-87; pre-existing double isCircuitOpen; self-contained test seed; TOCTOU guarded by EscalationService re-read; detached-entity version semantics unchanged from before).

**Follow-up review recommendation:** false — single low-severity patch (test-only, localized); verification green.

**Verification performed:**
- `mvn test-compile` PASS — no compilation errors
- `NotificationDispatchServiceTest` (8/8) PASS — TransactionTemplate mock path, all behavior assertions unchanged
- `NotificationJobCancelIntegrationTest` (6/6) PASS — DW-83 OPEN returned / ACKNOWLEDGED / RESOLVED excluded; Hibernate SQL confirms `join sparepart_alerts ... sae1_0.status='OPEN'`

**Residual risks:** WAHA send followed by persist-tx rollback can still produce a duplicate WhatsApp message after the dedup window expires (pre-existing at-least-once tradeoff, unchanged ordering); if a future caller adds `@Transactional` around `dispatch()`, REQUIRED propagation would re-hold the connection across the send — documented footgun, not currently reachable (`NotificationWorker.poll()` is not transactional).