package com.syncro.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code user_signatures} (blueprint I1, DP4, story 15-2). */
public interface UserSignatureRepository extends JpaRepository<UserSignatureEntity, UUID> {

  Optional<UserSignatureEntity> findByUserId(UUID userId);
}
