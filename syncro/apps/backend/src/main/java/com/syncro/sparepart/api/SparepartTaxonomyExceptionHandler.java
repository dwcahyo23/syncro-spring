package com.syncro.sparepart.api;

import com.syncro.masterdata.api.PlantDtos.ErrorResponse;
import com.syncro.sparepart.application.SparepartTaxonomyService.DuplicateSparepartTaxonomyException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyDataIntegrityException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyMutationForbiddenException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyNotFoundException;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyValidationException;
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
@RestControllerAdvice(assignableTypes = SparepartTaxonomyController.class)
public class SparepartTaxonomyExceptionHandler {
  private final Clock clock;

  public SparepartTaxonomyExceptionHandler(Clock clock) {
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

  @ExceptionHandler(SparepartTaxonomyValidationException.class)
  ResponseEntity<ErrorResponse> taxonomyValidation() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of());
  }

  @ExceptionHandler(DuplicateSparepartTaxonomyException.class)
  ResponseEntity<ErrorResponse> duplicateTaxonomy() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_SPAREPART_TAXONOMY", "Sparepart taxonomy code or name already exists for this dimension.", Map.of());
  }

  @ExceptionHandler(SparepartTaxonomyDataIntegrityException.class)
  ResponseEntity<ErrorResponse> taxonomyDataIntegrity() {
    return error(HttpStatus.CONFLICT, "SPAREPART_TAXONOMY_DATA_INTEGRITY_VIOLATION", "Sparepart taxonomy data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler(SparepartTaxonomyMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(SparepartTaxonomyNotFoundException.class)
  ResponseEntity<ErrorResponse> taxonomyNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_TAXONOMY_NOT_FOUND", "Sparepart taxonomy entry was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
