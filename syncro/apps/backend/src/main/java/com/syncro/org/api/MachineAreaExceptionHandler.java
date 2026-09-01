package com.syncro.org.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.org.api.MachineAreaDtos.ErrorResponse;
import com.syncro.org.application.MachineAreaService.DuplicateMachineAreaCodeException;
import com.syncro.org.application.MachineAreaService.DuplicateMachineAreaNameException;
import com.syncro.org.application.MachineAreaService.MachineAreaDataIntegrityException;
import com.syncro.org.application.MachineAreaService.MachineAreaHasMachinesException;
import com.syncro.org.application.MachineAreaService.MachineAreaMutationForbiddenException;
import com.syncro.org.application.MachineAreaService.MachineAreaNotFoundException;
import com.syncro.org.application.MachineAreaService.PlantNotFoundForMachineAreaException;
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
@RestControllerAdvice(assignableTypes = MachineAreaController.class)
public class MachineAreaExceptionHandler {

  private final Clock clock;

  public MachineAreaExceptionHandler(Clock clock) {
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

  @ExceptionHandler(DuplicateMachineAreaNameException.class)
  ResponseEntity<ErrorResponse> duplicateName() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "A machine area with this name already exists for the plant.", Map.of("name", "Invalid value."));
  }

  @ExceptionHandler(DuplicateMachineAreaCodeException.class)
  ResponseEntity<ErrorResponse> duplicateCode() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "A machine area with this code already exists for the plant.", Map.of("code", "Invalid value."));
  }

  @ExceptionHandler(MachineAreaHasMachinesException.class)
  ResponseEntity<ErrorResponse> areaHasMachines() {
    return error(HttpStatus.CONFLICT, "MACHINE_AREA_HAS_MACHINES",
        "Machine area cannot be deactivated while machines still reference it.", Map.of());
  }

  @ExceptionHandler({MachineAreaMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(MachineAreaNotFoundException.class)
  ResponseEntity<ErrorResponse> areaNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_AREA_NOT_FOUND", "Machine area was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForMachineAreaException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(MachineAreaDataIntegrityException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.CONFLICT, "VALIDATION_ERROR", "Machine area data conflicts with existing records.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
