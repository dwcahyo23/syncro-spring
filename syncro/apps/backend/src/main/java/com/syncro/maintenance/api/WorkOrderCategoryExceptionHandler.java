package com.syncro.maintenance.api;

import com.syncro.maintenance.api.WorkOrderCategoryDtos.ErrorResponse;
import com.syncro.maintenance.application.WorkOrderCategoryService.DuplicateWorkOrderCategoryCodeException;
import com.syncro.maintenance.application.WorkOrderCategoryService.WorkOrderCategoryMutationForbiddenException;
import com.syncro.maintenance.application.WorkOrderCategoryService.WorkOrderCategoryNotFoundException;
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
@RestControllerAdvice(assignableTypes = WorkOrderCategoryController.class)
public class WorkOrderCategoryExceptionHandler {

  private final Clock clock;

  public WorkOrderCategoryExceptionHandler(Clock clock) {
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

  @ExceptionHandler(WorkOrderCategoryMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(DuplicateWorkOrderCategoryCodeException.class)
  ResponseEntity<ErrorResponse> duplicateCode() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_CATEGORY_CODE", "A category with this code already exists.", Map.of());
  }

  @ExceptionHandler(WorkOrderCategoryNotFoundException.class)
  ResponseEntity<ErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "Category was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
