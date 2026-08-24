package com.syncro.shiftconfig.api;

import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.shiftconfig.api.ShiftConfigExceptionHandler.ErrorResponse;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineGroupNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MutationForbiddenException;
import com.syncro.shiftconfig.application.ShiftConfigService.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = ShiftConfigController.class)
public class ShiftConfigExceptionHandler {
  private final Clock clock;

  public ShiftConfigExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<ErrorResponse> validation(ValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  /**
   * Replace-all is delete-then-insert; two concurrent PUTs on one resource interleave into the
   * (resource_id, shift_number) unique constraint. Surfaces as a retryable conflict, not a 500.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorResponse> concurrentReplace() {
    return error(HttpStatus.CONFLICT, "SHIFT_CONFIG_CONFLICT",
        "Shift schedule was modified concurrently. Retry with the current state.", Map.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidPathValue(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  @ExceptionHandler({MutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(JobScopeForbiddenException.class)
  ResponseEntity<ErrorResponse> jobScopeForbidden(JobScopeForbiddenException exception) {
    return error(HttpStatus.FORBIDDEN, "JOB_SCOPE_REQUIRED", exception.getMessage(), Map.of());
  }

  @ExceptionHandler(MachineGroupNotFoundException.class)
  ResponseEntity<ErrorResponse> machineGroupNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_GROUP_NOT_FOUND", "Machine group was not found.", Map.of());
  }

  @ExceptionHandler(MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
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