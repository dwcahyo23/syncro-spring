package com.syncro.auth.api;

import com.syncro.auth.domain.ApplicationRole;
import jakarta.validation.constraints.NotBlank;

public final class AuthDtos {
  private AuthDtos() {
  }

  public record LoginRequest(@NotBlank String loginIdentifier, @NotBlank String password) {
  }

  public record AuthUserView(String id, String loginIdentifier, ApplicationRole applicationRole) {
  }

  public record LoginResponse(String tokenType, String accessToken, long expiresInSeconds, AuthUserView user) {
  }

  public record ErrorResponse(String code, String message, String timestamp, String traceId) {
  }
}
