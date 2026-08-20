package com.syncro.notification.api;

import com.syncro.notification.application.WahaTemplateService.WahaTemplateForbiddenException;
import com.syncro.notification.application.WahaTemplateService.WahaTemplateNotFoundException;
import com.syncro.notification.application.WahaTemplateService.WahaTemplateValidationException;
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
@RestControllerAdvice(assignableTypes = WahaTemplateController.class)
public class WahaTemplateExceptionHandler {

  private final Clock clock;

  public WahaTemplateExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(WahaTemplateNotFoundException.class)
  ResponseEntity<ErrorResponse> templateNotFound() {
    return error(HttpStatus.NOT_FOUND, "TEMPLATE_NOT_FOUND", "WAHA template was not found.", Map.of());
  }

  @ExceptionHandler(WahaTemplateForbiddenException.class)
  ResponseEntity<ErrorResponse> templateForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to modify WAHA templates.", Map.of());
  }

  @ExceptionHandler(WahaTemplateValidationException.class)
  ResponseEntity<ErrorResponse> invalidVariables(WahaTemplateValidationException ex) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    int i = 0;
    for (String unknown : new java.util.TreeSet<>(ex.getUnknownVariables())) {
      fieldErrors.put("body[" + i++ + "]", "Unknown variable: " + unknown);
    }
    return error(HttpStatus.BAD_REQUEST, "TEMPLATE_INVALID_VARIABLES",
        "Template contains unknown variables.", fieldErrors);
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
