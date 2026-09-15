package com.syncro.report.api;

import com.syncro.report.api.ReportPrintTemplateController.InvalidReportTypeException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateForbiddenException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateNotFoundException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateTooLargeException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ReportPrintTemplateController.class)
public class ReportPrintTemplateExceptionHandler {

  private final Clock clock;

  public ReportPrintTemplateExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(ReportPrintTemplateNotFoundException.class)
  ResponseEntity<ErrorResponse> templateNotFound() {
    return error(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "Report print template was not found.", Map.of());
  }

  @ExceptionHandler(ReportPrintTemplateForbiddenException.class)
  ResponseEntity<ErrorResponse> templateForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to modify report print templates.", Map.of());
  }

  @ExceptionHandler(ReportPrintTemplateTooLargeException.class)
  ResponseEntity<ErrorResponse> templateTooLarge() {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    fieldErrors.put("bodyHtml", "Template body exceeds the maximum length of 200000 characters.");
    return error(HttpStatus.BAD_REQUEST, "TEMPLATE_TOO_LARGE",
        "Report print template body is too large.", fieldErrors);
  }

  @ExceptionHandler(InvalidReportTypeException.class)
  ResponseEntity<ErrorResponse> invalidReportType() {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    fieldErrors.put("reportType", "Unknown report type.");
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", fieldErrors);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    ex.getBindingResult().getFieldErrors()
        .forEach(fe -> fieldErrors.put(fe.getField(), fe.getDefaultMessage()));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", fieldErrors);
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors,
            Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}
