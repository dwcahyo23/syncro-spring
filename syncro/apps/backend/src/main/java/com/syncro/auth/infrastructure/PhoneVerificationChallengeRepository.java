package com.syncro.auth.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code phone_verification_challenges} (blueprint I3, story 15-2). */
public interface PhoneVerificationChallengeRepository
    extends JpaRepository<PhoneVerificationChallengeEntity, UUID> {
}
