package com.syncro.org.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code system_roles} (blueprint A5, story 15-2). */
public interface SystemRoleRepository extends JpaRepository<SystemRoleEntity, UUID> {

  Optional<SystemRoleEntity> findByCode(String code);
}
