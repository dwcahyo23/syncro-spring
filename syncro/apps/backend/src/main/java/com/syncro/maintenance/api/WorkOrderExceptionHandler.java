package com.syncro.maintenance.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.maintenance.api.WorkOrderDtos.ErrorResponse;
import com.syncro.maintenance.application.WorkOrderAckService.AckAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderAckService.AckWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyDroppedException;
import com.syncro.maintenance.application.WorkAssignmentService.AssignmentAlreadyExistsException;
import com.syncro.maintenance.application.WorkAssignmentService.WorkAssignmentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.BreakdownCategoryRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ChildrenNotTerminalException;
import com.syncro.maintenance.application.WorkOrderService.DoneWithoutSessionReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.InvalidStateTransitionException;
import com.syncro.maintenance.application.WorkOrderService.NoOpenSessionException;
import com.syncro.maintenance.application.WorkOrderService.OverrideReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.ProcurementRequestConflictException;
import com.syncro.maintenance.application.WorkOrderService.SelfAssignmentForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.SessionAlreadyOpenException;
import com.syncro.maintenance.application.WorkOrderService.SessionOpenConflictException;
import com.syncro.maintenance.application.WorkOrderService.SessionOverlapException;
import com.syncro.maintenance.application.WorkOrderService.StopTimeReasonRequiredException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderCategoryNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderParentNotFoundException;
import com.syncro.maintenance.application.WorkOrderService.WorkOrderUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceAttachmentNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceForbiddenException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceWorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.StorageException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.ValidationException;
import com.syncro.maintenance.application.WorkOrderListService.WorkOrderListValidationException;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionInUseException;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatedUserNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingAlreadyExistsException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingForbiddenException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingValidationException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.UserNotExecutorException;
import com.syncro.maintenance.application.WorkOrderRatingService.WorkorderNotClosedException;
import com.syncro.maintenance.application.WorkOrderReportService.ReportForbiddenException;
import com.syncro.maintenance.application.WorkOrderReportService.ReportWorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderReportService.ReportWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderReportService.WorkOrderReportValidationException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoAlreadyCompletedException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoForbiddenException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoTechnicianNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoWorkOrderMachineNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.TodoWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTerminalException;
import com.syncro.maintenance.application.WorkOrderTodoService.WorkOrderTodoValidationException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderForbiddenException;
import com.syncro.maintenance.application.WorkOrderService.WorkorderNotInProgressException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureAlreadyExistsException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureForbiddenException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureMachineNotFoundException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureStorageException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureValidationException;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureWorkOrderNotFoundException;
import com.syncro.maintenance.application.WorkorderSignatureService.WorkorderNotTerminalException;
import com.syncro.maintenance.domain.workorder.WorkOrderIdGenerator.WorkorderIdExhaustedException;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.exc.InvalidFormatException;

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

  /**
   * Jackson wraps a type/enum mismatch (e.g. an unknown {@code toStatus}) inside
   * {@link HttpMessageNotReadableException}: that is a field validation error, so it maps
   * to 400 VALIDATION_ERROR with the offending field. Spring Boot 4 ships Jackson 3
   * ({@code tools.jackson}), so the cause-chain check targets that package. A path-less
   * invalid format or any other unreadable body stays MALFORMED_JSON.
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

  @ExceptionHandler(ConstraintViolationException.class)
  ResponseEntity<ErrorResponse> constraintViolation(ConstraintViolationException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var violation : exception.getConstraintViolations()) {
      fieldErrors.putIfAbsent(violation.getPropertyPath().toString(), violation.getMessage());
    }
    return error(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_TOO_LONG",
        "Idempotency-Key header must not exceed 64 characters.", fieldErrors);
  }

  @ExceptionHandler({WorkorderForbiddenException.class, EvidenceForbiddenException.class,
      ReportForbiddenException.class, TodoForbiddenException.class, PlantAccessDeniedException.class,
      RatingForbiddenException.class})
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

  // -------------------------------------------------------------------------
  // Work assignments (17-1, blueprint B3, AD-17)
  // -------------------------------------------------------------------------

  /** 409 on the uq_work_assignments_wo_tech_at unique-constraint friendly path. */
  @ExceptionHandler(AssignmentAlreadyExistsException.class)
  ResponseEntity<ErrorResponse> assignmentAlreadyExists() {
    return error(HttpStatus.CONFLICT, "ASSIGNMENT_ALREADY_EXISTS",
        "This technician is already assigned to this workorder at this time.", Map.of());
  }

  @ExceptionHandler(AssignmentAlreadyDroppedException.class)
  ResponseEntity<ErrorResponse> assignmentAlreadyDropped() {
    return error(HttpStatus.CONFLICT, "ASSIGNMENT_ALREADY_DROPPED",
        "This assignment has already been dropped.", Map.of());
  }

  @ExceptionHandler(WorkAssignmentNotFoundException.class)
  ResponseEntity<ErrorResponse> assignmentNotFound() {
    return error(HttpStatus.NOT_FOUND, "ASSIGNMENT_NOT_FOUND",
        "Assignment was not found.", Map.of());
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

  @ExceptionHandler(ProcurementRequestConflictException.class)
  ResponseEntity<ErrorResponse> procurementRequestConflict() {
    return error(HttpStatus.CONFLICT, "PROCUREMENT_REQUEST_CONFLICT",
        "A live non-ready sparepart request is in progress on this workorder.", Map.of());
  }

  @ExceptionHandler(ChildrenNotTerminalException.class)
  ResponseEntity<ErrorResponse> childrenNotTerminal() {
    return error(HttpStatus.CONFLICT, "CHILDREN_NOT_TERMINAL",
        "All child workorders must be closed or cancelled before closing this workorder.", Map.of());
  }

  @ExceptionHandler(OverrideReasonRequiredException.class)
  ResponseEntity<ErrorResponse> overrideReasonRequired() {
    return error(HttpStatus.BAD_REQUEST, "OVERRIDE_REASON_REQUIRED",
        "An override reason is required to close a workorder with non-terminal children.", Map.of());
  }

  /** DW-135: the id sequence is exhausted — 503 Service Unavailable, not a 500. */
  @ExceptionHandler(WorkorderIdExhaustedException.class)
  ResponseEntity<ErrorResponse> idExhausted() {
    return error(HttpStatus.SERVICE_UNAVAILABLE, "WORKORDER_ID_EXHAUSTED",
        "The monthly workorder id sequence is exhausted.", Map.of());
  }

  @ExceptionHandler(WorkorderNotInProgressException.class)
  ResponseEntity<ErrorResponse> workorderNotInProgress() {
    return error(HttpStatus.CONFLICT, "WORKORDER_NOT_IN_PROGRESS",
        "Repair sessions can only be started on an IN_PROGRESS workorder.", Map.of());
  }

  @ExceptionHandler(SessionAlreadyOpenException.class)
  ResponseEntity<ErrorResponse> sessionAlreadyOpen() {
    return error(HttpStatus.CONFLICT, "SESSION_ALREADY_OPEN",
        "A repair session is already open on this workorder.", Map.of());
  }

  @ExceptionHandler(SessionOverlapException.class)
  ResponseEntity<ErrorResponse> sessionOverlap() {
    return error(HttpStatus.CONFLICT, "SESSION_OVERLAP",
        "The new repair session would overlap an existing one.", Map.of());
  }

  @ExceptionHandler(NoOpenSessionException.class)
  ResponseEntity<ErrorResponse> noOpenSession() {
    return error(HttpStatus.CONFLICT, "NO_OPEN_SESSION",
        "There is no open repair session on this workorder.", Map.of());
  }

  @ExceptionHandler(SessionOpenConflictException.class)
  ResponseEntity<ErrorResponse> sessionOpenConflict() {
    return error(HttpStatus.CONFLICT, "SESSION_OPEN_CONFLICT",
        "The open repair session must be stopped before completing this workorder.", Map.of());
  }

  @ExceptionHandler(DoneWithoutSessionReasonRequiredException.class)
  ResponseEntity<ErrorResponse> doneWithoutSessionReasonRequired() {
    return error(HttpStatus.BAD_REQUEST, "DONE_WITHOUT_SESSION_REASON_REQUIRED",
        "A workorder with no completed repair session requires a documented reason to be completed.", Map.of());
  }

  // -------------------------------------------------------------------------
  // Evidence & technical drawings (10.5)
  // -------------------------------------------------------------------------

  @ExceptionHandler(EvidenceWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> evidenceWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(EvidenceWorkOrderMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> evidenceMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(EvidenceAttachmentNotFoundException.class)
  ResponseEntity<ErrorResponse> attachmentNotFound() {
    return error(HttpStatus.NOT_FOUND, "ATTACHMENT_NOT_FOUND", "Attachment was not found.", Map.of());
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<ErrorResponse> evidenceValidation(ValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  /** Mirrors the 8-4 sparepart-image handler: multipart failures are field errors. */
  @ExceptionHandler(MissingServletRequestPartException.class)
  ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getRequestPartName(), "This part is required."));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "This value is required."));
  }

  /** Mirrors the 8-4 sparepart-image handler: multipart failures are field errors. */
  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> oversizeUpload() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Uploaded file exceeds the maximum allowed size."));
  }

  /** A multipart request that is not multipart, or a malformed/truncated body. */
  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ErrorResponse> multipartError() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Request must be a well-formed multipart form-data upload."));
  }

  /** Request content-type is not multipart/form-data. */
  @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
  ResponseEntity<ErrorResponse> mediaTypeNotSupported() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Request must be sent as multipart/form-data."));
  }

  /**
   * A path/query parameter that cannot be converted to its target type (e.g.
   * /attachments/not-a-uuid, or a list query {@code status=NOT_A_STATUS} or
   * {@code machineId=not-a-uuid}). Field errors carry the actual parameter name so the
   * frontend can surface the offending filter.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> typeMismatch(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getName(), "Invalid value for this parameter."));
  }

  @ExceptionHandler({StorageException.class, com.syncro.maintenance.application.WorkOrderReportService.StorageException.class})
  ResponseEntity<ErrorResponse> objectStorageError() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR",
        "Object storage operation failed.", Map.of());
  }

  // -------------------------------------------------------------------------
  // Report, CP/CPK, FMEA & stop-time (10.6)
  // -------------------------------------------------------------------------

  @ExceptionHandler(ReportWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> reportWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(ReportWorkOrderMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> reportMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderReportValidationException.class)
  ResponseEntity<ErrorResponse> reportValidation(WorkOrderReportValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(StopTimeReasonRequiredException.class)
  ResponseEntity<ErrorResponse> stopTimeReasonRequired() {
    return error(HttpStatus.BAD_REQUEST, "STOP_TIME_REASON_REQUIRED",
        "A breakdown workorder requires a stop-time reason to be completed.", Map.of());
  }

  // -------------------------------------------------------------------------
  // Todos & kanban (10.7)
  // -------------------------------------------------------------------------

  @ExceptionHandler(TodoWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> todoWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(TodoWorkOrderMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> todoMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(TodoNotFoundException.class)
  ResponseEntity<ErrorResponse> todoNotFound() {
    return error(HttpStatus.NOT_FOUND, "TODO_NOT_FOUND", "Todo was not found.", Map.of());
  }

  @ExceptionHandler(TodoTechnicianNotFoundException.class)
  ResponseEntity<ErrorResponse> todoTechnicianNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Assigned technician was not found.", Map.of());
  }

  @ExceptionHandler(WorkOrderTerminalException.class)
  ResponseEntity<ErrorResponse> workOrderTerminal() {
    return error(HttpStatus.BAD_REQUEST, "WORKORDER_TERMINAL",
        "Workorder is in a terminal state. Todos cannot be mutated.", Map.of());
  }

  @ExceptionHandler(TodoAlreadyCompletedException.class)
  ResponseEntity<ErrorResponse> todoAlreadyCompleted() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Todo is already completed.",
        Map.of("status", "Todo is already completed."));
  }

  @ExceptionHandler(WorkOrderTodoValidationException.class)
  ResponseEntity<ErrorResponse> todoValidation(WorkOrderTodoValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  // -------------------------------------------------------------------------
  // Workorder list table (workorder-table story)
  // -------------------------------------------------------------------------

  /** Invalid list query (bad date format, page/size out of range) → 400 with fieldErrors. */
  @ExceptionHandler(WorkOrderListValidationException.class)
  ResponseEntity<ErrorResponse> listValidation(WorkOrderListValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  // -------------------------------------------------------------------------
  // Ratings & rating dimensions (10.8, FR-121/FR-124)
  // -------------------------------------------------------------------------

  @ExceptionHandler(RatingWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> ratingWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(RatingMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> ratingMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(RatedUserNotFoundException.class)
  ResponseEntity<ErrorResponse> ratedUserNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "Rated user was not found.", Map.of());
  }

  @ExceptionHandler(WorkorderNotClosedException.class)
  ResponseEntity<ErrorResponse> workorderNotClosed() {
    return error(HttpStatus.BAD_REQUEST, "RATING_WORKORDER_NOT_CLOSED",
        "Only closed workorders can be rated.", Map.of());
  }

  @ExceptionHandler(UserNotExecutorException.class)
  ResponseEntity<ErrorResponse> userNotExecutor() {
    return error(HttpStatus.BAD_REQUEST, "RATING_USER_NOT_EXECUTOR",
        "The rated user must have executed the workorder.", Map.of());
  }

  @ExceptionHandler(RatingAlreadyExistsException.class)
  ResponseEntity<ErrorResponse> ratingAlreadyExists() {
    return error(HttpStatus.CONFLICT, "RATING_ALREADY_EXISTS",
        "This workorder has already been rated by the same rater for this target.", Map.of());
  }

  @ExceptionHandler(RatingValidationException.class)
  ResponseEntity<ErrorResponse> ratingValidation(RatingValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(DimensionNotFoundException.class)
  ResponseEntity<ErrorResponse> dimensionNotFound() {
    return error(HttpStatus.NOT_FOUND, "RATING_DIMENSION_NOT_FOUND", "Rating dimension was not found.", Map.of());
  }

  @ExceptionHandler(DimensionInUseException.class)
  ResponseEntity<ErrorResponse> dimensionInUse() {
    return error(HttpStatus.BAD_REQUEST, "RATING_DIMENSION_IN_USE",
        "A rating dimension referenced by existing scores cannot be deleted.", Map.of());
  }

  // -------------------------------------------------------------------------
  // 4-hour acknowledgment (14-4, FR-181)
  // -------------------------------------------------------------------------

  @ExceptionHandler(AckWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> ackWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(AckAlreadyExistsException.class)
  ResponseEntity<ErrorResponse> ackAlreadyExists() {
    return error(HttpStatus.CONFLICT, "ACK_ALREADY_EXISTS",
        "This workorder has already been acknowledged.", Map.of());
  }

  // -------------------------------------------------------------------------
  // Print report & signature (14-3, FR-175)
  // -------------------------------------------------------------------------

  @ExceptionHandler(SignatureForbiddenException.class)
  ResponseEntity<ErrorResponse> signatureForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to approve this workorder.", Map.of());
  }

  @ExceptionHandler(SignatureWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> signatureWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(SignatureMachineNotFoundException.class)
  ResponseEntity<ErrorResponse> signatureMachineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(SignatureValidationException.class)
  ResponseEntity<ErrorResponse> signatureValidation(SignatureValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(WorkorderNotTerminalException.class)
  ResponseEntity<ErrorResponse> workorderNotTerminal() {
    return error(HttpStatus.BAD_REQUEST, "WORKORDER_NOT_TERMINAL",
        "Only DONE or CLOSED workorders can be signed.", Map.of());
  }

  @ExceptionHandler(SignatureAlreadyExistsException.class)
  ResponseEntity<ErrorResponse> signatureAlreadyExists() {
    return error(HttpStatus.CONFLICT, "ALREADY_SIGNED",
        "This workorder has already been signed.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.application.WorkorderPrintReportService.PrintReportWorkOrderNotFoundException.class)
  ResponseEntity<ErrorResponse> printReportWorkOrderNotFound() {
    return error(HttpStatus.NOT_FOUND, "WORKORDER_NOT_FOUND", "Workorder was not found.", Map.of());
  }

  @ExceptionHandler(com.syncro.maintenance.application.WorkorderPrintReportService.PrintReportStorageException.class)
  ResponseEntity<ErrorResponse> printReportStorageError() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR",
        "Object storage operation failed.", Map.of());
  }

  @ExceptionHandler(SignatureStorageException.class)
  ResponseEntity<ErrorResponse> signatureStorageError() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR",
        "Object storage operation failed.", Map.of());
  }

  /**
   * 10.4 DB-constraint backstop: a concurrent session insert that slips past the service
   * overlap pre-check hits the {@code excl_repair_sessions_no_overlap} gist EXCLUDE
   * constraint (and any OTHER insert integrity error becomes a generic 500 — never
   * mislabeled). Same cause-chain walk as {@code WorkOrderService.isIdempotencyKeyViolation}.
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ErrorResponse> sessionOverlapConstraint(DataIntegrityViolationException exception) {
    var cause = exception.getCause();
    while (cause != null) {
      if (cause instanceof org.hibernate.exception.ConstraintViolationException constraint
          && "excl_repair_sessions_no_overlap".equalsIgnoreCase(constraint.getConstraintName())) {
        return error(HttpStatus.CONFLICT, "SESSION_OVERLAP",
            "The new repair session would overlap an existing one.", Map.of());
      }
      cause = cause.getCause();
    }
    throw exception;
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
