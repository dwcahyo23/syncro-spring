package com.syncro.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code phone_verification_challenges} row (blueprint I3, story 15-2). One
 * active OTP challenge per issuance: only the {@code otp_hash} is stored (never the
 * code itself); {@code attempt_count}/{@code max_attempts} gate brute force, and
 * {@code consumed_at} marks a successfully verified challenge.
 */
@Entity
@Table(name = "phone_verification_challenges")
public class PhoneVerificationChallengeEntity {

  @Id
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "pending_phone", nullable = false, length = 32)
  private String pendingPhone;

  @Column(name = "otp_hash", nullable = false, length = 255)
  private String otpHash;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "max_attempts", nullable = false)
  private int maxAttempts;

  @Column(name = "resend_available_at")
  private Instant resendAvailableAt;

  @Column(name = "consumed_at")
  private Instant consumedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  /** Optimistic lock (review 22-2 P3): concurrent verify/resend → lost update rejected. */
  @Version
  @Column(nullable = false)
  private long version;

  protected PhoneVerificationChallengeEntity() {
  }

  public PhoneVerificationChallengeEntity(UUID id, UUID userId, String pendingPhone,
      String otpHash, Instant expiresAt, int attemptCount, int maxAttempts,
      Instant resendAvailableAt, Instant consumedAt, Instant createdAt) {
    this.id = id;
    this.userId = userId;
    this.pendingPhone = pendingPhone;
    this.otpHash = otpHash;
    this.expiresAt = expiresAt;
    this.attemptCount = attemptCount;
    this.maxAttempts = maxAttempts;
    this.resendAvailableAt = resendAvailableAt;
    this.consumedAt = consumedAt;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPendingPhone() {
    return pendingPhone;
  }

  public String getOtpHash() {
    return otpHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public int getMaxAttempts() {
    return maxAttempts;
  }

  public Instant getResendAvailableAt() {
    return resendAvailableAt;
  }

  public Instant getConsumedAt() {
    return consumedAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  /** Registers a failed verification attempt (I3); caller decides exhaustion. */
  public void registerFailedAttempt() {
    this.attemptCount = this.attemptCount + 1;
  }

  /** Consumes the challenge on successful verification (I3). */
  public void consume(Instant consumedAt) {
    this.consumedAt = consumedAt;
  }

  /**
   * Re-issues the challenge after the resend window (story 22-2): swaps in a new OTP
   * hash, extends expiry, resets the attempt budget, and sets the next resend gate.
   * The row identity (id/user/pendingPhone/maxAttempts) is preserved — resend renews
   * the same challenge, it does not fork a second one.
   */
  public void renew(String otpHash, Instant expiresAt, Instant resendAvailableAt) {
    this.otpHash = otpHash;
    this.expiresAt = expiresAt;
    this.resendAvailableAt = resendAvailableAt;
    this.attemptCount = 0;
  }
}
