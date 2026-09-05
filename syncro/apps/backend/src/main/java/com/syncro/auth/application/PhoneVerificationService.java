package com.syncro.auth.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.api.AuthDtos.PhoneChallengeView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeEntity;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phone-verification challenge lifecycle (story 22-2, blueprint I3): issue / verify /
 * resend, all SUPER_ADMIN mutations (operator-driven for the 4-hour ack auto-login flow,
 * FR-181 — self-service issuance would need rate limits this story does not define).
 *
 * <p>Only the BCrypt hash of the OTP is ever persisted or audited — the plain code is
 * generated server-side, hashed, and dropped; it is never returned or logged. Verify
 * compares the hash, increments {@code attempt_count} on mismatch, and consumes on
 * success; expired / exhausted / consumed challenges reject with stable 409 codes.
 * Resend renews the same row (new hash, new expiry, attempt budget reset) only after
 * the resend window has passed. Every mutation writes an immutable audit-log row with
 * previous/new values (phone masked — phone identifiers never land in audit values).
 */
@Service
public class PhoneVerificationService {

  static final int MAX_ATTEMPTS = 5;
  static final Duration OTP_TTL = Duration.ofMinutes(15);
  static final Duration RESEND_WINDOW = Duration.ofSeconds(60);

  private final PhoneVerificationChallengeRepository challenges;
  private final AuthUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final AuditLogWriter auditLog;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public PhoneVerificationService(PhoneVerificationChallengeRepository challenges,
      AuthUserRepository users, PasswordEncoder passwordEncoder, AuditLogWriter auditLog, Clock clock) {
    this.challenges = challenges;
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public PhoneChallengeView issue(AuthenticatedUser actor, UUID userId, String phoneNumber) {
    requireSuperAdmin(actor);
    var user = users.findById(userId).orElseThrow(AuthService.UserNotFoundException::new);
    var phone = phoneNumber.trim();
    var now = Instant.now(clock);
    var otp = generateOtp();
    var challenge = challenges.saveAndFlush(new PhoneVerificationChallengeEntity(
        UUID.randomUUID(),
        user.getId(),
        phone,
        passwordEncoder.encode(otp),
        now.plus(OTP_TTL),
        0,
        MAX_ATTEMPTS,
        now.plus(RESEND_WINDOW),
        null,
        now));
    auditLog.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.PHONE_VERIFICATION_CHALLENGE,
        challenge.getId(), challenge.getId().toString(), null, null,
        challengeValues(challenge), null));
    return toView(challenge);
  }

  /**
   * Verifies the delivered OTP. A wrong OTP increments {@code attempt_count} and the
   * increment MUST survive the 409 response (I/O matrix: "attempt_count incremented"),
   * hence {@code noRollbackFor} on the two failure codes that mutate the row — the
   * transaction commits, the exception still maps to 409.
   */
  @Transactional(noRollbackFor = {InvalidOtpException.class, ChallengeExhaustedException.class})
  public PhoneChallengeView verify(AuthenticatedUser actor, UUID challengeId, String otp) {
    requireSuperAdmin(actor);
    var challenge = load(challengeId);
    var previous = challengeValues(challenge);
    var now = Instant.now(clock);
    if (challenge.getConsumedAt() != null) {
      throw new ChallengeConsumedException();
    }
    if (!challenge.getExpiresAt().isAfter(now)) {
      throw new ChallengeExpiredException();
    }
    if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
      throw new ChallengeExhaustedException();
    }
    if (!passwordEncoder.matches(otp, challenge.getOtpHash())) {
      challenge.registerFailedAttempt();
      var failed = challenges.saveAndFlush(challenge);
      auditLog.record(actor, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PHONE_VERIFICATION_CHALLENGE,
          failed.getId(), failed.getId().toString(), null, previous, challengeValues(failed), null));
      if (failed.getAttemptCount() >= failed.getMaxAttempts()) {
        throw new ChallengeExhaustedException();
      }
      throw new InvalidOtpException();
    }
    challenge.consume(now);
    var saved = challenges.saveAndFlush(challenge);
    auditLog.record(actor, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PHONE_VERIFICATION_CHALLENGE,
        saved.getId(), saved.getId().toString(), null, previous, challengeValues(saved), null));
    return toView(saved);
  }

  @Transactional
  public PhoneChallengeView resend(AuthenticatedUser actor, UUID challengeId) {
    requireSuperAdmin(actor);
    var challenge = load(challengeId);
    var previous = challengeValues(challenge);
    var now = Instant.now(clock);
    if (challenge.getConsumedAt() != null) {
      throw new ChallengeConsumedException();
    }
    if (!challenge.getExpiresAt().isAfter(now)) {
      throw new ChallengeExpiredException();
    }
    // Review 22-2 P2: an exhausted-but-unexpired challenge must not be revived —
    // renew() resets the attempt budget, so without this guard the brute-force
    // budget would silently enlarge. Issue a fresh challenge instead.
    if (challenge.getAttemptCount() >= challenge.getMaxAttempts()) {
      throw new ChallengeExhaustedException();
    }
    if (challenge.getResendAvailableAt() != null && now.isBefore(challenge.getResendAvailableAt())) {
      throw new ResendTooEarlyException();
    }
    challenge.renew(passwordEncoder.encode(generateOtp()), now.plus(OTP_TTL), now.plus(RESEND_WINDOW));
    var saved = challenges.saveAndFlush(challenge);
    auditLog.record(actor, new AuditRecord(AuditAction.UPDATE, AuditEntityType.PHONE_VERIFICATION_CHALLENGE,
        saved.getId(), saved.getId().toString(), null, previous, challengeValues(saved), null));
    return toView(saved);
  }

  /** Newest active (unconsumed, unexpired) challenge for a user — FR-181 ack-flow lookup. */
  @Transactional(readOnly = true)
  public PhoneChallengeView findActiveForUser(UUID userId) {
    return challenges
        .findTopByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(userId, Instant.now(clock))
        .map(PhoneVerificationService::toView)
        .orElse(null);
  }

  private PhoneVerificationChallengeEntity load(UUID challengeId) {
    return challenges.findById(challengeId).orElseThrow(ChallengeNotFoundException::new);
  }

  private void requireSuperAdmin(AuthenticatedUser actor) {
    if (actor.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      throw new PhoneChallengeForbiddenException();
    }
  }

  private String generateOtp() {
    return String.format("%06d", random.nextInt(1_000_000));
  }

  /** Audit snapshot — the OTP hash and plain code are deliberately absent; phone is masked. */
  private static Map<String, Object> challengeValues(PhoneVerificationChallengeEntity challenge) {
    var values = new LinkedHashMap<String, Object>();
    values.put("userId", challenge.getUserId().toString());
    values.put("pendingPhone", maskPhone(challenge.getPendingPhone()));
    values.put("expiresAt", challenge.getExpiresAt().toString());
    values.put("attemptCount", challenge.getAttemptCount());
    values.put("maxAttempts", challenge.getMaxAttempts());
    values.put("resendAvailableAt",
        challenge.getResendAvailableAt() == null ? null : challenge.getResendAvailableAt().toString());
    values.put("consumedAt", challenge.getConsumedAt() == null ? null : challenge.getConsumedAt().toString());
    return values;
  }

  static String maskPhone(String phone) {
    if (phone == null || phone.length() < 5) {
      return "****";
    }
    return "*".repeat(phone.length() - 4) + phone.substring(phone.length() - 4);
  }

  static PhoneChallengeView toView(PhoneVerificationChallengeEntity challenge) {
    return new PhoneChallengeView(
        challenge.getId(),
        challenge.getUserId(),
        challenge.getPendingPhone(),
        challenge.getExpiresAt(),
        challenge.getAttemptCount(),
        challenge.getMaxAttempts(),
        challenge.getResendAvailableAt(),
        challenge.getConsumedAt(),
        challenge.getCreatedAt());
  }

  public static class PhoneChallengeForbiddenException extends RuntimeException {
  }

  public static class ChallengeNotFoundException extends RuntimeException {
  }

  public static class ChallengeExpiredException extends RuntimeException {
  }

  public static class ChallengeExhaustedException extends RuntimeException {
  }

  public static class ChallengeConsumedException extends RuntimeException {
  }

  public static class InvalidOtpException extends RuntimeException {
  }

  public static class ResendTooEarlyException extends RuntimeException {
  }
}
