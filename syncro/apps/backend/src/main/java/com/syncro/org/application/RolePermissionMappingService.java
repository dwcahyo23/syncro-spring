package com.syncro.org.application;

import com.syncro.org.infrastructure.db.RolePermissionMappingEntity;
import com.syncro.org.infrastructure.db.RolePermissionMappingRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to role-permission mappings (blueprint A6). */
@Service
public class RolePermissionMappingService {

  private final RolePermissionMappingRepository mappings;

  public RolePermissionMappingService(RolePermissionMappingRepository mappings) {
    this.mappings = mappings;
  }

  @Transactional(readOnly = true)
  public RolePermissionMappingListView listByRole(UUID systemRoleId) {
    return new RolePermissionMappingListView(
        mappings.findAll().stream()
            .filter(m -> m.getSystemRoleId().equals(systemRoleId))
            .map(this::toView)
            .toList());
  }

  private RolePermissionMappingView toView(RolePermissionMappingEntity mapping) {
    return new RolePermissionMappingView(mapping.getId(), mapping.getSystemRoleId(), mapping.getMenuFeatureId(),
        mapping.getDomainId(), mapping.isGranted());
  }

  public record RolePermissionMappingView(UUID id, UUID systemRoleId, UUID menuFeatureId, UUID domainId,
      boolean granted) {
  }

  public record RolePermissionMappingListView(List<RolePermissionMappingView> items) {
  }
}