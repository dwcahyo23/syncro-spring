package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.PhoneVerificationService.ChallengeConsumedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExhaustedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExpiredException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeNotFoundException;
import com.syncro.auth.application.PhoneVerificationService.InvalidOtpException;
import com.syncro.auth.application.PhoneVerificationService.PhoneChallengeForbiddenException;
import com.syncro.auth.application.PhoneVerificationService.ResendTooEarlyException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeEntity;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeRepository;
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
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Story 22-2 AC3 unit coverage: issue/verify/resend lifecycle — only the OTP hash is
 * stored, wrong OTPs increment toward exhaustion, expired/exhausted/consumed/unknown
 * challenges reject with stable codes, resend respects the window, mutations are
 * SUPER_ADMIN-only and audit-logged with masked phone values.
 */
@ExtendWith(MockitoExtension.class)
class PhoneVerificationServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

  @Mock
  private PhoneVerificationChallengeRepository challenges;
  @Mock
  private AuthUserRepository users;
  @Mock
  private PasswordEncoder passwordEncoder;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  // -- issue ---------------------------------------------------------------------

  @Test
  void issueStoresOnlyHashAndAuditsCreate() {
    var userId = UUID.randomUUID();
    when(users.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$fakehash");
    when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service().issue(superAdmin(), userId, "0812345678");

    assertThat(view.id()).isNotNull();
    assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
    assertThat(view.attemptCount()).isZero();
    assertThat(view.maxAttempts()).isEqualTo(5);
    assertThat(view.consumedAt()).isNull();

    var saved = captureSaved();
    assertThat(saved.getOtpHash()).isEqualTo("$2a$10$fakehash");
    // The plain OTP is never persisted — the stored hash is the encoder output only.
    assertThat(saved.getPendingPhone()).isEqualTo("0812345678");

    var record = captureAudit();
    assertThat(record.action()).isEqualTo(AuditAction.CREATE);
    assertThat(record.entityType()).isEqualTo(AuditEntityType.PHONE_VERIFICATION_CHALLENGE);
    assertThat(record.newValue()).containsEntry("pendingPhone", "******5678");
    assertThat(record.newValue()).doesNotContainKey("otpHash");
  }

  @Test
  void issueUnknownUserThrowsNotFound() {
    var userId = UUID.randomUUID();
    when(users.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().issue(superAdmin(), userId, "0812345678"))
        .isInstanceOf(AuthService.UserNotFoundException.class);
    verify(challenges, never()).saveAndFlush(any());
  }

  @Test
  void issueByNonAdminIsForbidden() {
    assertThatThrownBy(() -> service().issue(actor(ApplicationRole.AUDITOR), UUID.randomUUID(), "0812345678"))
        .isInstanceOf(PhoneChallengeForbiddenException.class);
  }

  // -- verify --------------------------------------------------------------------

  @Test
  void verifyCorrectOtpConsumesAndStamps() {
    var challenge = challenge(NOW.plusSeconds(600), 0);
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));
    when(passwordEncoder.matches("123456", "stored-hash")).thenReturn(true);
    when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service().verify(superAdmin(), challenge.getId(), "123456");

    assertThat(view.consumedAt()).isEqualTo(NOW);
    assertThat(view.attemptCount()).isZero();
    verify(auditLog).record(any(), any());
  }

  @Test
  void verifyWrongOtpIncrementsAttempt() {
    var challenge = challenge(NOW.plusSeconds(600), 1);
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));
    when(passwordEncoder.matches("000000", "stored-hash")).thenReturn(false);
    when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> service().verify(superAdmin(), challenge.getId(), "000000"))
        .isInstanceOf(InvalidOtpException.class);

    assertThat(challenge.getAttemptCount()).isEqualTo(2);
    assertThat(challenge.getConsumedAt()).isNull();
    // The failed attempt is itself a mutation — audited with previous/new values.
    verify(auditLog).record(any(), any());
  }

  @Test
  void verifyFifthWrongOtpExhausts() {
    var challenge = challenge(NOW.plusSeconds(600), 4);
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));
    when(passwordEncoder.matches("000000", "stored-hash")).thenReturn(false);
    when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> service().verify(superAdmin(), challenge.getId(), "000000"))
        .isInstanceOf(ChallengeExhaustedException.class);

    assertThat(challenge.getAttemptCount()).isEqualTo(5);
  }

  @Test
  void verifyAlreadyExhaustedRejectsBeforeHashCompare() {
    var challenge = challenge(NOW.plusSeconds(600), 5);
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().verify(superAdmin(), challenge.getId(), "123456"))
        .isInstanceOf(ChallengeExhaustedException.class);
    verify(passwordEncoder, never()).matches(anyString(), anyString());
  }

  @Test
  void verifyExpiredRejects() {
    var challenge = challenge(NOW.minusSeconds(1), 0);
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().verify(superAdmin(), challenge.getId(), "123456"))
        .isInstanceOf(ChallengeExpiredException.class);
  }

  @Test
  void verifyConsumedRejects() {
    var challenge = challenge(NOW.plusSeconds(600), 0);
    challenge.consume(NOW.minusSeconds(30));
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().verify(superAdmin(), challenge.getId(), "123456"))
        .isInstanceOf(ChallengeConsumedException.class);
  }

  @Test
  void verifyUnknownChallengeThrowsNotFound() {
    var id = UUID.randomUUID();
    when(challenges.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().verify(superAdmin(), id, "123456"))
        .isInstanceOf(ChallengeNotFoundException.class);
  }

  // -- resend --------------------------------------------------------------------

  @Test
  void resendBeforeWindowRejects() {
    var challenge = challenge(NOW.plusSeconds(600), 0); // resendAvailableAt = NOW+60s
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().resend(superAdmin(), challenge.getId()))
        .isInstanceOf(ResendTooEarlyException.class);
  }

  @Test
  void resendAfterWindowRenewsChallenge() {
    var challenge = new PhoneVerificationChallengeEntity(UUID.randomUUID(), UUID.randomUUID(), "0812345678",
        "stored-hash", NOW.plusSeconds(600), 3, 5, NOW.minusSeconds(1), null, NOW.minusSeconds(120));
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));
    when(passwordEncoder.encode(anyString())).thenReturn("$2a$10$newhash");
    when(challenges.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var view = service().resend(superAdmin(), challenge.getId());

    assertThat(view.attemptCount()).isZero();
    assertThat(view.expiresAt()).isEqualTo(NOW.plusSeconds(15 * 60));
    assertThat(challenge.getOtpHash()).isEqualTo("$2a$10$newhash");
    verify(auditLog).record(any(), any());
  }

  @Test
  void resendConsumedRejects() {
    var challenge = challenge(NOW.plusSeconds(600), 0);
    challenge.consume(NOW.minusSeconds(30));
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().resend(superAdmin(), challenge.getId()))
        .isInstanceOf(ChallengeConsumedException.class);
  }

  @Test
  void resendExhaustedChallengeRejectsAndDoesNotRevive() {
    // Review 22-2 P2: exhausted-but-unexpired + resend window passed — renew() would
    // reset the attempt budget, so this must reject instead.
    var challenge = new PhoneVerificationChallengeEntity(UUID.randomUUID(), UUID.randomUUID(), "0812345678",
        "stored-hash", NOW.plusSeconds(600), 5, 5, NOW.minusSeconds(1), null, NOW.minusSeconds(120));
    when(challenges.findById(challenge.getId())).thenReturn(Optional.of(challenge));

    assertThatThrownBy(() -> service().resend(superAdmin(), challenge.getId()))
        .isInstanceOf(ChallengeExhaustedException.class);

    assertThat(challenge.getAttemptCount()).isEqualTo(5);
    assertThat(challenge.getOtpHash()).isEqualTo("stored-hash");
    verify(challenges, never()).saveAndFlush(any());
  }

  // -- findActiveForUser (review 22-2 P13: FR-181 selection semantics) --------------

  @Test
  void findActiveForUserReturnsNewestActiveChallenge() {
    var userId = UUID.randomUUID();
    var challenge = challenge(NOW.plusSeconds(600), 1);
    when(challenges.findTopByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, NOW))
        .thenReturn(Optional.of(challenge));

    var view = service().findActiveForUser(userId);

    assertThat(view).isNotNull();
    assertThat(view.id()).isEqualTo(challenge.getId());
    assertThat(view.attemptCount()).isEqualTo(1);
  }

  @Test
  void findActiveForUserReturnsNullWhenNoneActive() {
    var userId = UUID.randomUUID();
    when(challenges.findTopByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, NOW))
        .thenReturn(Optional.empty());

    assertThat(service().findActiveForUser(userId)).isNull();
  }

  // -- maskPhone branches (review 22-2 P8g) -----------------------------------------

  @Test
  void maskPhoneHandlesNullShortAndNormalValues() {
    assertThat(PhoneVerificationService.maskPhone(null)).isEqualTo("****");
    assertThat(PhoneVerificationService.maskPhone("123")).isEqualTo("****");
    assertThat(PhoneVerificationService.maskPhone("12345")).isEqualTo("*2345");
    assertThat(PhoneVerificationService.maskPhone("0812345678")).isEqualTo("******5678");
  }

  // -- helpers ---------------------------------------------------------------------

  private PhoneVerificationService service() {
    return new PhoneVerificationService(challenges, users, passwordEncoder, auditLog, clock);
  }

  private PhoneVerificationChallengeEntity captureSaved() {
    var captor = ArgumentCaptor.forClass(PhoneVerificationChallengeEntity.class);
    verify(challenges).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private AuditRecord captureAudit() {
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    return captor.getValue();
  }

  private static AuthenticatedUser superAdmin() {
    return actor(ApplicationRole.SUPER_ADMIN);
  }

  private static AuthenticatedUser actor(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static AuthUserEntity user(UUID id) {
    return new AuthUserEntity(id, "user@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, NOW, NOW);
  }

  private static PhoneVerificationChallengeEntity challenge(Instant expiresAt, int attempts) {
    return new PhoneVerificationChallengeEntity(UUID.randomUUID(), UUID.randomUUID(), "0812345678",
        "stored-hash", expiresAt, attempts, 5, NOW.plusSeconds(60), null, NOW.minusSeconds(10));
  }
}
