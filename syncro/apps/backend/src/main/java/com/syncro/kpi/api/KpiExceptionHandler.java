package com.syncro.kpi.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.kpi.api.KpiTargetController.UnknownKpiTypeException;
import com.syncro.kpi.application.KpiQueryService.KpiPlantForbiddenException;
import com.syncro.kpi.application.KpiTargetService.PlantNotFoundForTargetException;
import com.syncro.kpi.application.KpiTargetService.TargetConflictException;
import com.syncro.kpi.application.KpiTargetService.TargetMutationForbiddenException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Stable error envelope for the KPI API (story 20-1) — codes, never exception names. */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = KpiTargetController.class)
public class KpiExceptionHandler {

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }

  private final Clock clock;

  public KpiExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler({TargetMutationForbiddenException.class, PlantAccessDeniedException.class,
      KpiPlantForbiddenException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var fieldError : exception.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(fieldError.getField(), "Invalid value.");
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "Invalid value."));
  }

  @ExceptionHandler(TargetConflictException.class)
  ResponseEntity<ErrorResponse> targetConflict() {
    return error(HttpStatus.CONFLICT, "KPI_TARGET_CONFLICT",
        "A concurrent update already created this target. Retry the request.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForTargetException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(UnknownKpiTypeException.class)
  ResponseEntity<ErrorResponse> unknownType() {
    return error(HttpStatus.NOT_FOUND, "KPI_TYPE_NOT_FOUND",
        "Unknown KPI type. Use mtbf, mttr, mar, pm-completion, technician, or breakdown.",
        Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status).body(new ErrorResponse(code, message, fieldErrors,
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
