package com.syncro.masterdata.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.masterdata.api.PlantDtos.ErrorResponse;
import com.syncro.masterdata.application.MachineGroupService.DuplicateMachineGroupNameException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupDataIntegrityException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupMutationForbiddenException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupNotFoundException;
import com.syncro.masterdata.application.MachineGroupService.PlantNotFoundForMachineGroupException;
import com.syncro.masterdata.application.MachineGroupService.SectionNotFoundForMachineGroupException;
import com.syncro.masterdata.application.MachineGroupService.SectionPlantMismatchException;
import com.syncro.masterdata.application.MachineGroupService.SectionReassignmentRejectedException;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = MachineGroupController.class)
public class MachineGroupExceptionHandler {
  private final Clock clock;

  public MachineGroupExceptionHandler(Clock clock) {
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

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidPathValue(MethodArgumentTypeMismatchException exception) {
    if (exception.getParameter() != null && exception.getParameter().hasParameterAnnotation(RequestParam.class)) {
      return error(HttpStatus.BAD_REQUEST, "INVALID_QUERY_VALUE", "Query value is invalid.", Map.of());
    }
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  @ExceptionHandler(DuplicateMachineGroupNameException.class)
  ResponseEntity<ErrorResponse> duplicateMachineGroupName() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_MACHINE_GROUP_NAME", "Machine group name already exists for this plant.", Map.of());
  }

  @ExceptionHandler(MachineGroupDataIntegrityException.class)
  ResponseEntity<ErrorResponse> machineGroupDataIntegrity() {
    return error(HttpStatus.CONFLICT, "MACHINE_GROUP_DATA_INTEGRITY_VIOLATION", "Machine group data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler({MachineGroupMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(MachineGroupNotFoundException.class)
  ResponseEntity<ErrorResponse> machineGroupNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_GROUP_NOT_FOUND", "Machine group was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForMachineGroupException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(SectionNotFoundForMachineGroupException.class)
  ResponseEntity<ErrorResponse> sectionNotFound() {
    return error(HttpStatus.NOT_FOUND, "SECTION_NOT_FOUND", "Section was not found.", Map.of());
  }

  @ExceptionHandler(SectionPlantMismatchException.class)
  ResponseEntity<ErrorResponse> sectionPlantMismatch() {
    return error(HttpStatus.BAD_REQUEST, "SECTION_PLANT_MISMATCH",
        "Section belongs to a different plant than the machine group.", Map.of());
  }

  @ExceptionHandler(SectionReassignmentRejectedException.class)
  ResponseEntity<ErrorResponse> sectionReassignmentRejected() {
    return error(HttpStatus.BAD_REQUEST, "SECTION_REASSIGNMENT_REJECTED",
        "Machine group is already assigned to another section; clear it before reassigning.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
