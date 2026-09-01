package com.syncro.org.api;

import com.syncro.org.application.DomainContextService;
import com.syncro.org.application.MenuFeatureService;
import com.syncro.org.application.RolePermissionMappingService;
import com.syncro.org.application.SystemRoleService;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consolidated read-only endpoints for the data-driven role configuration
 * tables (blueprint A5–A8, story 16-2). Accessible to any authenticated user.
 */
@RestController
public class RoleConfigController {

  private final SystemRoleService systemRoles;
  private final MenuFeatureService menuFeatures;
  private final DomainContextService domainContexts;
  private final RolePermissionMappingService rolePermissionMappings;

  public RoleConfigController(SystemRoleService systemRoles, MenuFeatureService menuFeatures,
      DomainContextService domainContexts, RolePermissionMappingService rolePermissionMappings) {
    this.systemRoles = systemRoles;
    this.menuFeatures = menuFeatures;
    this.domainContexts = domainContexts;
    this.rolePermissionMappings = rolePermissionMappings;
  }

  @Operation(operationId = "listSystemRoles", summary = "List system roles")
  @GetMapping("/api/v1/system-roles")
  public SystemRoleService.SystemRoleListView listSystemRoles() {
    return systemRoles.list();
  }

  @Operation(operationId = "listMenuFeatures", summary = "List menu features")
  @GetMapping("/api/v1/menu-features")
  public MenuFeatureService.MenuFeatureListView listMenuFeatures() {
    return menuFeatures.list();
  }

  @Operation(operationId = "listDomainContexts", summary = "List domain contexts")
  @GetMapping("/api/v1/domain-contexts")
  public DomainContextService.DomainContextListView listDomainContexts() {
    return domainContexts.list();
  }

  @Operation(operationId = "listRolePermissionMappings", summary = "List role-permission mappings by role")
  @GetMapping("/api/v1/role-permission-mappings")
  public RolePermissionMappingService.RolePermissionMappingListView listRolePermissionMappings(
      @RequestParam UUID systemRoleId) {
    return rolePermissionMappings.listByRole(systemRoleId);
  }
}