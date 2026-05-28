package com.syncro.sparepart.api;

import com.syncro.sparepart.application.SparepartService.DuplicateSparepartException;
import com.syncro.sparepart.application.SparepartService.SparepartDataIntegrityException;
import com.syncro.sparepart.application.SparepartService.SparepartMutationForbiddenException;
import com.syncro.sparepart.application.SparepartService.SparepartNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyDimensionMismatchException;
import com.syncro.sparepart.application.SparepartService.SparepartTaxonomyReferenceNotFoundException;
import com.syncro.sparepart.application.SparepartService.SparepartValidationException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SparepartController.class)
public class SparepartExceptionHandler {
  private final Clock clock;

  public SparepartExceptionHandler(Clock clock) {
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

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidPathValue() {
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  @ExceptionHandler(SparepartValidationException.class)
  ResponseEntity<ErrorResponse> sparepartValidation() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of());
  }

  @ExceptionHandler(DuplicateSparepartException.class)
  ResponseEntity<ErrorResponse> duplicateSparepart() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_SPAREPART", "Sparepart code or name already exists.", Map.of());
  }

  @ExceptionHandler(SparepartTaxonomyReferenceNotFoundException.class)
  ResponseEntity<ErrorResponse> taxonomyReferenceNotFound() {
    return error(HttpStatus.BAD_REQUEST, "SPAREPART_TAXONOMY_REFERENCE_NOT_FOUND", "Sparepart taxonomy reference was not found.", Map.of());
  }

  @ExceptionHandler(SparepartTaxonomyDimensionMismatchException.class)
  ResponseEntity<ErrorResponse> taxonomyDimensionMismatch() {
    return error(HttpStatus.BAD_REQUEST, "SPAREPART_TAXONOMY_DIMENSION_MISMATCH", "Sparepart taxonomy reference has invalid dimension.", Map.of());
  }

  @ExceptionHandler(SparepartDataIntegrityException.class)
  ResponseEntity<ErrorResponse> sparepartDataIntegrity() {
    return error(HttpStatus.CONFLICT, "SPAREPART_DATA_INTEGRITY_VIOLATION", "Sparepart data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler(SparepartMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(SparepartNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId) {
  }
}
