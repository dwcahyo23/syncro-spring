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
import com.syncro.maintenance.preventive.application.PreventiveReportService.ReportStorageException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.MachineNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveForbiddenException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.PreventiveValidationException;
import com.syncro.maintenance.preventive.application.PreventiveProgramService.ProgramNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecklistService.CalibrationInstrumentNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecklistService.PmChecklistCategoryNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecklistService.PmChecklistItemNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ActiveChecksheetNotFoundException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ChecksheetAlreadyExistsException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.ChecksheetValidationException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.InvalidChecksheetTransitionException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.PmChecksheetForbiddenException;
import com.syncro.maintenance.preventive.application.PmChecksheetService.PmChecksheetNotFoundException;
import com.syncro.maintenance.preventive.application.PmFrequencyService.DuplicateFrequencyCodeException;
import com.syncro.maintenance.preventive.application.PmFrequencyService.FrequencyValidationException;
import com.syncro.maintenance.preventive.application.PmFrequencyService.PmFrequencyForbiddenException;
import com.syncro.maintenance.preventive.application.PmFrequencyService.PmFrequencyNotFoundException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidFormatException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {PreventiveProgramController.class, PreventiveScheduleController.class,
    PmFrequencyController.class, PmChecksheetController.class, PmChecklistCategoryController.class,
    PmChecklistItemController.class})
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

  /**
   * Malformed query/path value (e.g. a non-UUID {@code machineId} on
   * GET /pm-checksheets/active) must not leak Spring's default body — map to the
   * standard envelope (InventoryLocationExceptionHandler parity).
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidArgumentValue(MethodArgumentTypeMismatchException exception) {
    if (exception.getParameter() != null
        && exception.getParameter().hasParameterAnnotation(RequestParam.class)) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY_VALUE", "Query value is invalid.", Map.of());
    }
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  /** Missing required query param (e.g. machineId/frequencyId) → 400 VALIDATION_ERROR. */
  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParameter(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "Invalid value."));
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

  @ExceptionHandler(com.syncro.maintenance.preventive.application.PreventiveReportService.ScheduleNotFoundException.class)
  ResponseEntity<ErrorResponse> reportScheduleNotFound() {
    return error(HttpStatus.NOT_FOUND, "SCHEDULE_NOT_FOUND", "Preventive schedule was not found.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.preventive.application.PreventiveReportService.ProgramNotFoundException.class)
  ResponseEntity<ErrorResponse> reportProgramNotFound() {
    return error(HttpStatus.NOT_FOUND, "PROGRAM_NOT_FOUND", "Preventive program was not found.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.preventive.application.PreventiveReportService.MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> reportMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(ReportStorageException.class)
  ResponseEntity<ErrorResponse> reportStorageError() {
    return error(HttpStatus.INTERNAL_SERVER_ERROR, "STORAGE_ERROR",
        "File storage operation failed. Please try again.", Map.of());
  }

  // -------------------------------------------------------------------------
  // Story 19-1: PM frequencies & checksheets
  // -------------------------------------------------------------------------

  @ExceptionHandler(PmFrequencyForbiddenException.class)
  ResponseEntity<ErrorResponse> pmFrequencyForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(DuplicateFrequencyCodeException.class)
  ResponseEntity<ErrorResponse> duplicateFrequencyCode() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_FREQUENCY_CODE",
        "A PM frequency with this code already exists.", Map.of());
  }

  @ExceptionHandler(PmFrequencyNotFoundException.class)
  ResponseEntity<ErrorResponse> pmFrequencyNotFound() {
    return error(HttpStatus.NOT_FOUND, "PM_FREQUENCY_NOT_FOUND",
        "PM frequency was not found.", Map.of());
  }

  @ExceptionHandler(FrequencyValidationException.class)
  ResponseEntity<ErrorResponse> frequencyValidation(FrequencyValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(PmChecksheetForbiddenException.class)
  ResponseEntity<ErrorResponse> pmChecksheetForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(PmChecksheetNotFoundException.class)
  ResponseEntity<ErrorResponse> pmChecksheetNotFound() {
    return error(HttpStatus.NOT_FOUND, "PM_CHECKSHEET_NOT_FOUND",
        "PM checksheet was not found.", Map.of());
  }

  @ExceptionHandler(ActiveChecksheetNotFoundException.class)
  ResponseEntity<ErrorResponse> activeChecksheetNotFound() {
    return error(HttpStatus.NOT_FOUND, "PM_CHECKSHEET_NOT_FOUND",
        "No active PM checksheet was found for this machine and frequency.", Map.of());
  }

  @ExceptionHandler(ChecksheetAlreadyExistsException.class)
  ResponseEntity<ErrorResponse> checksheetAlreadyExists() {
    return error(HttpStatus.CONFLICT, "CHECKSHEET_ALREADY_EXISTS",
        "A PM checksheet already exists for this machine and frequency.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.preventive.application.PmChecksheetService.MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> pmChecksheetMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(InvalidChecksheetTransitionException.class)
  ResponseEntity<ErrorResponse> invalidChecksheetTransition() {
    return error(HttpStatus.CONFLICT, "INVALID_CHECKSHEET_TRANSITION",
        "The checksheet is not in the expected state for this action.", Map.of());
  }

  @ExceptionHandler(ChecksheetValidationException.class)
  ResponseEntity<ErrorResponse> checksheetValidation(ChecksheetValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  // -------------------------------------------------------------------------
  // Story 19-2: PM checklist categories & items
  // -------------------------------------------------------------------------

  @ExceptionHandler(PmChecklistCategoryNotFoundException.class)
  ResponseEntity<ErrorResponse> pmChecklistCategoryNotFound() {
    return error(HttpStatus.NOT_FOUND, "PM_CHECKLIST_CATEGORY_NOT_FOUND",
        "PM checklist category was not found.", Map.of());
  }

  @ExceptionHandler(PmChecklistItemNotFoundException.class)
  ResponseEntity<ErrorResponse> pmChecklistItemNotFound() {
    return error(HttpStatus.NOT_FOUND, "PM_CHECKLIST_ITEM_NOT_FOUND",
        "PM checklist item was not found.", Map.of());
  }

  @ExceptionHandler(CalibrationInstrumentNotFoundException.class)
  ResponseEntity<ErrorResponse> calibrationInstrumentNotFound() {
    return error(HttpStatus.NOT_FOUND, "CALIBRATION_INSTRUMENT_NOT_FOUND",
        "Calibration instrument was not found.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.preventive.application.PmChecklistService.ChecklistValidationException.class)
  ResponseEntity<ErrorResponse> checklistItemValidation(
      com.syncro.maintenance.preventive.application.PmChecklistService.ChecklistValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
