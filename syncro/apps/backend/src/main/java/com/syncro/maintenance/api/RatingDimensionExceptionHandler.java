package com.syncro.maintenance.api;

import com.syncro.maintenance.api.WorkOrderDtos.ErrorResponse;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionInUseException;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.DuplicateDimensionCodeException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingForbiddenException;
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

/**
 * Rating-dimension error mapping (story 10-8, AD-14). Dimension mutations are
 * SUPER_ADMIN-only (service-gated, mirroring OPA default-deny); reads are any-
 * authenticated. {@link WorkOrderExceptionHandler} covers the workorder-rating
 * endpoints under {@code WorkOrderController}; this handler is scoped to the
 * dimension controller.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = RatingDimensionController.class)
public class RatingDimensionExceptionHandler {

  private final Clock clock;

  public RatingDimensionExceptionHandler(Clock clock) {
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

  @ExceptionHandler(RatingForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(DuplicateDimensionCodeException.class)
  ResponseEntity<ErrorResponse> duplicateCode() {
    return error(HttpStatus.CONFLICT, "RATING_DIMENSION_CODE_EXISTS",
        "A rating dimension with this code already exists.", Map.of());
  }

  @ExceptionHandler(DimensionNotFoundException.class)
  ResponseEntity<ErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "RATING_DIMENSION_NOT_FOUND", "Rating dimension was not found.", Map.of());
  }

  @ExceptionHandler(DimensionInUseException.class)
  ResponseEntity<ErrorResponse> inUse() {
    return error(HttpStatus.BAD_REQUEST, "RATING_DIMENSION_IN_USE",
        "A rating dimension referenced by existing scores cannot be deleted.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
