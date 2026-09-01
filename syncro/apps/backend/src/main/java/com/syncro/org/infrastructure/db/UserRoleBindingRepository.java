package com.syncro.org.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code user_role_bindings} (blueprint A10, story 15-2). */
public interface UserRoleBindingRepository extends JpaRepository<UserRoleBindingEntity, UUID> {

  List<UserRoleBindingEntity> findByUserId(UUID userId);

  Optional<UserRoleBindingEntity> findByUserIdAndSystemRoleId(UUID userId, UUID systemRoleId);

  void deleteByUserIdAndSystemRoleId(UUID userId, UUID systemRoleId);
}