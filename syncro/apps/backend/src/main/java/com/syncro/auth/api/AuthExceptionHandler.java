package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.ErrorResponse;
import com.syncro.auth.application.AuthService.BadCredentialsException;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AuthExceptionHandler {
  private final Clock clock;

  public AuthExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(BadCredentialsException.class)
  ResponseEntity<ErrorResponse> badCredentials() {
    return error(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Invalid login credentials.");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.");
  }

  @ExceptionHandler(PlantAccessDeniedException.class)
  ResponseEntity<ErrorResponse> plantAccessDenied() {
    return error(HttpStatus.FORBIDDEN, "PLANT_ACCESS_DENIED", "You don't have access to this plant's data.");
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
