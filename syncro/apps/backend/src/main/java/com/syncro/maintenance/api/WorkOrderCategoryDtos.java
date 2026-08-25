package com.syncro.maintenance.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

public final class WorkOrderCategoryDtos {
  private WorkOrderCategoryDtos() {
  }

  /** Safe as a URL path segment and for OPA glob matching: alphanumerics, dot, dash, underscore. */
  private static final String CODE_PATTERN = "^[A-Za-z0-9._-]{1,16}$";

  public record CreateWorkOrderCategoryRequest(
      @NotBlank @Pattern(regexp = CODE_PATTERN) String code,
      @NotBlank @Size(max = 100) String label) {
  }

  public record UpdateWorkOrderCategoryRequest(
      @NotBlank @Pattern(regexp = CODE_PATTERN) String code,
      @NotBlank @Size(max = 100) String label) {
  }

  public record WorkOrderCategoryView(String code, String label) {
  }

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }
}
