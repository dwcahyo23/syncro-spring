package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.UserSignatureService.SignaturePasswordOutcome;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

/**
 * Leader-signature spec: the 3-strike lockout is only real if the failed-attempt counter
 * COMMITS through the controller's 401 throw. Unit tests observe in-memory entities and
 * the controller test mocks the service — neither can catch a lost commit. This test runs
 * the service on a real PostgreSQL (Testcontainers) and asserts the auth_users V22
 * columns across transactions: wrong passwords persist, the third arms the 15-minute
 * block (423), the window expiring + correct password resets both.
 */
class UserSignatureLockoutIntegrationTest extends AbstractPostgresIntegrationTest {

  @MockitoBean
  private ObjectStorageService objectStorage;
  @MockitoBean
  private PasswordEncoder passwordEncoder;

  @Autowired
  private UserSignatureService userSignaturesService;
  @Autowired
  private JdbcTemplate jdbc;

  @Test
  @DisplayName("LEAD-PW-INT-001 P0 three wrong passwords persist on auth_users and the block engages; expiry + correct password resets")
  void wrongPasswordsPersistAndBlock() {
    var userId = UUID.randomUUID();
    jdbc.update("INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled,"
            + " created_at, updated_at) VALUES (?::uuid, ?, 'hash', 'SECTION_LEADER', TRUE, NOW(), NOW())",
        userId, "lockout-" + userId + "@syncro.test");
    // End the test-managed transaction so service transactions see committed rows.
    TestTransaction.flagForCommit();
    TestTransaction.end();

    when(passwordEncoder.matches(eq("wrong"), anyString())).thenReturn(false);
    when(passwordEncoder.matches(eq("right"), anyString())).thenReturn(true);

    // First-time saver (NO user_signatures row): the frozen AC demands counting anyway.
    for (int i = 1; i <= 3; i++) {
      assertThat(userSignaturesService.checkSignaturePassword(userId, "wrong"))
          .as("attempt " + i)
          .isEqualTo(SignaturePasswordOutcome.INVALID);
    }

    // Committed across transactions: 3rd strike reset the counter and armed the block.
    var row = jdbc.queryForMap(
        "SELECT signature_failed_attempts, signature_blocked_until FROM auth_users WHERE id = ?::uuid", userId);
    assertThat(((Number) row.get("signature_failed_attempts")).intValue()).isZero();
    assertThat(((java.sql.Timestamp) row.get("signature_blocked_until")).toInstant()).isAfter(Instant.now());

    // Fourth attempt answers BLOCKED before any password check.
    assertThat(userSignaturesService.checkSignaturePassword(userId, "right"))
        .isEqualTo(SignaturePasswordOutcome.BLOCKED);

    // Block window passes; the correct password now verifies OK and clears both columns.
    jdbc.update("UPDATE auth_users SET signature_blocked_until = NOW() - INTERVAL '1 minute' WHERE id = ?::uuid",
        userId);
    assertThat(userSignaturesService.checkSignaturePassword(userId, "right"))
        .isEqualTo(SignaturePasswordOutcome.OK);
    var reset = jdbc.queryForMap(
        "SELECT signature_failed_attempts, signature_blocked_until FROM auth_users WHERE id = ?::uuid", userId);
    assertThat(((Number) reset.get("signature_failed_attempts")).intValue()).isZero();
    assertThat(reset.get("signature_blocked_until")).isNull();
  }
}
