package com.syncro.sparepart.stock.api;

import com.syncro.inventory.application.InventoryStockService.DefaultLocationNotFoundException;
import com.syncro.inventory.application.InventoryStockService.InventoryStockForbiddenException;
import com.syncro.inventory.application.InventoryStockService.InventoryStockValidationException;
import com.syncro.inventory.application.InventoryStockService.NegativeStockRejectedException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryStockService.StockBalanceNotFoundException;
import com.syncro.inventory.application.InventoryStockService.VersionConflictException;
import com.syncro.sparepart.stock.api.SparepartStockDtos.ErrorResponse;
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
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.InvalidFormatException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SparepartStockController.class})
public class SparepartStockExceptionHandler {

  private final Clock clock;

  public SparepartStockExceptionHandler(Clock clock) {
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

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson(HttpMessageNotReadableException exception) {
    var invalidFormat = findInvalidFormat(exception);
    if (invalidFormat != null) {
      var fieldErrors = new LinkedHashMap<String, String>();
      for (var reference : invalidFormat.getPath()) {
        var propertyName = reference.getPropertyName();
        if (propertyName != null) {
          fieldErrors.putIfAbsent(propertyName, "Invalid value.");
        }
      }
      if (!fieldErrors.isEmpty()) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
      }
    }
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  private InvalidFormatException findInvalidFormat(Throwable throwable) {
    var current = throwable;
    while (current != null) {
      if (current instanceof InvalidFormatException invalidFormat) {
        return invalidFormat;
      }
      current = current.getCause();
    }
    return null;
  }

  @ExceptionHandler(InventoryStockValidationException.class)
  ResponseEntity<ErrorResponse> stockValidation(InventoryStockValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(InventoryStockForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(StockBalanceNotFoundException.class)
  ResponseEntity<ErrorResponse> stockNotFound() {
    return error(HttpStatus.NOT_FOUND, "STOCK_NOT_FOUND", "Stock balance was not found.", Map.of());
  }

  @ExceptionHandler(DefaultLocationNotFoundException.class)
  ResponseEntity<ErrorResponse> defaultLocationNotFound() {
    return error(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND",
        "The plant's default inventory location was not found.", Map.of());
  }

  @ExceptionHandler(SparepartNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  /** Atomic conditional update matched 0 rows → stock would go negative. */
  @ExceptionHandler(NegativeStockRejectedException.class)
  ResponseEntity<ErrorResponse> negativeStockRejected() {
    return error(HttpStatus.CONFLICT, "NEGATIVE_STOCK_REJECTED",
        "The adjustment would make stock negative.", Map.of());
  }

  /** Optimistic-lock mismatch on PUT. */
  @ExceptionHandler(VersionConflictException.class)
  ResponseEntity<ErrorResponse> versionConflict() {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
        "Concurrent update detected; reload and retry.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(),
            UUID.randomUUID().toString()));
  }
}
