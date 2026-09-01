package com.syncro.org.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code user_role_bindings} (blueprint A10, story 15-2). */
public interface UserRoleBindingRepository extends JpaRepository<UserRoleBindingEntity, UUID> {
}
