package com.syncro.sparepart.request.api;

import com.syncro.sparepart.request.api.SparepartRequestDtos.ErrorResponse;
import com.syncro.sparepart.request.application.SparepartRequestService.MachineNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.PriceEntryNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestValidationException;
import com.syncro.sparepart.request.application.SparepartRequestService.SparepartNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.WorkOrderNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.InvalidRequestStateTransitionException;
import com.syncro.sparepart.request.application.SparepartRequestService.SelfApprovalForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.DuplicateMaterialCodeException;
import com.syncro.sparepart.stock.application.SparepartStockService.NegativeStockRejectedException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import tools.jackson.databind.exc.InvalidFormatException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SparepartRequestController.class})
public class SparepartRequestExceptionHandler {

  private final Clock clock;

  public SparepartRequestExceptionHandler(Clock clock) {
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

  @ExceptionHandler(RequestValidationException.class)
  ResponseEntity<ErrorResponse> requestValidation(RequestValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(RequestForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(WorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> workOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Work order was not found.", Map.of());
  }

  /** Story 12-2: invalid or terminal state transition → 409 CONFLICT (mirrors WorkOrderExceptionHandler). */
  @ExceptionHandler(InvalidRequestStateTransitionException.class)
  ResponseEntity<ErrorResponse> invalidStateTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
        "Request is not in a state that allows this transition.", Map.of());
  }

  /** Story 12-2: unknown request id → 404 (mirrors WorkOrderExceptionHandler codes). */
  @ExceptionHandler(RequestNotFoundException.class)
  ResponseEntity<ErrorResponse> requestNotFound() {
    return error(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "Sparepart request was not found.", Map.of());
  }

  /** Story 12-3: requester approving their own request → 403 SELF_APPROVAL_FORBIDDEN (AD-16). */
  @ExceptionHandler(SelfApprovalForbiddenException.class)
  ResponseEntity<ErrorResponse> selfApprovalForbidden() {
    return error(HttpStatus.FORBIDDEN, "SELF_APPROVAL_FORBIDDEN",
        "You cannot approve your own request.", Map.of());
  }

  /** Story 12-4: completion material code already belongs to another sparepart → 409 (FR-144). */
  @ExceptionHandler(DuplicateMaterialCodeException.class)
  ResponseEntity<ErrorResponse> duplicateMaterialCode() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_MATERIAL_CODE",
        "The material code already belongs to another sparepart.", Map.of());
  }

  /**
   * Story 12-4: the PICKED_UP stock decrement would drive stock negative → 409. The
   * transition rolls back (no partial pickup) and the client sees the stock gate, not a 500.
   */
  @ExceptionHandler(NegativeStockRejectedException.class)
  ResponseEntity<ErrorResponse> negativeStockRejected() {
    return error(HttpStatus.CONFLICT, "NEGATIVE_STOCK_REJECTED",
        "The pickup would make stock negative.", Map.of());
  }

  /** Story 12-4: concurrent stock/request version conflict → 409. */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> optimisticLockConflict() {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
        "Concurrent update detected; reload and retry.", Map.of());
  }

  @ExceptionHandler(MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(SparepartNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  @ExceptionHandler(PriceEntryNotFoundException.class)
  ResponseEntity<ErrorResponse> priceEntryNotFound() {
    return error(HttpStatus.NOT_FOUND, "PRICE_ENTRY_NOT_FOUND", "Price entry was not found.", Map.of());
  }

  /**
   * FK race backstop: a referenced row deleted between the service load and insert surfaces
   * as a constraint violation. A client error — not a 500.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "A referenced resource no longer exists."));
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}