package com.syncro.auth.api;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RoleCheckTestController {
  @GetMapping("/api/v1/auth/role-check/super-admin")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  Map<String, String> superAdminRoleCheck() {
    return Map.of("status", "OK");
  }

  @GetMapping("/api/v1/auth/role-check/manage")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MANAGER_MAINTENANCE')")
  Map<String, String> manageRoleCheck() {
    return Map.of("status", "OK");
  }

  @PostMapping("/api/v1/auth/role-check/mutation")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MANAGER_MAINTENANCE')")
  Map<String, String> mutationRoleCheck() {
    return Map.of("status", "OK");
  }
}
