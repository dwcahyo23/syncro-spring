package com.syncro.auth.infrastructure;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserRepository extends JpaRepository<AuthUserEntity, UUID> {
  Optional<AuthUserEntity> findByLoginIdentifierIgnoreCase(String loginIdentifier);
}
