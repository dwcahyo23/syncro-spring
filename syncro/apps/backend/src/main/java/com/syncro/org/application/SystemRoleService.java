package com.syncro.org.application;

import com.syncro.org.infrastructure.db.SystemRoleEntity;
import com.syncro.org.infrastructure.db.SystemRoleRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only access to system roles (blueprint A5). */
@Service
public class SystemRoleService {

  private final SystemRoleRepository systemRoles;

  public SystemRoleService(SystemRoleRepository systemRoles) {
    this.systemRoles = systemRoles;
  }

  @Transactional(readOnly = true)
  public SystemRoleListView list() {
    return new SystemRoleListView(
        systemRoles.findAll().stream().map(this::toView).toList());
  }

  private SystemRoleView toView(SystemRoleEntity role) {
    return new SystemRoleView(role.getId(), role.getCode(), role.getName(), role.getLevel(),
        role.isActive(), role.getDescription());
  }

  public record SystemRoleView(UUID id, String code, String name, int level, boolean active, String description) {
  }

  public record SystemRoleListView(List<SystemRoleView> items) {
  }
}