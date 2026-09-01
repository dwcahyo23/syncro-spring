package com.syncro.org.api;

import com.syncro.org.application.UserBindingService.JobTitleNotFoundException;
import com.syncro.org.application.UserBindingService.RoleBindingNotFoundException;
import com.syncro.org.application.UserBindingService.SystemRoleNotFoundException;
import com.syncro.org.application.UserBindingService.UserBindingMutationForbiddenException;
import com.syncro.org.application.UserBindingService.UserNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = UserBindingController.class)
public class UserBindingExceptionHandler {

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }

  private final Clock clock;

  public UserBindingExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson() {
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  @ExceptionHandler(UserBindingMutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler({UserNotFoundException.class, JobTitleNotFoundException.class,
      SystemRoleNotFoundException.class, RoleBindingNotFoundException.class})
  ResponseEntity<ErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Requested binding reference was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}