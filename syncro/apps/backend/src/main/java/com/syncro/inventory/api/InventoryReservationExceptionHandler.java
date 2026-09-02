package com.syncro.inventory.api;

import com.syncro.inventory.api.InventoryReservationDtos.ErrorResponse;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService.InsufficientStockException;
import com.syncro.inventory.application.InventoryReservationService.InvalidReservationTransitionException;
import com.syncro.inventory.application.InventoryReservationService.InventoryReservationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService.ReservationForbiddenException;
import com.syncro.inventory.application.InventoryReservationService.ReservationValidationException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Error mapping for the inventory-reservations API (story 18-5). One stable shape
 * (code/message/fieldErrors/timestamp/traceId) — machine-readable codes:
 * INSUFFICIENT_STOCK (409), INVALID_RESERVATION_TRANSITION (409),
 * INVENTORY_RESERVATION_NOT_FOUND (404), INVENTORY_LOCATION_NOT_FOUND (404),
 * SPAREPART_NOT_FOUND (404), FORBIDDEN (403), VALIDATION_ERROR (400).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InventoryReservationController.class)
public class InventoryReservationExceptionHandler {

  private final Clock clock;

  public InventoryReservationExceptionHandler(Clock clock) {
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

  @ExceptionHandler(ReservationValidationException.class)
  ResponseEntity<ErrorResponse> reservationValidation(ReservationValidationException exception) {
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

  @ExceptionHandler(ReservationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(InventoryReservationNotFoundException.class)
  ResponseEntity<ErrorResponse> reservationNotFound() {
    return error(HttpStatus.NOT_FOUND, "INVENTORY_RESERVATION_NOT_FOUND",
        "Inventory reservation was not found.", Map.of());
  }

  @ExceptionHandler(InventoryLocationNotFoundException.class)
  ResponseEntity<ErrorResponse> locationNotFound() {
    return error(HttpStatus.NOT_FOUND, "INVENTORY_LOCATION_NOT_FOUND",
        "Inventory location was not found.", Map.of());
  }

  @ExceptionHandler(SparepartNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  /** Transition on a terminal reservation → 409. */
  @ExceptionHandler(InvalidReservationTransitionException.class)
  ResponseEntity<ErrorResponse> invalidTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_RESERVATION_TRANSITION",
        "The reservation is not in an ACTIVE state.", Map.of());
  }

  /** Deadlock, DB integrity violation, or lock timeout → 409 RESERVATION_CONFLICT. */
  @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
  ResponseEntity<ErrorResponse> reservationConflict() {
    return error(HttpStatus.CONFLICT, "RESERVATION_CONFLICT",
        "A reservation conflict occurred. Please retry the request.", Map.of());
  }

  /** Balance move matched 0 rows → the whole transaction rolled back. */
  @ExceptionHandler(InsufficientStockException.class)
  ResponseEntity<ErrorResponse> insufficientStock() {
    return error(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
        "Not enough available stock to reserve.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(),
            UUID.randomUUID().toString()));
  }
}