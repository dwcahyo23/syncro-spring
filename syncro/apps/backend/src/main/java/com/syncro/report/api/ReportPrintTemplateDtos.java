package com.syncro.report.api;

import com.syncro.report.domain.ReportPrintTemplateType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class ReportPrintTemplateDtos {

  private ReportPrintTemplateDtos() {
  }

  public record ReportPrintTemplateView(
      @Schema(nullable = false) UUID id,
      @Schema(nullable = false) ReportPrintTemplateType reportType,
      @Schema(nullable = false) String bodyHtml,
      @Schema(nullable = true) UUID updatedBy,
      @Schema(nullable = false) Instant updatedAt) {
  }

  public record UpsertReportPrintTemplateRequest(
      @NotNull
      @Schema(nullable = false, description = "Template HTML (TipTap output); blank clears to the default layout")
      String bodyHtml) {
  }
}
