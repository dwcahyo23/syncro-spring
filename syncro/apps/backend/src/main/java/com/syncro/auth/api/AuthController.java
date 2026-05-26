package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginRequest;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;

  public AuthController(AuthService auth) {
    this.auth = auth;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request) {
    return auth.login(request.loginIdentifier(), request.password());
  }

  @GetMapping("/me")
  public AuthUserView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return auth.currentUser(user);
  }

  @GetMapping("/role-check/super-admin")
  @PreAuthorize("hasRole('SUPER_ADMIN')")
  public Map<String, String> superAdminRoleCheck() {
    return Map.of("status", "OK");
  }

  @GetMapping("/role-check/manage")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MANAGE')")
  public Map<String, String> manageRoleCheck() {
    return Map.of("status", "OK");
  }

  @PostMapping("/role-check/mutation")
  @PreAuthorize("hasAnyRole('SUPER_ADMIN', 'MANAGE')")
  public Map<String, String> mutationRoleCheck() {
    return Map.of("status", "OK");
  }

  @PostMapping("/logout")
  public Map<String, String> logout() {
    return Map.of("status", "OK");
  }
}
