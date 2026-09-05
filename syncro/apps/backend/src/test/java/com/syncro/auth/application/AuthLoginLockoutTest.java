package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.AuthService.AccountLockedException;
import com.syncro.auth.application.AuthService.BadCredentialsException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthLoginLockoutTest {

  @Mock
  private AuthUserRepository users;
  @Mock
  private PasswordEncoder passwordEncoder;
  @Mock
  private JwtTokenService tokens;
  @Mock
  private AuditLogWriter auditLog;
  @Mock
  private AuthLoginAuditService loginAudits;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void successfulLoginResetsFailures() {
    var user = user();
    user.recordLoginFailure(5); // 1 prior failure
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("correct", "hash")).thenReturn(true);
    when(tokens.createToken(any())).thenReturn("tok");
    when(tokens.expiresInSeconds()).thenReturn(3600L);

    var result = new AuthService(users, passwordEncoder, tokens, auditLog, loginAudits, clock)
        .login("test@syncro.dev", "correct", "10.0.0.1", "JUnit");

    assertThat(result.accessToken()).isEqualTo("tok");
    assertThat(user.getFailedLoginAttempts()).isZero();
    assertThat(user.getLockedAt()).isNull();
  }

  @Test
  void failedLoginIncrementsCounter() {
    var user = user();
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> new AuthService(users, passwordEncoder, tokens, auditLog, loginAudits, clock)
        .login("test@syncro.dev", "wrong", "10.0.0.1", "JUnit"))
        .isInstanceOf(BadCredentialsException.class);

    assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
  }

  @Test
  void fifthFailureLocksAccount() {
    var user = user();
    // 4 prior failures (not yet locked)
    for (int i = 0; i < 4; i++) {
      user.recordLoginFailure(5);
    }
    assertThat(user.getLockedAt()).isNull();

    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> new AuthService(users, passwordEncoder, tokens, auditLog, loginAudits, clock)
        .login("test@syncro.dev", "wrong", "10.0.0.1", "JUnit"))
        .isInstanceOf(BadCredentialsException.class);

    assertThat(user.getFailedLoginAttempts()).isEqualTo(5);
    assertThat(user.getLockedAt()).isNotNull();
  }

  @Test
  void lockedAccountRejectsEvenCorrectPassword() {
    var user = user();
    // drive to lockout state directly
    for (int i = 0; i < 5; i++) {
      user.recordLoginFailure(5);
    }
    assertThat(user.getLockedAt()).isNotNull();

    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> new AuthService(users, passwordEncoder, tokens, auditLog, loginAudits, clock)
        .login("test@syncro.dev", "correct", "10.0.0.1", "JUnit"))
        .isInstanceOf(AccountLockedException.class);
  }

  private AuthUserEntity user() {
    return new AuthUserEntity(UUID.randomUUID(), "test@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true,
        Instant.now(clock), Instant.now(clock));
  }
}