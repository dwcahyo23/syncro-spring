package com.syncro.report.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.report.api.ReportPrintTemplateDtos.ReportPrintTemplateView;
import com.syncro.report.api.ReportPrintTemplateDtos.UpsertReportPrintTemplateRequest;
import com.syncro.report.application.ReportPrintTemplateService;
import com.syncro.report.domain.ReportPrintTemplate;
import com.syncro.report.domain.ReportPrintTemplateType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Global print-report layout templates (spec "Template Laporan Cetak"). GET is
 * readable by every authenticated user — the WO/Preventive print pages fetch the
 * template to decide custom-vs-default layout; a missing template is 404 and the
 * client falls back silently. PUT is service-gated to SUPER_ADMIN /
 * MANAGER_MAINTENANCE (the security decision lives in {@code ReportPrintTemplateService},
 * never in this controller). An empty bodyHtml is accepted on purpose: saving a
 * blank template is how a leader clears the custom layout (the client then renders
 * the built-in default).
 */
@RestController
@RequestMapping("/api/v1/report-templates")
public class ReportPrintTemplateController {

  private final ReportPrintTemplateService templateService;

  public ReportPrintTemplateController(ReportPrintTemplateService templateService) {
    this.templateService = templateService;
  }

  @Operation(operationId = "getReportPrintTemplate", summary = "Get the print layout template for a report type")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Template returned"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "No template stored for this report type")
  })
  @GetMapping("/{reportType}")
  public ResponseEntity<ReportPrintTemplateView> get(@PathVariable String reportType) {
    ReportPrintTemplateType type = parseType(reportType);
    return templateService.findTemplate(type)
        .map(template -> ResponseEntity.ok(toView(template)))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @Operation(operationId = "upsertReportPrintTemplate", summary = "Create or update the print layout template for a report type")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Template saved"),
      @ApiResponse(responseCode = "400", description = "Invalid report type or template body"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden")
  })
  @PutMapping("/{reportType}")
  public ReportPrintTemplateView upsert(
      @PathVariable String reportType,
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody UpsertReportPrintTemplateRequest request) {
    ReportPrintTemplateType type = parseType(reportType);
    ReportPrintTemplate template = templateService.upsertTemplate(type, request.bodyHtml(), user);
    return toView(template);
  }

  private ReportPrintTemplateType parseType(String raw) {
    try {
      return ReportPrintTemplateType.valueOf(raw);
    } catch (IllegalArgumentException ex) {
      throw new InvalidReportTypeException();
    }
  }

  /** Raised on an unknown {reportType} path value; mapped to 400 by the module handler. */
  public static class InvalidReportTypeException extends RuntimeException {
    public InvalidReportTypeException() {
      super("Unknown report type.");
    }
  }

  private ReportPrintTemplateView toView(ReportPrintTemplate template) {
    return new ReportPrintTemplateView(
        template.id(),
        template.reportType(),
        template.bodyHtml(),
        template.updatedBy(),
        template.updatedAt());
  }
}
