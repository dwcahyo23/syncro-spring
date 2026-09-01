package com.syncro.inventory.api;

import com.syncro.inventory.api.InventoryLocationDtos.ErrorResponse;
import com.syncro.inventory.application.InventoryLocationService.DuplicateLocationException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationForbiddenException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationValidationException;
import com.syncro.inventory.application.InventoryLocationService.PlantNotFoundForLocationException;
import jakarta.validation.ConstraintViolationException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/**
 * Error mapping for the inventory-locations API (story 18-2). One stable shape
 * (code/message/fieldErrors/timestamp/traceId) — machine-readable codes:
 * DUPLICATE_LOCATION (409), INVENTORY_LOCATION_NOT_FOUND (404), PLANT_NOT_FOUND (404),
 * FORBIDDEN (403), VALIDATION_ERROR (400).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InventoryLocationController.class)
public class InventoryLocationExceptionHandler {

  private final Clock clock;

  public InventoryLocationExceptionHandler(Clock clock) {
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

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var violation : exception.getConstraintViolations()) {
      fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(InventoryLocationValidationException.class)
  ResponseEntity<ErrorResponse> locationValidation(InventoryLocationValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidPathValue(MethodArgumentTypeMismatchException exception) {
    if (exception.getParameter() != null
        && exception.getParameter().hasParameterAnnotation(RequestParam.class)) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY_VALUE", "Query value is invalid.", Map.of());
    }
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "Invalid value."));
  }

  @ExceptionHandler(InventoryLocationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(InventoryLocationNotFoundException.class)
  ResponseEntity<ErrorResponse> locationNotFound() {
    return error(HttpStatus.NOT_FOUND, "INVENTORY_LOCATION_NOT_FOUND",
        "Inventory location was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForLocationException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(DuplicateLocationException.class)
  ResponseEntity<ErrorResponse> duplicateLocation() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_LOCATION",
        "An inventory location with this code already exists for the plant.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(),
            UUID.randomUUID().toString()));
  }
}
