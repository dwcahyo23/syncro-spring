package com.syncro.org.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarDataIntegrityException;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarDateNotFoundException;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarMutationForbiddenException;
import com.syncro.org.application.PlantWorkingCalendarService.CalendarNotFoundException;
import com.syncro.org.application.PlantWorkingCalendarService.DuplicateCalendarDateException;
import com.syncro.org.application.PlantWorkingCalendarService.DuplicateCalendarException;
import com.syncro.org.application.PlantWorkingCalendarService.PlantNotFoundForCalendarException;
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
@RestControllerAdvice(assignableTypes = PlantWorkingCalendarController.class)
public class PlantWorkingCalendarExceptionHandler {

  public record ErrorResponse(String code, String message, Map<String, String> fieldErrors, String timestamp,
      String traceId) {
  }

  private final Clock clock;

  public PlantWorkingCalendarExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(DuplicateCalendarException.class)
  ResponseEntity<ErrorResponse> duplicateCalendar() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "A working calendar for this plant and year already exists.", Map.of("year", "Invalid value."));
  }

  @ExceptionHandler(DuplicateCalendarDateException.class)
  ResponseEntity<ErrorResponse> duplicateDate() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "This date already exists on the calendar.", Map.of("date", "Invalid value."));
  }

  @ExceptionHandler({CalendarMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler({CalendarNotFoundException.class, CalendarDateNotFoundException.class})
  ResponseEntity<ErrorResponse> notFound() {
    return error(HttpStatus.NOT_FOUND, "CALENDAR_NOT_FOUND", "Working calendar was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForCalendarException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  @ExceptionHandler(CalendarDataIntegrityException.class)
  ResponseEntity<ErrorResponse> dataIntegrity() {
    return error(HttpStatus.CONFLICT, "VALIDATION_ERROR", "Calendar data conflicts with existing records.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}