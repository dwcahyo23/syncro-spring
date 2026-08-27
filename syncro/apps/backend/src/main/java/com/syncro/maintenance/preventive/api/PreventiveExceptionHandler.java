package com.syncro.maintenance.preventive.api;

import com.syncro.maintenance.preventive.api.PreventiveDtos.ErrorResponse;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistAlreadySubmittedException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistNotSubmittedException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistValidationException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.InvalidStateTransitionException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.MissingSignatureException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ScheduleNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceAttachmentNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceScheduleNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceStorageException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceValidationException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.MachineNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveValidationException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.ProgramNotFoundException;
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
@RestControllerAdvice(assignableTypes = {PreventiveProgramController.class, PreventiveScheduleController.class})
public class PreventiveExceptionHandler {

  private final Clock clock;

  public PreventiveExceptionHandler(Clock clock) {
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

  /**
   * Jackson wraps a type/enum mismatch (e.g. an unknown {@code category} or
   * {@code scheduleType}) inside {@link HttpMessageNotReadableException}: that is a
   * field validation error, so it maps to 400 VALIDATION_ERROR with the offending field.
   */
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

  @ExceptionHandler(PreventiveValidationException.class)
  ResponseEntity<ErrorResponse> preventiveValidation(PreventiveValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(PreventiveForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(ProgramNotFoundException.class)
  ResponseEntity<ErrorResponse> programNotFound() {
    return error(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND", "Preventive program was not found.", Map.of());
  }

  @ExceptionHandler(ScheduleNotFoundException.class)
  ResponseEntity<ErrorResponse> scheduleNotFound() {
    return error(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "Preventive schedule was not found.", Map.of());
  }

  @ExceptionHandler(InvalidStateTransitionException.class)
  ResponseEntity<ErrorResponse> invalidState(InvalidStateTransitionException exception) {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "The schedule is not in the expected state for this action.", Map.of());
  }

  @ExceptionHandler(ChecklistAlreadySubmittedException.class)
  ResponseEntity<ErrorResponse> checklistAlreadySubmitted() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION", "A checklist has already been submitted for this schedule.", Map.of());
  }

  @ExceptionHandler(MissingSignatureException.class)
  ResponseEntity<ErrorResponse> missingSignature() {
    var fieldErrors = new LinkedHashMap<String, String>();
    fieldErrors.put("signatureObjectKey", "Signature object key is required for approval.");
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(ChecklistValidationException.class)
  ResponseEntity<ErrorResponse> checklistValidation(ChecklistValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(EvidenceValidationException.class)
  ResponseEntity<ErrorResponse> evidenceValidation(EvidenceValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler({ChecklistForbiddenException.class, EvidenceForbiddenException.class})
  ResponseEntity<ErrorResponse> checklistForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(EvidenceScheduleNotFoundException.class)
  ResponseEntity<ErrorResponse> evidenceScheduleNotFound() {
    return error(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "Preventive schedule was not found.", Map.of());
  }

  @ExceptionHandler(EvidenceAttachmentNotFoundException.class)
  ResponseEntity<ErrorResponse> evidenceAttachmentNotFound() {
    return error(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Evidence attachment was not found.", Map.of());
  }

  @ExceptionHandler(ChecklistNotSubmittedException.class)
  ResponseEntity<ErrorResponse> checklistNotSubmitted() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE_TRANSITION",
        "No checklist has been submitted for this schedule.", Map.of());
  }

  @ExceptionHandler(EvidenceStorageException.class)
  ResponseEntity<ErrorResponse> evidenceStorageError() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR",
        "File storage operation failed. Please try again.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}