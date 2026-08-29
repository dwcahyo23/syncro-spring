package com.syncro.org.api;

import com.syncro.org.api.TeamDtos.ErrorResponse;
import com.syncro.org.application.TeamService.DuplicateTeamNameException;
import com.syncro.org.application.TeamService.MachineNotFoundException;
import com.syncro.org.application.TeamService.TeamDataIntegrityException;
import com.syncro.org.application.TeamService.TeamExpiryInPastException;
import com.syncro.org.application.TeamService.TeamMutationForbiddenException;
import com.syncro.org.application.TeamService.TeamNotFoundException;
import com.syncro.org.application.TeamService.UserNotFoundException;
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
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = TeamController.class)
public class TeamExceptionHandler {

  private final Clock clock;

  public TeamExceptionHandler(Clock clock) {
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

  @ExceptionHandler(DuplicateTeamNameException.class)
  ResponseEntity<ErrorResponse> duplicateTeamName() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_TEAM_NAME",
        "A team with this name already exists (case-insensitive).", Map.of());
  }

  @ExceptionHandler(TeamExpiryInPastException.class)
  ResponseEntity<ErrorResponse> teamExpiryInPast() {
    return error(HttpStatus.BAD_REQUEST, "TEAM_EXPIRY_IN_PAST",
        "Team expiry must be strictly in the future.", Map.of());
  }

  @ExceptionHandler(TeamDataIntegrityException.class)
  ResponseEntity<ErrorResponse> teamDataIntegrity() {
    return error(HttpStatus.BAD_REQUEST, "TEAM_DATA_INTEGRITY",
        "Team data integrity violation.", Map.of());
  }

  @ExceptionHandler(TeamMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(TeamNotFoundException.class)
  ResponseEntity<ErrorResponse> teamNotFound() {
    return error(HttpStatus.NOT_FOUND, "TEAM_NOT_FOUND", "Team was not found.", Map.of());
  }

  @ExceptionHandler(UserNotFoundException.class)
  ResponseEntity<ErrorResponse> userNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.", Map.of());
  }

  @ExceptionHandler(MachineNotFoundException.class)
  ResponseEntity<ErrorResponse> machineNotFound() {
    return error(HttpStatus.NOT_FOUND, "MACHINE_NOT_FOUND", "Machine was not found.", Map.of());
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ResponseEntity<ErrorResponse> concurrentModification() {
    // DW-126: TeamEntity carries @Version; a lost-update race on PUT/DELETE must surface
    // as 409 CONCURRENT_MODIFICATION (retry signal), not a raw 500.
    return error(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION",
        "Team was modified concurrently. Reload and retry.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
