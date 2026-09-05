package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.IssuePhoneChallengeRequest;
import com.syncro.auth.api.AuthDtos.LoginAuditListResponse;
import com.syncro.auth.api.AuthDtos.LoginAuditView;
import com.syncro.auth.api.AuthDtos.LoginRequest;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.api.AuthDtos.PhoneChallengeView;
import com.syncro.auth.api.AuthDtos.PlantScopeResponse;
import com.syncro.auth.api.AuthDtos.UpdateUserRequest;
import com.syncro.auth.api.AuthDtos.VerifyPhoneChallengeRequest;
import com.syncro.auth.application.AuthLoginAuditService;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PhoneVerificationService;
import com.syncro.auth.application.PlantScopeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final AuthService auth;
  private final PlantScopeService plantScopes;
  private final AuthLoginAuditService loginAudits;
  private final PhoneVerificationService phoneChallenges;

  public AuthController(AuthService auth, PlantScopeService plantScopes,
      AuthLoginAuditService loginAudits, PhoneVerificationService phoneChallenges) {
    this.auth = auth;
    this.plantScopes = plantScopes;
    this.loginAudits = loginAudits;
    this.phoneChallenges = phoneChallenges;
  }

  @PostMapping("/login")
  public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    return auth.login(request.loginIdentifier(), request.password(), clientIp(http),
            http.getHeader(HttpHeaders.USER_AGENT));
  }

  @GetMapping("/me")
  public AuthUserView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return auth.currentUser(user);
  }

  @GetMapping("/plant-scope")
  public PlantScopeResponse plantScope(@AuthenticationPrincipal AuthenticatedUser user) {
    return plantScopes.effectiveScope(user);
  }

  @Operation(operationId = "listUsers", summary = "List users")
  @GetMapping("/users")
  public java.util.List<AuthUserView> listUsers() {
    return auth.listUsers();
  }

  @Operation(operationId = "updateUser", summary = "Update user master fields")
  @PutMapping("/users/{userId}")
  public AuthUserView updateUser(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID userId,
      @Valid @RequestBody UpdateUserRequest request) {
    return auth.updateUser(user, userId, request);
  }

  @Operation(operationId = "listLoginAudits",
      summary = "List login-attempt audits (SUPER_ADMIN/AUDITOR, newest first)")
  @GetMapping("/login-audits")
  public LoginAuditListResponse listLoginAudits(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String identifier,
      @RequestParam(required = false) Boolean success,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant from,
      @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return loginAudits.list(user, identifier, success, from, to, page, size);
  }

  @Operation(operationId = "getLoginAudit", summary = "Read one login-attempt audit (SUPER_ADMIN/AUDITOR)")
  @GetMapping("/login-audits/{auditId}")
  public LoginAuditView getLoginAudit(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID auditId) {
    return loginAudits.get(user, auditId);
  }

  @Operation(operationId = "issuePhoneChallenge",
      summary = "Issue a phone-verification challenge (SUPER_ADMIN; OTP is never returned)")
  @PostMapping("/phone-challenges")
  public ResponseEntity<PhoneChallengeView> issuePhoneChallenge(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody IssuePhoneChallengeRequest request) {
    var view = phoneChallenges.issue(user, request.userId(), request.phoneNumber());
    return ResponseEntity.created(URI.create("/api/v1/auth/phone-challenges/" + view.id())).body(view);
  }

  @Operation(operationId = "verifyPhoneChallenge", summary = "Verify a phone-verification challenge (SUPER_ADMIN)")
  @PostMapping("/phone-challenges/{challengeId}/verify")
  public PhoneChallengeView verifyPhoneChallenge(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID challengeId, @Valid @RequestBody VerifyPhoneChallengeRequest request) {
    return phoneChallenges.verify(user, challengeId, request.otp());
  }

  @Operation(operationId = "resendPhoneChallenge",
      summary = "Resend (renew) a phone-verification challenge after its resend window (SUPER_ADMIN)")
  @PostMapping("/phone-challenges/{challengeId}/resend")
  public ResponseEntity<PhoneChallengeView> resendPhoneChallenge(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID challengeId) {
    var view = phoneChallenges.resend(user, challengeId);
    // 201 per the story 22-2 I/O matrix ("201 + new expiry after window").
    return ResponseEntity.created(URI.create("/api/v1/auth/phone-challenges/" + view.id())).body(view);
  }

  @PostMapping("/logout")
  public Map<String, String> logout() {
    return Map.of("status", "OK");
  }

  /** First X-Forwarded-For hop when present (local infra sits behind no trusted proxy chain), else socket address. */
  private static String clientIp(HttpServletRequest request) {
    var forwarded = request.getHeader("X-Forwarded-For");
    if (forwarded != null && !forwarded.isBlank()) {
      var first = forwarded.split(",")[0].trim();
      if (!first.isEmpty()) {
        return first;
      }
    }
    return request.getRemoteAddr();
  }
}
