package com.syncro.sparepart.api;

import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.sparepart.application.SparepartImageService.ImageNotFoundException;
import com.syncro.sparepart.application.SparepartImageService.MutationForbiddenException;
import com.syncro.sparepart.application.SparepartImageService.NotFoundException;
import com.syncro.sparepart.application.SparepartImageService.StorageException;
import com.syncro.sparepart.application.SparepartImageService.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SparepartImageController.class)
public class SparepartImageExceptionHandler {
  private final Clock clock;

  public SparepartImageExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<ErrorResponse> invalidPathValue(MethodArgumentTypeMismatchException exception) {
    return error(HttpStatus.BAD_REQUEST, "INVALID_PATH_VALUE", "Path value is invalid.", Map.of());
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getRequestPartName(), "This part is required."));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of(exception.getParameterName(), "This value is required."));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> oversizeUpload() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Image file exceeds the maximum allowed size."));
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<ErrorResponse> validation(ValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        exception.getFieldErrors());
  }

  @ExceptionHandler(JobScopeForbiddenException.class)
  ResponseEntity<ErrorResponse> jobScopeForbidden(JobScopeForbiddenException exception) {
    return error(HttpStatus.FORBIDDEN, "JOB_SCOPE_REQUIRED", exception.getMessage(), Map.of());
  }

  @ExceptionHandler(MutationForbiddenException.class)
  ResponseEntity<ErrorResponse> forbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN",
        "You do not have permission to access this resource.", Map.of());
  }

  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<ErrorResponse> sparepartNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_NOT_FOUND", "Sparepart was not found.", Map.of());
  }

  @ExceptionHandler(ImageNotFoundException.class)
  ResponseEntity<ErrorResponse> imageNotFound() {
    return error(HttpStatus.NOT_FOUND, "SPAREPART_IMAGE_NOT_FOUND",
        "Sparepart has no image.", Map.of());
  }

  @ExceptionHandler(StorageException.class)
  ResponseEntity<ErrorResponse> objectStorageError() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR",
        "Object storage operation failed.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors,
            Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  record ErrorResponse(String code, String message, Map<String, String> fieldErrors,
      String timestamp, String traceId) {
  }
}