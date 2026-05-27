package com.syncro.masterdata.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.masterdata.api.PlantDtos.ErrorResponse;
import com.syncro.masterdata.application.PlantService.DuplicatePlantCodeException;
import com.syncro.masterdata.application.PlantService.PlantDataIntegrityException;
import com.syncro.masterdata.application.PlantService.PlantMutationForbiddenException;
import com.syncro.masterdata.application.PlantService.PlantNotFoundException;
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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = PlantController.class)
public class PlantExceptionHandler {
  private final Clock clock;

  public PlantExceptionHandler(Clock clock) {
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

  @ExceptionHandler(DuplicatePlantCodeException.class)
  ResponseEntity<ErrorResponse> duplicatePlantCode() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_PLANT_CODE", "Plant code already exists.", Map.of());
  }

  @ExceptionHandler(PlantDataIntegrityException.class)
  ResponseEntity<ErrorResponse> plantDataIntegrity() {
    return error(HttpStatus.CONFLICT, "PLANT_DATA_INTEGRITY_VIOLATION", "Plant data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler({PlantMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundException.class)
  ResponseEntity<ErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
