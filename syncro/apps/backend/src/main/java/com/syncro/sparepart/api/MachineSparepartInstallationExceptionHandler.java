package com.syncro.sparepart.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationConcurrentModificationException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationDataIntegrityException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationMutationForbiddenException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationPlantNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationPlantScopeEmptyException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.InstallationValidationException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.MachineForInstallationNotFoundException;
import com.syncro.sparepart.application.MachineSparepartInstallationService.SparepartForInstallationNotFoundException;
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
@RestControllerAdvice(assignableTypes = MachineSparepartInstallationController.class)
public class MachineSparepartInstallationExceptionHandler {
  private final Clock clock;

  public MachineSparepartInstallationExceptionHandler(Clock clock) {
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

  @ExceptionHandler(InstallationValidationException.class)
  ResponseEntity<ErrorResponse> validation() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", Map.of());
  }

  @ExceptionHandler(MachineForInstallationNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(SparepartForInstallationNotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  @ExceptionHandler(InstallationNotFoundException.class)
  ResponseEntity<ErrorResponse> installationNotFound() {
    return error(HttpStatus.NOT_FOUND, "INSTALLATION_NOT_FOUND", "Machine sparepart installation was not found.", Map.of());
  }

  @ExceptionHandler({InstallationMutationForbiddenException.class, InstallationPlantScopeEmptyException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(InstallationPlantNotFoundException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(InstallationDataIntegrityException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.CONFLICT, "INSTALLATION_DATA_INTEGRITY_VIOLATION", "Machine sparepart installation data conflicts with existing records.", Map.of());
  }

  @ExceptionHandler(InstallationConcurrentModificationException.class)
  ResponseEntity<ErrorResponse> concurrentModification() {
    return error(HttpStatus.CONFLICT, "INSTALLATION_CONCURRENT_MODIFICATION", "Machine sparepart installation was modified concurrently. Reload and retry.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp, String traceId) {
  }
}
