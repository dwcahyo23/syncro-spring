package com.syncro.auth.infrastructure;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code phone_verification_challenges} (blueprint I3, story 15-2; service story 22-2). */
public interface PhoneVerificationChallengeRepository
    extends JpaRepository<PhoneVerificationChallengeEntity, UUID> {

  /**
   * Newest active (unconsumed, unexpired) challenge for a user — the lookup the 4-hour ack
   * auto-login flow (FR-181) resolves a phone-bound challenge through.
   */
  Optional<PhoneVerificationChallengeEntity>
      findTopByUserIdAndConsumedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(UUID userId, Instant now);
}

