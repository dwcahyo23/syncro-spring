package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.application.OrganizationHierarchyService;
import com.syncro.org.application.OrganizationHierarchyService.OrganizationHierarchyView;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/organization")
public class OrganizationHierarchyController {

  private final OrganizationHierarchyService hierarchyService;

  public OrganizationHierarchyController(OrganizationHierarchyService hierarchyService) {
    this.hierarchyService = hierarchyService;
  }

  @Operation(operationId = "organizationHierarchy", summary = "Read the organization hierarchy")
  @GetMapping("/hierarchy")
  public OrganizationHierarchyView hierarchy(@AuthenticationPrincipal AuthenticatedUser user) {
    return hierarchyService.hierarchy(user);
  }
}
