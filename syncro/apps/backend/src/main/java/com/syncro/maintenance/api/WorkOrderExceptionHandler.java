package com.syncro.maintenance.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.maintenance.api.WorkOrderDtos.ErrorResponse;
import com.syncro.maintenance.application.WorkOrderService.BreakdownCategoryRequiredException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderParentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
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

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = WorkOrderController.class)
public class WorkOrderExceptionHandler {

  private final Clock clock;

  public WorkOrderExceptionHandler(Clock clock) {
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

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var violation : exception.getConstraintViolations()) {
      fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
    }
    return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_TOO_LONG",
        "Idempotency-Key header must not exceed 64 characters.", fieldErrors);
  }

  @ExceptionHandler({WorkorderForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(BreakdownCategoryRequiredException.class)
  ResponseEntity<ErrorResponse> breakdownCategoryRequired() {
    return error(HttpStatus.FORBIDDEN, "BREAKDOWN_CATEGORY_REQUIRED",
        "Production leaders may only open Breakdown (01) workorders.", Map.of());
  }

  @ExceptionHandler(WorkOrderMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderCategoryNotFoundException.class)
  ResponseEntity<ErrorResponse> categoryNotFound() {
    return error(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Work-order category was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderParentNotFoundException.class)
  ResponseEntity<ErrorResponse> parentNotFound() {
    return error(HttpStatus.NOT_FOUND, "PARENT_NOT_FOUND", "Parent workorder was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> workOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderUserNotFoundException.class)
  ResponseEntity<ErrorResponse> userNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Assignee was not found.", Map.of());
  }

  @ExceptionHandler(InvalidStateTransitionException.class)
  ResponseEntity<ErrorResponse> invalidStateTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
        "Workorder is not in a state that allows this transition.", Map.of());
  }

  @ExceptionHandler(SelfAssignmentForbiddenException.class)
  ResponseEntity<ErrorResponse> selfAssignment() {
    return error(HttpStatus.CONFLICT, "SELF_ASSIGNMENT_FORBIDDEN",
        "Section leaders cannot assign workorders to themselves.", Map.of());
  }

  /** DW-135: the id sequence is exhausted — 503 Service Unavailable, not a 500. */
  @ExceptionHandler(WorkorderIdExhaustedException.class)
  ResponseEntity<ErrorResponse> idExhausted() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "WORKORDER_ID_EXHAUSTED",
        "The monthly workorder id sequence is exhausted.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
