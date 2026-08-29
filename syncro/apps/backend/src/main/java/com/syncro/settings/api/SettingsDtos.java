package com.syncro.settings.api;

import java.util.Map;

/** Settings module API contract types (story 14-3, FR-175). */
public final class SettingsDtos {
  private SettingsDtos() {
  }

  public record LogoView(String objectKey, String presignedUrl) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}