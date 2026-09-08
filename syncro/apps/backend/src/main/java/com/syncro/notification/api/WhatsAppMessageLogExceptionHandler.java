package com.syncro.notification.api;

import com.syncro.notification.application.WhatsAppMessageLogQueryService.WhatsAppMessageLogForbiddenException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * House error envelope for the message-log read surface (story 22-4): stable
 * machine-readable code, safe message, timestamp, traceId. Never exposes exception
 * names, SQL details, raw phones, or message text.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WhatsAppMessageLogController.class)
public class WhatsAppMessageLogExceptionHandler {

  private final Clock clock;

  public WhatsAppMessageLogExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(WhatsAppMessageLogForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  /** Unknown ?status= enum value → 400 VALIDATION_ERROR, not a 500. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> statusParamMismatch(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.",
        Map.of(exception.getName(), "Invalid value."));
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
