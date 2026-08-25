package com.syncro.authz.api;

import java.util.List;
import java.util.Map;

public final class AuthzDtos {
  private AuthzDtos() {
  }

  public record AllowedActionsView(List<String> actions, boolean degraded) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}
