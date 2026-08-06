package com.syncro.machine.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.masterdata.api.PlantDtos.ErrorResponse;
import com.syncro.machine.application.MachineService.DuplicateMachineCodeException;
import com.syncro.machine.application.MachineResponsibilityService;
import com.syncro.machine.application.MachineService.MachineDataIntegrityException;
import com.syncro.machine.application.MachineService.MachineGroupNotFoundForMachineException;
import com.syncro.machine.application.MachineService.MachineGroupPlantMismatchException;
import com.syncro.machine.application.MachineService.MachineMutationForbiddenException;
import com.syncro.machine.application.MachineService.MachineNotFoundException;
import com.syncro.machine.application.MachineService.MachineValidationException;
import com.syncro.machine.application.MachineService.PlantNotFoundForMachineException;
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
@RestControllerAdvice(assignableTypes = {MachineController.class, MachineResponsibilityController.class})
public class MachineExceptionHandler {
  private final Clock clock;

  public MachineExceptionHandler(Clock clock) {
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

  @ExceptionHandler(MachineValidationException.class)
  ResponseEntity<ErrorResponse> machineValidation() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of("code", "Invalid value."));
  }

  @ExceptionHandler(MachineResponsibilityService.DuplicateResponsibilityException.class)
  ResponseEntity<ErrorResponse> duplicateResponsibility() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_RESPONSIBILITY", "User is already assigned to this machine.", Map.of());
  }

  @ExceptionHandler(DuplicateMachineCodeException.class)
  ResponseEntity<ErrorResponse> duplicateMachineCode() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_MACHINE_CODE", "Machine code already exists for this plant.", Map.of());
  }

  @ExceptionHandler(MachineGroupPlantMismatchException.class)
  ResponseEntity<ErrorResponse> machineGroupPlantMismatch() {
    return error(HttpStatus.BAD_REQUEST, "MACHINE_GROUP_PLANT_MISMATCH", "Machine group must belong to the machine plant.", Map.of());
  }

  @ExceptionHandler(MachineDataIntegrityException.class)
  ResponseEntity<ErrorResponse> machineDataIntegrity() {
    return error(HttpStatus.CONFLICT, "MACHINE_DATA_INTEGRITY_VIOLATION", "Machine data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler({MachineMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler({MachineNotFoundException.class, MachineResponsibilityService.ResponsibilityNotFoundException.class})
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(MachineGroupNotFoundForMachineException.class)
  ResponseEntity<ErrorResponse> machineGroupNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_GROUP_NOT_FOUND", "Machine group was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForMachineException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
