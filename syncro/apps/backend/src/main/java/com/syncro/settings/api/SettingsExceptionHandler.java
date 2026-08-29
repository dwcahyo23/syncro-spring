package com.syncro.settings.api;

import com.syncro.settings.api.SettingsController.LogoBodyReadException;
import com.syncro.settings.api.SettingsDtos.ErrorResponse;
import com.syncro.settings.application.LogoService.LogoNotConfiguredException;
import com.syncro.settings.application.LogoService.LogoStorageException;
import com.syncro.settings.application.LogoService.LogoValidationException;
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
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.databind.exc.InvalidFormatException;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = SettingsController.class)
public class SettingsExceptionHandler {

  private final Clock clock;

  public SettingsExceptionHandler(Clock clock) {
    this.clock = clock;
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
    var fieldErrors = new LinkedHashMap<String, String>();
    for (var error : exception.getBindingResult().getFieldErrors()) {
      // Preserve the actual Bean Validation message (e.g. "must not be blank").
      fieldErrors.putIfAbsent(error.getField(),
          error.getDefaultMessage() == null ? "Invalid value." : error.getDefaultMessage());
    }
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  ResponseEntity<ErrorResponse> malformedJson(HttpMessageNotReadableException exception) {
    var invalidFormat = findInvalidFormat(exception);
    if (invalidFormat != null) {
      var fieldErrors = new LinkedHashMap<String, String>();
      for (var reference : invalidFormat.getPath()) {
        var propertyName = reference.getPropertyName();
        if (propertyName != null) {
          fieldErrors.putIfAbsent(propertyName, "Invalid value.");
        }
      }
      if (!fieldErrors.isEmpty()) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", fieldErrors);
      }
    }
    return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Request body is malformed.", Map.of());
  }

  private InvalidFormatException findInvalidFormat(Throwable throwable) {
    var current = throwable;
    while (current != null) {
      if (current instanceof InvalidFormatException invalidFormat) {
        return invalidFormat;
      }
      current = current.getCause();
    }
    return null;
  }

  @ExceptionHandler(LogoValidationException.class)
  ResponseEntity<ErrorResponse> logoValidation(LogoValidationException exception) {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.", exception.getFieldErrors());
  }

  @ExceptionHandler(LogoNotConfiguredException.class)
  ResponseEntity<ErrorResponse> logoNotConfigured() {
    return error(HttpStatus.NOT_FOUND, "SETTINGS_NOT_FOUND",
        "Settings have not been configured.", Map.of());
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

  @ExceptionHandler(LogoBodyReadException.class)
  ResponseEntity<ErrorResponse> bodyReadError() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Uploaded file could not be read."));
  }

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> oversizeUpload() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Uploaded file exceeds the maximum allowed size."));
  }

  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ErrorResponse> multipartError() {
    return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed.",
        Map.of("data", "Request must be a well-formed multipart form-data upload."));
  }

  @ExceptionHandler(LogoStorageException.class)
  ResponseEntity<ErrorResponse> storageError() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR",
        "Object storage operation failed.", Map.of());
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message,
      Map<String, String> fieldErrors) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, fieldErrors, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}