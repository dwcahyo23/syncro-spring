package com.syncro.org.api;

import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.org.api.SectionDtos.ErrorResponse;
import com.syncro.org.application.SectionService.DuplicateSectionCodeException;
import com.syncro.org.application.SectionService.DuplicateSectionNameException;
import com.syncro.org.application.SectionService.InvalidSectionCodeException;
import com.syncro.org.application.SectionService.SectionHasActiveMachineGroupsException;
import com.syncro.org.application.SectionService.SectionMutationForbiddenException;
import com.syncro.org.application.SectionService.SectionNotFoundException;
import com.syncro.org.application.SectionService.PlantNotFoundForSectionException;
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
@RestControllerAdvice(assignableTypes = SectionController.class)
public class SectionExceptionHandler {

  private final Clock clock;

  public SectionExceptionHandler(Clock clock) {
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

  @ExceptionHandler(DuplicateSectionCodeException.class)
  ResponseEntity<ErrorResponse> duplicateSectionCode() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_SECTION_CODE",
        "A section with this code already exists for the plant.", Map.of());
  }

  @ExceptionHandler(DuplicateSectionNameException.class)
  ResponseEntity<ErrorResponse> duplicateSectionName() {
    return error(HttpStatus.BAD_REQUEST, "DUPLICATE_SECTION_NAME",
        "A section with this name already exists for the plant.", Map.of());
  }

  @ExceptionHandler(InvalidSectionCodeException.class)
  ResponseEntity<ErrorResponse> invalidSectionCode() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
        "Section code must be one of MACHINERY, UTILITY, WORKSHOP.", Map.of("code", "Invalid value."));
  }

  @ExceptionHandler(SectionHasActiveMachineGroupsException.class)
  ResponseEntity<ErrorResponse> sectionHasActiveMachineGroups() {
    return error(HttpStatus.CONFLICT, "SECTION_HAS_ACTIVE_MACHINE_GROUPS",
        "Section cannot be deactivated while it has machine groups with active machines.", Map.of());
  }

  @ExceptionHandler({SectionMutationForbiddenException.class, PlantAccessDeniedException.class})
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(SectionNotFoundException.class)
  ResponseEntity<ErrorResponse> sectionNotFound() {
    return error(HttpStatus.NOT_FOUND, "SECTION_NOT_FOUND", "Section was not found.", Map.of());
  }

  @ExceptionHandler(PlantNotFoundForSectionException.class)
  ResponseEntity<ErrorResponse> plantNotFound() {
    return error(HttpStatus.NOT_FOUND, "PLANT_NOT_FOUND", "Plant was not found.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
