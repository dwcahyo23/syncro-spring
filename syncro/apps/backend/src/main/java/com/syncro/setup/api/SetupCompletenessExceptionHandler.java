package com.syncro.setup.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.masterdata.api.PlantDtos.ErrorResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = {SetupCompletenessController.class})
public class SetupCompletenessExceptionHandler {
  private final Clock clock;

  public SetupCompletenessExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(PlantAccessDeniedException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse("FORBIDDEN", "You do not have permission to access this resource.", Map.of(),
            Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
