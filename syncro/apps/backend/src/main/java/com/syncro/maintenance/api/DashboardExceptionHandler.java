package com.syncro.maintenance.api;

import com.syncro.maintenance.api.DashboardDtos.ErrorResponse;
import com.syncro.maintenance.application.DashboardAnalyticsService.AnalyticsForbiddenException;
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
 * Error mapping for the dashboard read surface (story 14-1 + 14-2). The dashboard
 * contract (I/O matrix) returns empty payloads for out-of-scope filter values — never
 * a 403 for scope. Role-denied analytics calls (14-2) map to a FORBIDDEN 403.
 * Live mappings: invalid query values (bad UUIDs, bad enums) → 400; analytics role
 * denial → 403.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = DashboardController.class)
public class DashboardExceptionHandler {

  private final Clock clock;

  public DashboardExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidQueryValue(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY_VALUE", "Query value is invalid.", Map.of());
  }

  @ExceptionHandler(AnalyticsForbiddenException.class)
  ResponseEntity<ErrorResponse> analyticsForbidden(AnalyticsForbiddenException exception) {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}