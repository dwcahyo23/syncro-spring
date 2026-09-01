package com.syncro.org.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code role_permission_mappings} (blueprint A6, story 15-2). */
public interface RolePermissionMappingRepository extends JpaRepository<RolePermissionMappingEntity, UUID> {
}
