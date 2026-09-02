package com.syncro.inventory.api;

import com.syncro.inventory.api.InventoryLocationDtos.ErrorResponse;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryTransferService.InsufficientStockException;
import com.syncro.inventory.application.InventoryTransferService.InvalidTransferTransitionException;
import com.syncro.inventory.application.InventoryTransferService.InventoryTransferNotFoundException;
import com.syncro.inventory.application.InventoryTransferService.TransferForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferSelfReviewForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferValidationException;
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
 * Error mapping for the inventory-transfers API (story 18-4). One stable shape
 * (code/message/fieldErrors/timestamp/traceId) — machine-readable codes:
 * INVALID_TRANSFER_TRANSITION (409), INSUFFICIENT_STOCK (409),
 * TRANSFER_SELF_REVIEW_FORBIDDEN (403), INVENTORY_LOCATION_NOT_FOUND (404),
 * SPAREPART_NOT_FOUND (404), INVENTORY_TRANSFER_NOT_FOUND (404), FORBIDDEN (403),
 * VALIDATION_ERROR (400).
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = InventoryTransferController.class)
public class InventoryTransferExceptionHandler {

  private final Clock clock;

  public InventoryTransferExceptionHandler(Clock clock) {
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

  @ExceptionHandler(TransferValidationException.class)
  ResponseEntity<ErrorResponse> transferValidation(TransferValidationException exception) {
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

  @ExceptionHandler(TransferForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  /** SoD: the requester can never review their own transfer. */
  @ExceptionHandler(TransferSelfReviewForbiddenException.class)
  ResponseEntity<ErrorResponse> selfReviewForbidden() {
    return error(HttpStatus.FORBIDDEN, "TRANSFER_SELF_REVIEW_FORBIDDEN",
        "The requester cannot review their own transfer.", Map.of());
  }

  @ExceptionHandler(InventoryTransferNotFoundException.class)
  ResponseEntity<ErrorResponse> transferNotFound() {
    return error(HttpStatus.NOT_FOUND, "INVENTORY_TRANSFER_NOT_FOUND",
        "Inventory transfer was not found.", Map.of());
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

  /** Review on a terminal transfer → 409. */
  @ExceptionHandler(InvalidTransferTransitionException.class)
  ResponseEntity<ErrorResponse> invalidTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_TRANSFER_TRANSITION",
        "The transfer is not pending approval.", Map.of());
  }

  /** Deadlock, DB integrity violation, or lock timeout → 409 TRANSFER_CONFLICT. */
  @ExceptionHandler({DataIntegrityViolationException.class, PessimisticLockingFailureException.class})
  ResponseEntity<ErrorResponse> transferConflict() {
    return error(HttpStatus.CONFLICT, "TRANSFER_CONFLICT",
        "A transfer conflict occurred. Please retry the request.", Map.of());
  }

  /** Source debit matched 0 rows → the whole move rolled back. */
  @ExceptionHandler(InsufficientStockException.class)
  ResponseEntity<ErrorResponse> insufficientStock() {
    return error(HttpStatus.CONFLICT, "INSUFFICIENT_STOCK",
        "The source location does not have enough available stock.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(),
            UUID.randomUUID().toString()));
  }
}
