package com.syncro.alert.api;

import com.syncro.alert.application.SparepartAlertCommandService.AlertInvalidTransitionException;
import com.syncro.alert.application.SparepartAlertQueryService.AlertNotFoundException;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SparepartAlertController.class)
public class SparepartAlertExceptionHandler {

  private final Clock clock;

  public SparepartAlertExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(AlertNotFoundException.class)
  ResponseEntity<ErrorResponse> alertNotFound() {
    return error(HttpStatus.NOT_FOUND, "ALERT_NOT_FOUND", "Alert was not found.");
  }

  @ExceptionHandler(AlertInvalidTransitionException.class)
  ResponseEntity<ErrorResponse> invalidTransition(AlertInvalidTransitionException ex) {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
        "Invalid alert state transition: " + ex.getFrom() + " → " + ex.getTo() + ".");
  }

  @ExceptionHandler(PlantAccessDeniedException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidQueryValue(MethodArgumentTypeMismatchException exception) {
    if (exception.getParameter() != null && exception.getParameter().hasParameterAnnotation(RequestParam.class)) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY_VALUE", "Query value is invalid.");
    }
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.");
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, Map.of(), Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId) {
  }
}
