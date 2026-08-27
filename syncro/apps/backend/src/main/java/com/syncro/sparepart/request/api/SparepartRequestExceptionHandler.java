package com.syncro.sparepart.request.api;

import com.syncro.sparepart.request.api.SparepartRequestDtos.ErrorResponse;
import com.syncro.sparepart.request.application.SparepartRequestService.MachineNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestForbiddenException;
import com.syncro.sparepart.request.application.SparepartRequestService.RequestValidationException;
import com.syncro.sparepart.request.application.SparepartRequestService.SparepartNotFoundException;
import com.syncro.sparepart.request.application.SparepartRequestService.WorkOrderNotFoundException;
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

  @ExceptionHandler(MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(SparepartNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}