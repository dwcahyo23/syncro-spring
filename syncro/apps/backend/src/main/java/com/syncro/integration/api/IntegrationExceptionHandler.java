package com.syncro.integration.api;

import com.syncro.integration.application.WebhookConfigService.DuplicateWebhookNameException;
import com.syncro.integration.application.WebhookConfigService.WebhookConfigNotFoundException;
import com.syncro.integration.application.WebhookConfigService.WebhookForbiddenException;
import com.syncro.integration.application.WebhookConfigService.WebhookValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * House error envelope for the webhook surface (story 22-1): stable machine-readable
 * code, safe message, fieldErrors, timestamp, traceId. Never exposes exception names,
 * SQL details, or the HMAC secret.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WebhookController.class)
public class IntegrationExceptionHandler {

  private final Clock clock;

  public IntegrationExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(WebhookForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(WebhookConfigNotFoundException.class)
  ResponseEntity<ErrorResponse> configNotFound() {
    return error(HttpStatus.NOT_FOUND, "WEBHOOK_CONFIG_NOT_FOUND",
        "Webhook config was not found.", Map.of());
  }

  @ExceptionHandler(DuplicateWebhookNameException.class)
  ResponseEntity<ErrorResponse> duplicateName() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_WEBHOOK_NAME",
        "A webhook config with this name already exists.", Map.of());
  }

  /** Review 21-1 P6 house rule: @Version lost updates surface as 409, never 500. */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> optimisticLockConflict() {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
        "Concurrent update detected; reload and retry.", Map.of());
  }

  /** Review 22-1 P10: malformed JSON must answer with the house envelope, not Spring's default. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", Map.of());
  }

  @ExceptionHandler(WebhookValidationException.class)
  ResponseEntity<ErrorResponse> serviceValidation(WebhookValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> dtoValidation(MethodArgumentNotValidException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    exception.getBindingResult().getFieldErrors()
        .forEach(fe -> fieldErrors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.",
        fieldErrors);
  }

  /** Unknown ?status= enum value → 400 VALIDATION_ERROR, not a 500. */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> statusParamMismatch(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.",
        Map.of(exception.getName(), "Invalid value."));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.",
        Map.of(exception.getParameterName(), "This value is required."));
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
