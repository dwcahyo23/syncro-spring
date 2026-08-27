package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginRequest;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.api.AuthDtos.PlantScopeResponse;
import com.syncro.auth.api.AuthDtos.UpdateUserRequest;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final PlantScopeService plantScopes;

  public AuthController(AuthService auth, PlantScopeService plantScopes) {
    this.auth = auth;
    this.plantScopes = plantScopes;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return auth.login(request.loginIdentifier(), request.password());
  }

  @GetMapping("/me")
  public AuthUserView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return auth.currentUser(user);
  }

  @GetMapping("/plant-scope")
  public PlantScopeResponse plantScope(@AuthenticationPrincipal AuthenticatedUser user) {
    return plantScopes.effectiveScope(user);
  }

  @io.swagger.v3.oas.annotations.Operation(operationId = "listUsers", summary = "List users")
  @GetMapping("/users")
  public java.util.List<AuthUserView> listUsers() {
    return auth.listUsers();
  }

  @io.swagger.v3.oas.annotations.Operation(operationId = "updateUser", summary = "Update user master fields")
  @PutMapping("/users/{userId}")
  public AuthUserView updateUser(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId,
      @Valid @RequestBody UpdateUserRequest request) {
    return auth.updateUser(user, userId, request);
  }

  @PostMapping("/logout")
  public Map<String, String> logout() {
    return Map.of("status", "OK");
  }
}
