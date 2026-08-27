package com.syncro.org.api;

import com.syncro.org.application.JobTitleService.DuplicateJobTitleCodeException;
import com.syncro.org.application.JobTitleService.JobTitleDataIntegrityException;
import com.syncro.org.application.JobTitleService.JobTitleMutationForbiddenException;
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
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = JobTitleController.class)
public class JobTitleExceptionHandler {

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }

  private final Clock clock;

  public JobTitleExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var error : exception.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), "Invalid value.");
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  @ExceptionHandler(DuplicateJobTitleCodeException.class)
  ResponseEntity<ErrorResponse> duplicateJobTitleCode() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "A job title with this code already exists.", Map.of("code", "Invalid value."));
  }

  @ExceptionHandler(JobTitleMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(JobTitleDataIntegrityException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.CONFLICT, "VALIDATION_ERROR", "Job title data conflicts with existing records.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
