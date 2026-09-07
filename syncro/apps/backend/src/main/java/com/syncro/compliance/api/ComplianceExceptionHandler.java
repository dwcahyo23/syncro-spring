package com.syncro.compliance.api;

import com.syncro.compliance.application.CalibrationService.CalibrationInstrumentNotFoundException;
import com.syncro.compliance.application.CalibrationService.CalibrationRecordNotFoundException;
import com.syncro.compliance.application.EightDReportService.EightDConflictException;
import com.syncro.compliance.application.EightDReportService.EightDReportNotFoundException;
import com.syncro.compliance.application.EquipmentChangeNoticeService.EquipmentChangeNoticeNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.application.NonConformanceService.InvalidStateTransitionException;
import com.syncro.compliance.application.NonConformanceService.NonConformanceNotFoundException;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Stable error envelope for the compliance API (stories 21-1/21-2,
 * KpiExceptionHandler pattern): code/message/fieldErrors/timestamp/traceId —
 * machine-readable codes FORBIDDEN (403), VALIDATION_ERROR (400),
 * NON_CONFORMANCE_NOT_FOUND / EIGHT_D_REPORT_NOT_FOUND / INSTRUMENT_NOT_FOUND /
 * CALIBRATION_RECORD_NOT_FOUND / ECN_NOT_FOUND / *_NOT_FOUND (404),
 * DUPLICATE_IDENTIFIER / INVALID_STATE_TRANSITION / EIGHT_D_CONFLICT /
 * VERSION_CONFLICT (409). Never exception names.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {NonConformanceController.class,
    CalibrationController.class, EquipmentChangeNoticeController.class})
public class ComplianceExceptionHandler {

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }

  private final Clock clock;

  public ComplianceExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(ComplianceForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var error : exception.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), "Invalid value.");
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(ComplianceValidationException.class)
  ResponseEntity<ErrorResponse> businessValidation(ComplianceValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var violation : exception.getConstraintViolations()) {
      fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), "Invalid value.");
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of());
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "Invalid value."));
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidValue(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of());
  }

  @ExceptionHandler(NonConformanceNotFoundException.class)
  ResponseEntity<ErrorResponse> ncNotFound() {
    return error(HttpStatus.NOT_FOUND, "NON_CONFORMANCE_NOT_FOUND",
        "Non-conformance was not found.", Map.of());
  }

  @ExceptionHandler(EightDReportNotFoundException.class)
  ResponseEntity<ErrorResponse> reportNotFound() {
    return error(HttpStatus.NOT_FOUND, "EIGHT_D_REPORT_NOT_FOUND",
        "8D report was not found for this non-conformance.", Map.of());
  }

  @ExceptionHandler(ComplianceReferenceNotFoundException.class)
  ResponseEntity<ErrorResponse> referenceNotFound(ComplianceReferenceNotFoundException exception) {
    return error(HttpStatus.NOT_FOUND, exception.getCode(), exception.getMessage(), Map.of());
  }

  @ExceptionHandler(DuplicateIdentifierException.class)
  ResponseEntity<ErrorResponse> duplicateIdentifier() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_IDENTIFIER",
        "This business identifier is already in use.", Map.of());
  }

  @ExceptionHandler(InvalidStateTransitionException.class)
  ResponseEntity<ErrorResponse> invalidTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
        "The requested status transition is not allowed.", Map.of());
  }

  @ExceptionHandler(EightDConflictException.class)
  ResponseEntity<ErrorResponse> eightDConflict() {
    return error(HttpStatus.CONFLICT, "EIGHT_D_CONFLICT",
        "This non-conformance already has an 8D report.", Map.of());
  }

  @ExceptionHandler(CalibrationInstrumentNotFoundException.class)
  ResponseEntity<ErrorResponse> instrumentNotFound() {
    return error(HttpStatus.NOT_FOUND, "INSTRUMENT_NOT_FOUND",
        "Calibration instrument was not found.", Map.of());
  }

  @ExceptionHandler(CalibrationRecordNotFoundException.class)
  ResponseEntity<ErrorResponse> calibrationRecordNotFound() {
    return error(HttpStatus.NOT_FOUND, "CALIBRATION_RECORD_NOT_FOUND",
        "Calibration record was not found for this instrument.", Map.of());
  }

  @ExceptionHandler(EquipmentChangeNoticeNotFoundException.class)
  ResponseEntity<ErrorResponse> ecnNotFound() {
    return error(HttpStatus.NOT_FOUND, "ECN_NOT_FOUND",
        "Equipment change notice was not found.", Map.of());
  }

  /** Review 21-1 P6: @Version lost updates surface as 409, never 500. */
  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> optimisticLockConflict() {
    return error(HttpStatus.CONFLICT, "VERSION_CONFLICT",
        "Concurrent update detected; reload and retry.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status).body(new ErrorResponse(code, message, fieldErrors,
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
