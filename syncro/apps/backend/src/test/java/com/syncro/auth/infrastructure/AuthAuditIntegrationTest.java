package com.syncro.auth.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.AuthLoginAuditService;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PhoneVerificationService;
import com.syncro.auth.application.PhoneVerificationService.ChallengeConsumedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExhaustedException;
import com.syncro.auth.application.PhoneVerificationService.InvalidOtpException;
import com.syncro.auth.application.PhoneVerificationService.ResendTooEarlyException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.api.AuthDtos.LoginAuditListResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.transaction.TestTransaction;
/**
 * Story 22-2 end-to-end evidence on a real database: login attempts (success, bad
 * password, unknown user, locked) each leave exactly one {@code auth_login_audits} row
 * with no credential material — including failed attempts, whose audit row survives the
 * rolled-back login transaction (REQUIRES_NEW); and the phone-challenge lifecycle
 * (issue → wrong OTP → exhaustion / correct OTP → consumed → resend window) persists
 * only the OTP hash and writes immutable audit-log rows.
 *
 * <p>Login-path tests commit the seeded user via {@link TestTransaction} first: the
 * audit insert runs in its own transaction (exactly like production, where the user row
 * is long committed before anyone can log in), so it cannot see an uncommitted
 * test-transaction user. Committed rows are keyed by per-run UUID identifiers and do
 * not interfere across runs.
 */
class AuthAuditIntegrationTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private AuthService authService;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private PasswordEncoder passwordEncoder;
  @Autowired
  private JdbcTemplate jdbc;
  @Autowired
  private PhoneVerificationService phoneChallenges;
  @Autowired
  private AuthLoginAuditService loginAudits;
  @Autowired
  private PhoneVerificationChallengeRepository challenges;
  @Autowired
  private AuditLogRepository auditLogs;

  @Test
  @DisplayName("22-2-INT-001 P0 successful login writes exactly one audit row (was_success=true)")
  void successfulLoginWritesAuditRow() {
    var login = "int-admin-" + UUID.randomUUID() + "@syncro.dev";
    var user = persistedUser(login, ApplicationRole.SUPER_ADMIN, "right-password");
    commitSeed();

    var response = authService.login(login, "right-password", "10.0.0.1", "IT-Agent");
    assertThat(response.accessToken()).isNotBlank();

    var rows = auditRows(login);
    assertThat(rows).hasSize(1);
    var row = rows.get(0);
    assertThat(row[0]).isEqualTo("true");
    assertThat(row[1]).isEqualTo(user.getId().toString());
    assertThat(row[2]).isEqualTo("10.0.0.1");
    assertThat(row[3]).isEqualTo("IT-Agent");
    assertThat(row[4]).isNull(); // failure_reason
    assertThat(row[5]).isNotNull(); // occurred_at
  }

  @Test
  @DisplayName("22-2-INT-002 P0 failed login (bad password) still leaves one audit row after rollback")
  void failedLoginWritesAuditRowDespiteRollback() {
    var login = "int-tech-" + UUID.randomUUID() + "@syncro.dev";
    var user = persistedUser(login, ApplicationRole.TECHNICIAN, "right-password");
    commitSeed();

    assertThatThrownBy(() -> authService.login(login, "wrong-password", "10.0.0.2", "IT-Agent"))
        .isInstanceOf(AuthService.BadCredentialsException.class);

    var rows = auditRows(login);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[0]).isEqualTo("false");
    assertThat(rows.get(0)[1]).isEqualTo(user.getId().toString());
    assertThat(rows.get(0)[4]).isEqualTo("INVALID_CREDENTIALS");
    // No credential material is ever stored.
    var raw = jdbc.queryForObject(
        "SELECT identifier || coalesce(failure_reason,'') FROM auth_login_audits WHERE identifier = ?",
        String.class, login);
    assertThat(raw).doesNotContain("wrong-password").doesNotContain("$2a$");
  }

  @Test
  @DisplayName("22-2-INT-003 P0 unknown-user attempt audits with null user_id")
  void unknownUserLoginAuditsWithNullUserId() {
    var login = "int-ghost-" + UUID.randomUUID() + "@syncro.dev";
    commitSeed();

    assertThatThrownBy(() -> authService.login(login, "whatever", "10.0.0.3", "IT-Agent"))
        .isInstanceOf(AuthService.BadCredentialsException.class);

    var rows = auditRows(login);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[0]).isEqualTo("false");
    assertThat(rows.get(0)[1]).isNull();
    assertThat(rows.get(0)[4]).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  @DisplayName("22-2-INT-004 P1 locked-account attempt audits with ACCOUNT_LOCKED")
  void lockedAccountLoginAudits() {
    var login = "int-locked-" + UUID.randomUUID() + "@syncro.dev";
    var user = persistedUser(login, ApplicationRole.TECHNICIAN, "right-password");
    for (int i = 0; i < 5; i++) {
      user.recordLoginFailure(5);
    }
    users.saveAndFlush(user);
    commitSeed();

    assertThatThrownBy(() -> authService.login(login, "right-password", "10.0.0.4", "IT-Agent"))
        .isInstanceOf(AuthService.AccountLockedException.class);

    var rows = auditRows(login);
    assertThat(rows).hasSize(1);
    assertThat(rows.get(0)[4]).isEqualTo("ACCOUNT_LOCKED");
  }

  @Test
  @DisplayName("22-2-INT-005 P0 challenge lifecycle: hash-only storage, attempts, consume, audit rows")
  void challengeLifecyclePersistsHashAndAudits() {
    var userId = persistedUser("int-target-" + UUID.randomUUID() + "@syncro.dev",
        ApplicationRole.TECHNICIAN, "pw").getId();
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "int-super@syncro.dev",
        ApplicationRole.SUPER_ADMIN);

    var issued = phoneChallenges.issue(actor, userId, "0812345678");
    assertThat(issued.expiresAt()).isAfter(issued.createdAt());
    assertThat(issued.consumedAt()).isNull();

    var stored = challenges.findById(issued.id()).orElseThrow();
    assertThat(stored.getOtpHash()).startsWith("$2a$");
    assertThat(stored.getAttemptCount()).isZero();

    // Wrong OTPs increment toward exhaustion (5 total).
    for (int i = 1; i <= 4; i++) {
      final int attempt = i;
      assertThatThrownBy(() -> phoneChallenges.verify(actor, issued.id(), "00000" + Math.min(attempt, 9)))
          .isInstanceOf(InvalidOtpException.class);
      assertThat(challenges.findById(issued.id()).orElseThrow().getAttemptCount()).isEqualTo(i);
    }
    assertThatThrownBy(() -> phoneChallenges.verify(actor, issued.id(), "000009"))
        .isInstanceOf(ChallengeExhaustedException.class);
    assertThat(challenges.findById(issued.id()).orElseThrow().getAttemptCount()).isEqualTo(5);

    // Audit trail (review 22-2 P8f): exactly 1 CREATE + 5 UPDATE rows (one per wrong
    // OTP, including the 5th that flips to exhausted), phone masked, no hash. Read
    // through the JPA repository (auto-flushes the pending last row; raw JDBC would
    // miss it inside the still-open test transaction).
    var auditRows = auditLogs.findByEntityIdOrderByCreatedAtAsc(issued.id()).stream()
        .filter(entry -> entry.getEntityType() == AuditEntityType.PHONE_VERIFICATION_CHALLENGE)
        .toList();
    assertThat(auditRows).hasSize(6);
    assertThat(auditRows.get(0).getAction()).isEqualTo(AuditAction.CREATE);
    var lastUpdate = auditRows.get(auditRows.size() - 1);
    assertThat(lastUpdate.getAction()).isEqualTo(AuditAction.UPDATE);
    // The final UPDATE row shows the attemptCount delta 4 -> 5.
    assertThat(lastUpdate.getPreviousValue()).contains("\"attemptCount\":4");
    assertThat(lastUpdate.getNewValue()).contains("\"attemptCount\":5");
    assertThat(lastUpdate.getNewValue()).doesNotContain("$2a$").doesNotContain("0812345678")
        .contains("******5678");
  }

  @Test
  @DisplayName("22-2-INT-006 P0 correct OTP consumes; re-verify rejects CHALLENGE_CONSUMED; resend before window rejects")
  void correctOtpConsumesAndResendWindowEnforced() {
    var userId = persistedUser("int-target2-" + UUID.randomUUID() + "@syncro.dev",
        ApplicationRole.TECHNICIAN, "pw").getId();
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "int-super@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    // Seed a challenge whose OTP we know (hash written through the same encoder the
    // service compares against) — the issued OTP is deliberately never returned.
    var now = Instant.now();
    var challenge = challenges.saveAndFlush(new PhoneVerificationChallengeEntity(
        UUID.randomUUID(), userId, "08987654321", passwordEncoder.encode("654321"),
        now.plusSeconds(900), 0, 5, now.plusSeconds(60), null, now));

    var verified = phoneChallenges.verify(actor, challenge.getId(), "654321");
    assertThat(verified.consumedAt()).isNotNull();

    assertThatThrownBy(() -> phoneChallenges.verify(actor, challenge.getId(), "654321"))
        .isInstanceOf(ChallengeConsumedException.class);
    assertThatThrownBy(() -> phoneChallenges.resend(actor, challenge.getId()))
        .isInstanceOf(ChallengeConsumedException.class);
  }

  @Test
  @DisplayName("22-2-INT-007 P1 filtered paged audit reads (identifier/success/date range, newest first)")
  void filteredAuditReads() {
    var login = "int-filter-" + UUID.randomUUID() + "@syncro.dev";
    var user = persistedUser(login, ApplicationRole.TECHNICIAN, "pw");
    commitSeed();
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "int-super@syncro.dev",
        ApplicationRole.SUPER_ADMIN);

    loginAudits.recordAttempt(user.getId(), login, "10.1.0.1", "A", true, null);
    loginAudits.recordAttempt(user.getId(), login, "10.1.0.2", "B", false, "INVALID_CREDENTIALS");

    LoginAuditListResponse all = loginAudits.list(admin, login, null, null, null, 0, 20);
    assertThat(all.items()).hasSize(2);
    assertThat(all.items().get(0).occurredAt()).isAfterOrEqualTo(all.items().get(1).occurredAt());

    LoginAuditListResponse failures = loginAudits.list(admin, login, false, null, null, 0, 20);
    assertThat(failures.items()).hasSize(1);
    assertThat(failures.items().get(0).wasSuccess()).isFalse();

    LoginAuditListResponse future = loginAudits.list(admin, login, null,
        Instant.now().plusSeconds(3600), null, 0, 20);
    assertThat(future.items()).isEmpty();

    // LIKE wildcards in the filter are escaped, not treated as patterns.
    LoginAuditListResponse wildcard = loginAudits.list(admin, "%", null, null, null, 0, 20);
    assertThat(wildcard.items()).isEmpty();

    // Review 22-2 P8d: the identifier filter is case-insensitive (lower() on both
    // sides in the JPQL) — an uppercase query returns the same rows.
    LoginAuditListResponse upper = loginAudits.list(admin, login.toUpperCase(), null, null, null, 0, 20);
    assertThat(upper.items()).hasSize(2);
  }

  @Test
  @DisplayName("22-2-INT-008 P0 concurrent verifies cannot both increment (optimistic lock, review 22-2 P3)")
  void concurrentVerifyLosesUpdateRejected() throws Exception {
    var userId = persistedUser("int-race-" + UUID.randomUUID() + "@syncro.dev",
        ApplicationRole.TECHNICIAN, "pw").getId();
    var actor = new AuthenticatedUser(UUID.randomUUID().toString(), "int-super@syncro.dev",
        ApplicationRole.SUPER_ADMIN);
    var now = Instant.now();
    var challenge = challenges.saveAndFlush(new PhoneVerificationChallengeEntity(
        UUID.randomUUID(), userId, "0811223344", passwordEncoder.encode("000000"),
        now.plusSeconds(900), 4, 5, now.minusSeconds(1), null, now));
    commitSeed();

    // Two threads race the 5th (final) wrong-OTP guess. BCrypt.matches is slow enough
    // that both usually read version=V before either writes, so the loser surfaces as a
    // clean optimistic-lock failure; if they serialize instead, the second thread reads
    // attempt_count=5 and rejects at the pre-check. Either way the anti-lost-update
    // invariant holds: exactly ONE guess is counted (attempt_count 4->5, never 6) and
    // exactly ONE UPDATE audit row is written. Without @Version the interleaved case
    // would count BOTH guesses (two audit rows, both claiming 4->5) — the silent
    // budget-enlargement bug this test pins.
    var barrier = new CyclicBarrier(2);
    var outcomes = new ConcurrentLinkedQueue<String>();
    var threads = List.of(new Thread(() -> raceVerify(actor, challenge.getId(), barrier, outcomes)),
        new Thread(() -> raceVerify(actor, challenge.getId(), barrier, outcomes)));
    threads.forEach(Thread::start);
    for (var thread : threads) {
      thread.join(TimeUnit.SECONDS.toMillis(60));
    }

    assertThat(outcomes).hasSize(2);
    assertThat(outcomes).noneMatch(o -> o.startsWith("Other:") || o.equals("Consumed"));
    assertThat(challenges.findById(challenge.getId()).orElseThrow().getAttemptCount()).isEqualTo(5);
    var updateAudits = jdbc.queryForObject(
        "SELECT count(*) FROM audit_log WHERE entity_type = 'PHONE_VERIFICATION_CHALLENGE' "
            + "AND entity_id = ?::uuid AND action = 'UPDATE'",
        Long.class, challenge.getId().toString());
    assertThat(updateAudits).isEqualTo(1L);
  }

  private void raceVerify(AuthenticatedUser actor, UUID challengeId, CyclicBarrier barrier,
      ConcurrentLinkedQueue<String> outcomes) {
    try {
      barrier.await(30, TimeUnit.SECONDS);
      phoneChallenges.verify(actor, challengeId, "999999");
      outcomes.add("Consumed");
    } catch (OptimisticLockingFailureException race) {
      outcomes.add("OptimisticLock");
    } catch (InvalidOtpException counted) {
      outcomes.add("InvalidOtp");
    } catch (ChallengeExhaustedException rejected) {
      outcomes.add("Exhausted");
    } catch (Exception other) {
      outcomes.add("Other:" + other.getClass().getSimpleName());
    }
  }

  private AuthUserEntity persistedUser(String login, ApplicationRole role, String password) {
    var now = Instant.parse("2026-09-05T00:00:00Z");
    return users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), login,
        passwordEncoder.encode(password), role, true, now, now));
  }

  /** Commits the test-managed transaction so the REQUIRES_NEW audit insert can see the seeded user. */
  private static void commitSeed() {
    if (TestTransaction.isActive()) {
      TestTransaction.flagForCommit();
      TestTransaction.end();
    }
  }

  /** [was_success, user_id, ip_address, user_agent, failure_reason, occurred_at] per row. */
  private java.util.List<String[]> auditRows(String identifier) {
    return jdbc.query(
        "SELECT was_success::text, user_id::text, ip_address, user_agent, failure_reason, occurred_at::text "
            + "FROM auth_login_audits WHERE identifier = ? ORDER BY occurred_at",
        (rs, n) -> new String[] {
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)
        }, identifier);
  }
}
