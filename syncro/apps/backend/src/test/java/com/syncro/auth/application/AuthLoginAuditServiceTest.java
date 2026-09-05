package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.api.AuthDtos.LoginAuditListResponse;
import com.syncro.auth.application.AuthLoginAuditService.AuditReadForbiddenException;
import com.syncro.auth.application.AuthLoginAuditService.LoginAuditNotFoundException;
import com.syncro.auth.application.AuthService.AccountLockedException;
import com.syncro.auth.application.AuthService.BadCredentialsException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthLoginAuditEntity;
import com.syncro.auth.infrastructure.AuthLoginAuditRepository;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Story 22-2 AC1/AC2 unit coverage: every login outcome writes exactly one audit row
 * with the right result (incl. unknown user with null userId), and audit reads are
 * SUPER_ADMIN/AUDITOR-only.
 */
@ExtendWith(MockitoExtension.class)
class AuthLoginAuditServiceTest {

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
  @Mock
  private AuthLoginAuditRepository audits;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);

  // -- login audit-per-attempt matrix (AC1) --------------------------------------

  @Test
  void successfulLoginAuditsOneRow() {
    var user = user();
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("correct", "hash")).thenReturn(true);
    when(tokens.createToken(any())).thenReturn("tok");
    when(tokens.expiresInSeconds()).thenReturn(3600L);

    authService().login("test@syncro.dev", "correct", "10.0.0.1", "JUnit-Agent");

    verify(loginAudits).recordAttempt(user.getId(), "test@syncro.dev", "10.0.0.1", "JUnit-Agent", true, null);
  }

  @Test
  void badPasswordAuditsOneFailedRow() {
    var user = user();
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);

    assertThatThrownBy(() -> authService().login("test@syncro.dev", "wrong", "10.0.0.2", "JUnit-Agent"))
        .isInstanceOf(BadCredentialsException.class);

    verify(loginAudits).recordAttempt(user.getId(), "test@syncro.dev", "10.0.0.2", "JUnit-Agent", false,
        "INVALID_CREDENTIALS");
  }

  @Test
  void unknownUserAuditsWithNullUserId() {
    when(users.findByLoginIdentifierIgnoreCase("ghost@syncro.dev")).thenReturn(Optional.empty());
    when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

    assertThatThrownBy(() -> authService().login("ghost@syncro.dev", "whatever", "10.0.0.3", "JUnit-Agent"))
        .isInstanceOf(BadCredentialsException.class);

    verify(loginAudits).recordAttempt(null, "ghost@syncro.dev", "10.0.0.3", "JUnit-Agent", false,
        "INVALID_CREDENTIALS");
    verify(users, never()).save(any());
  }

  @Test
  void lockedAccountAuditsOneFailedRow() {
    var user = user();
    for (int i = 0; i < 5; i++) {
      user.recordLoginFailure(5);
    }
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> authService().login("test@syncro.dev", "correct", "10.0.0.4", "JUnit-Agent"))
        .isInstanceOf(AccountLockedException.class);

    verify(loginAudits).recordAttempt(user.getId(), "test@syncro.dev", "10.0.0.4", "JUnit-Agent", false,
        "ACCOUNT_LOCKED");
  }

  // -- read gates (AC2) -----------------------------------------------------------

  @Test
  void technicianReadIsForbidden() {
    var service = auditService();
    assertThatThrownBy(() -> service.list(actor(ApplicationRole.TECHNICIAN), null, null, null, null, 0, 20))
        .isInstanceOf(AuditReadForbiddenException.class);
    verify(audits, never()).search(any(), any(), any(), any(), any(Pageable.class));
  }

  @Test
  void auditorReadIsAllowed() {
    var audit = new AuthLoginAuditEntity(UUID.randomUUID(), UUID.randomUUID(), "tech@syncro.dev",
        "10.0.0.9", "JUnit-Agent", false, "INVALID_CREDENTIALS", Instant.parse("2026-09-04T12:00:00Z"));
    when(audits.search(isNull(), isNull(), any(Instant.class), any(Instant.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of(audit)));

    LoginAuditListResponse response = auditService().list(actor(ApplicationRole.AUDITOR), null, null, null, null, 0, 20);

    assertThat(response.items()).hasSize(1);
    assertThat(response.items().get(0).wasSuccess()).isFalse();
    assertThat(response.items().get(0).failureReason()).isEqualTo("INVALID_CREDENTIALS");
  }

  @Test
  void superAdminDetailReadReturnsView() {
    var id = UUID.randomUUID();
    var audit = new AuthLoginAuditEntity(id, null, "ghost@syncro.dev", null, null, false, "INVALID_CREDENTIALS",
        Instant.parse("2026-09-04T12:00:00Z"));
    when(audits.findById(id)).thenReturn(Optional.of(audit));

    var view = auditService().get(actor(ApplicationRole.SUPER_ADMIN), id);

    assertThat(view.id()).isEqualTo(id);
    assertThat(view.userId()).isNull();
  }

  @Test
  void unknownAuditDetailIsNotFound() {
    var id = UUID.randomUUID();
    when(audits.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> auditService().get(actor(ApplicationRole.SUPER_ADMIN), id))
        .isInstanceOf(LoginAuditNotFoundException.class);
  }

  @Test
  void identifierFilterIsEscapedAndLowercased() {
    when(audits.search(anyString(), anyBoolean(), any(Instant.class), any(Instant.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of()));

    auditService().list(actor(ApplicationRole.SUPER_ADMIN), "a%b", true, null, null, 0, 20);

    verify(audits).search(eq("%a\\%b%"), eq(true), any(Instant.class), any(Instant.class), any(Pageable.class));
  }

  // -- review 22-2 P1: audit write must not break login ----------------------------

  @Test
  void auditWriteFailureDoesNotBreakSuccessfulLogin() {
    var user = user();
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("correct", "hash")).thenReturn(true);
    when(tokens.createToken(any())).thenReturn("tok");
    when(tokens.expiresInSeconds()).thenReturn(3600L);
    doThrow(new RuntimeException("audit db down")).when(loginAudits)
        .recordAttempt(any(), anyString(), any(), any(), anyBoolean(), any());

    var result = authService().login("test@syncro.dev", "correct", "10.0.0.1", "JUnit-Agent");

    assertThat(result.accessToken()).isEqualTo("tok");
  }

  @Test
  void auditWriteFailureDoesNotBreakFailedLogin() {
    var user = user();
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(user));
    when(passwordEncoder.matches("wrong", "hash")).thenReturn(false);
    doThrow(new RuntimeException("audit db down")).when(loginAudits)
        .recordAttempt(any(), anyString(), any(), any(), anyBoolean(), any());

    assertThatThrownBy(() -> authService().login("test@syncro.dev", "wrong", "10.0.0.1", "JUnit-Agent"))
        .isInstanceOf(BadCredentialsException.class);
    // Lockout counter still advanced — the audit failure did not abort the login path.
    assertThat(user.getFailedLoginAttempts()).isEqualTo(1);
  }

  // -- review 22-2 P8e: disabled user audits a failed attempt ----------------------

  @Test
  void disabledUserWithCorrectPasswordAuditsFailedRow() {
    var disabled = new AuthUserEntity(UUID.randomUUID(), "test@syncro.dev", "hash", ApplicationRole.TECHNICIAN, false,
        Instant.now(clock), Instant.now(clock));
    when(users.findByLoginIdentifierIgnoreCase("test@syncro.dev")).thenReturn(Optional.of(disabled));
    when(passwordEncoder.matches("correct", "hash")).thenReturn(true);

    assertThatThrownBy(() -> authService().login("test@syncro.dev", "correct", "10.0.0.5", "JUnit-Agent"))
        .isInstanceOf(BadCredentialsException.class);

    verify(loginAudits).recordAttempt(disabled.getId(), "test@syncro.dev", "10.0.0.5", "JUnit-Agent", false,
        "INVALID_CREDENTIALS");
  }

  // -- review 22-2 P8b: truncate caps on the persisted row -------------------------

  @Test
  void recordAttemptTruncatesIdentifierAndIpButNotUserAgent() {
    var longIdentifier = "x".repeat(300);
    var longIp = "9".repeat(80);
    var longAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) long-agent-string-".repeat(80);

    new AuthLoginAuditService(audits, clock).recordAttempt(null, longIdentifier, longIp, longAgent, false,
        "INVALID_CREDENTIALS");

    var captor = ArgumentCaptor.forClass(AuthLoginAuditEntity.class);
    verify(audits).save(captor.capture());
    assertThat(captor.getValue().getIdentifier()).hasSize(255);
    assertThat(captor.getValue().getIpAddress()).hasSize(64);
    // user_agent is a TEXT column — no magic cap (review 22-2 P12).
    assertThat(captor.getValue().getUserAgent()).isEqualTo(longAgent);
  }

  // -- review 22-2 P8c + P9: size clamp + stable sort ------------------------------

  @Test
  void pageSizeClampsAndSortCarriesIdTiebreaker() {
    when(audits.search(any(), any(), any(Instant.class), any(Instant.class), any(Pageable.class)))
        .thenReturn(new PageImpl<>(java.util.List.of()));
    var service = auditService();

    service.list(actor(ApplicationRole.SUPER_ADMIN), null, null, null, null, 0, 0);
    var smallCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(audits).search(any(), any(), any(Instant.class), any(Instant.class), smallCaptor.capture());
    assertThat(smallCaptor.getValue().getPageSize()).isEqualTo(20);

    service.list(actor(ApplicationRole.SUPER_ADMIN), null, null, null, null, 0, 500);
    var bigCaptor = ArgumentCaptor.forClass(Pageable.class);
    verify(audits, org.mockito.Mockito.times(2))
        .search(any(), any(), any(Instant.class), any(Instant.class), bigCaptor.capture());
    assertThat(bigCaptor.getAllValues().get(1).getPageSize()).isEqualTo(100);

    var sort = bigCaptor.getAllValues().get(1).getSort();
    assertThat(sort.getOrderFor("occurredAt")).isNotNull();
    assertThat(sort.getOrderFor("occurredAt").getDirection().name()).isEqualTo("DESC");
    assertThat(sort.getOrderFor("id")).isNotNull();
    assertThat(sort.getOrderFor("id").getDirection().name()).isEqualTo("DESC");
  }

  private AuthService authService() {
    return new AuthService(users, passwordEncoder, tokens, auditLog, loginAudits, clock);
  }

  private AuthLoginAuditService auditService() {
    return new AuthLoginAuditService(audits, clock);
  }

  private static AuthenticatedUser actor(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private AuthUserEntity user() {
    return new AuthUserEntity(UUID.randomUUID(), "test@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true,
        Instant.now(clock), Instant.now(clock));
  }
}
