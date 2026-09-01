package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.application.UserBindingService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/user-bindings")
public class UserBindingController {

  private final UserBindingService bindings;

  public UserBindingController(UserBindingService bindings) {
    this.bindings = bindings;
  }

  @Operation(operationId = "getUserBindings", summary = "Get a user's job and role bindings")
  @GetMapping("/{userId}")
  public UserBindingService.UserBindingsView getBindings(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId) {
    return bindings.getBindings(user, userId);
  }

  @Operation(operationId = "setUserJob", summary = "Set or clear a user's job title binding")
  @PutMapping("/{userId}/job")
  public UserBindingService.UserBindingsView setJob(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId,
      @RequestBody SetJobRequest request) {
    return bindings.setJob(user, userId, request.jobTitleId());
  }

  @Operation(operationId = "addUserRole", summary = "Add a role binding for a user")
  @PostMapping("/{userId}/roles")
  public UserBindingService.UserBindingsView addRole(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId,
      @RequestBody AddRoleRequest request) {
    return bindings.addRole(user, userId, request.systemRoleId(), request.isOverride());
  }

  @Operation(operationId = "removeUserRole", summary = "Remove a role binding")
  @DeleteMapping("/{userId}/roles/{bindingId}")
  public ResponseEntity<Void> removeRole(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId,
      @PathVariable UUID bindingId) {
    bindings.removeRole(user, userId, bindingId);
    return ResponseEntity.noContent().build();
  }

  public record SetJobRequest(UUID jobTitleId) {
  }

  public record AddRoleRequest(@NotNull UUID systemRoleId, boolean isOverride) {
  }
}