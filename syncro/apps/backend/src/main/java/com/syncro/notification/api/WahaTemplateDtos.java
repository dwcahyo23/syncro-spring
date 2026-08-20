package com.syncro.notification.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

public final class WahaTemplateDtos {

  private WahaTemplateDtos() {
  }

  public record WahaTemplateView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) String templateKey,
      @Schema(nullable = false) String body,
      @Schema(nullable = false) Instant updatedAt) {
  }

  public record UpsertTemplateRequest(
      @NotBlank
      @Schema(nullable = false, description = "Template body text with supported variables") String body) {
  }
}
