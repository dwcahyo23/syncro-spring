package com.syncro.org.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.org.api.DepartmentDtos.ErrorResponse;
import com.syncro.org.application.DepartmentService.DepartmentDataIntegrityException;
import com.syncro.org.application.DepartmentService.DepartmentHasMembersException;
import com.syncro.org.application.DepartmentService.DepartmentInactiveException;
import com.syncro.org.application.DepartmentService.DepartmentMutationForbiddenException;
import com.syncro.org.application.DepartmentService.DepartmentNotFoundException;
import com.syncro.org.application.DepartmentService.DuplicateDepartmentNameException;
import com.syncro.org.application.DepartmentService.LeaderValidationException;
import com.syncro.org.application.DepartmentService.PlantNotFoundForDepartmentException;
import com.syncro.org.application.DepartmentService.UserNotFoundException;
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
@RestControllerAdvice(assignableTypes = DepartmentController.class)
public class DepartmentExceptionHandler {

  private final Clock clock;

  public DepartmentExceptionHandler(Clock clock) {
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

  @ExceptionHandler(DuplicateDepartmentNameException.class)
  ResponseEntity<ErrorResponse> duplicateDepartmentName() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "A department with this name already exists for the plant.", Map.of("name", "Invalid value."));
  }

  @ExceptionHandler(LeaderValidationException.class)
  ResponseEntity<ErrorResponse> leaderValidation(LeaderValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "SPV/MG must be an enabled user with access to this plant.",
        Map.of(exception.getField(), "Invalid value."));
  }

  @ExceptionHandler(DepartmentHasMembersException.class)
  ResponseEntity<ErrorResponse> departmentHasMembers() {
    return error(HttpStatus.CONFLICT, "DEPARTMENT_HAS_MEMBERS",
        "Department cannot be deactivated while it still has members.", Map.of());
  }

  @ExceptionHandler(DepartmentInactiveException.class)
  ResponseEntity<ErrorResponse> departmentInactive() {
    return error(HttpStatus.CONFLICT, "INVALID_STATE",
        "Members cannot be changed on an inactive department.", Map.of());
  }

  @ExceptionHandler({DepartmentMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(DepartmentNotFoundException.class)
  ResponseEntity<ErrorResponse> departmentNotFound() {
    return error(HttpStatus.NOT_FOUND, "DEPARTMENT_NOT_FOUND", "Department was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForDepartmentException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(UserNotFoundException.class)
  ResponseEntity<ErrorResponse> userNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.", Map.of());
  }

  @ExceptionHandler(DepartmentDataIntegrityException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.CONFLICT, "VALIDATION_ERROR", "Department data conflicts with existing records.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
