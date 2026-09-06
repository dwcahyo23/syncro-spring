package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.ErrorResponse;
import com.syncro.auth.application.AuthLoginAuditService.AuditReadForbiddenException;
import com.syncro.auth.application.AuthLoginAuditService.LoginAuditNotFoundException;
import com.syncro.auth.application.AuthService.AccountLockedException;
import com.syncro.auth.application.AuthService.BadCredentialsException;
import com.syncro.auth.application.AuthService.DuplicateUserIdentifierException;
import com.syncro.auth.application.AuthService.UserDataIntegrityException;
import com.syncro.auth.application.AuthService.UserMasterForbiddenException;
import com.syncro.auth.application.AuthService.UserNotFoundException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeConsumedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExhaustedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExpiredException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeNotFoundException;
import com.syncro.auth.application.PhoneVerificationService.InvalidOtpException;
import com.syncro.auth.application.PhoneVerificationService.PhoneChallengeForbiddenException;
import com.syncro.auth.application.PhoneVerificationService.ResendTooEarlyException;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.application.UserSignatureService.SignatureForbiddenException;
import com.syncro.auth.application.UserSignatureService.SignatureNotFoundException;
import com.syncro.auth.application.UserSignatureService.SignatureUploadConflictException;
import com.syncro.auth.application.UserSignatureService.StorageException;
import com.syncro.auth.application.UserSignatureService.UnsupportedContentTypeException;
import com.syncro.auth.application.UserSignatureService.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

  @ExceptionHandler(AccountLockedException.class)
  ResponseEntity<ErrorResponse> accountLocked() {
    return error(HttpStatus.LOCKED, "ACCOUNT_LOCKED", "Account is locked due to too many failed login attempts.");
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ErrorResponse> validationError(MethodArgumentNotValidException exception) {
    var fieldErrors = new java.util.LinkedHashMap<String, String>();
    for (var error : exception.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), "Invalid value.");
    }
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.", Map.copyOf(fieldErrors),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(PlantAccessDeniedException.class)
  ResponseEntity<ErrorResponse> plantAccessDenied() {
    return error(HttpStatus.FORBIDDEN, "PLANT_ACCESS_DENIED", "You don't have access to this plant's data.");
  }

  @ExceptionHandler(UserNotFoundException.class)
  ResponseEntity<ErrorResponse> userNotFound() {
    return error(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "User was not found.");
  }

  @ExceptionHandler(DuplicateUserIdentifierException.class)
  ResponseEntity<ErrorResponse> duplicateUserIdentifier() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_IDENTIFIER",
        "The NIK or phone number is already in use by another user.");
  }

  @ExceptionHandler(UserMasterForbiddenException.class)
  ResponseEntity<ErrorResponse> userMasterForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.");
  }

  @ExceptionHandler(UserDataIntegrityException.class)
  ResponseEntity<ErrorResponse> userDataIntegrity() {
    return error(HttpStatus.CONFLICT, "DUPLICATE_IDENTIFIER",
        "The NIK or phone number is already in use by another user.");
  }

  // --- Story 22-2: login-audit reads + phone-challenge lifecycle -------------------

  @ExceptionHandler(AuditReadForbiddenException.class)
  ResponseEntity<ErrorResponse> auditReadForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.");
  }

  @ExceptionHandler(LoginAuditNotFoundException.class)
  ResponseEntity<ErrorResponse> loginAuditNotFound() {
    return error(HttpStatus.NOT_FOUND, "LOGIN_AUDIT_NOT_FOUND", "Login audit entry was not found.");
  }

  @ExceptionHandler(PhoneChallengeForbiddenException.class)
  ResponseEntity<ErrorResponse> phoneChallengeForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.");
  }

  @ExceptionHandler(ChallengeNotFoundException.class)
  ResponseEntity<ErrorResponse> challengeNotFound() {
    return error(HttpStatus.NOT_FOUND, "CHALLENGE_NOT_FOUND", "Phone verification challenge was not found.");
  }

  @ExceptionHandler(ChallengeExpiredException.class)
  ResponseEntity<ErrorResponse> challengeExpired() {
    return error(HttpStatus.CONFLICT, "CHALLENGE_EXPIRED", "Phone verification challenge has expired.");
  }

  @ExceptionHandler(ChallengeExhaustedException.class)
  ResponseEntity<ErrorResponse> challengeExhausted() {
    return error(HttpStatus.CONFLICT, "CHALLENGE_EXHAUSTED",
        "Phone verification challenge attempt limit reached.");
  }

  @ExceptionHandler(ChallengeConsumedException.class)
  ResponseEntity<ErrorResponse> challengeConsumed() {
    return error(HttpStatus.CONFLICT, "CHALLENGE_CONSUMED", "Phone verification challenge was already used.");
  }

  @ExceptionHandler(InvalidOtpException.class)
  ResponseEntity<ErrorResponse> invalidOtp() {
    return error(HttpStatus.CONFLICT, "INVALID_OTP", "The OTP is incorrect.");
  }

  @ExceptionHandler(ResendTooEarlyException.class)
  ResponseEntity<ErrorResponse> resendTooEarly() {
    return error(HttpStatus.CONFLICT, "RESEND_TOO_EARLY", "A resend is not available yet for this challenge.");
  }

  // --- Story 22-3: user-signature storage endpoints ---------------------------------

  @ExceptionHandler(SignatureNotFoundException.class)
  ResponseEntity<ErrorResponse> signatureNotFound() {
    return error(HttpStatus.NOT_FOUND, "SIGNATURE_NOT_FOUND", "User signature was not found.");
  }

  @ExceptionHandler(SignatureForbiddenException.class)
  ResponseEntity<ErrorResponse> signatureForbidden() {
    return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to access this resource.");
  }

  @ExceptionHandler(UnsupportedContentTypeException.class)
  ResponseEntity<ErrorResponse> unsupportedContentType() {
    return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
        "Signature must be an image (image/jpeg, image/png, image/webp, or image/gif).");
  }

  @ExceptionHandler(ValidationException.class)
  ResponseEntity<ErrorResponse> signatureValidation(ValidationException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.", exception.getFieldErrors(),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(SignatureUploadConflictException.class)
  ResponseEntity<ErrorResponse> signatureUploadConflict() {
    return error(HttpStatus.CONFLICT, "SIGNATURE_UPLOAD_CONFLICT",
        "A concurrent signature upload won the race; retry the upload.");
  }

  // Review 22-3 P8: multipart failure modes must answer with the house envelope,
  // never Spring's default 500 (mirrors SparepartImageExceptionHandler).

  @ExceptionHandler(MaxUploadSizeExceededException.class)
  ResponseEntity<ErrorResponse> oversizeUpload() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.", Map.of("data", "Signature file exceeds the maximum allowed size."),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MissingServletRequestPartException.class)
  ResponseEntity<ErrorResponse> missingPart(MissingServletRequestPartException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.", Map.of(exception.getRequestPartName(), "This part is required."),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MissingServletRequestParameterException.class)
  ResponseEntity<ErrorResponse> missingParam(MissingServletRequestParameterException exception) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.", Map.of(exception.getParameterName(), "This value is required."),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(MultipartException.class)
  ResponseEntity<ErrorResponse> multipartError() {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse("VALIDATION_ERROR",
        "Request validation failed.",
        Map.of("data", "Request must be a well-formed multipart form-data upload."),
        Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }

  @ExceptionHandler(StorageException.class)
  ResponseEntity<ErrorResponse> signatureStorage() {
    return error(HttpStatus.BAD_GATEWAY, "OBJECT_STORAGE_ERROR", "Object storage operation failed.");
  }

  private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String message) {
    return ResponseEntity.status(status)
        .body(new ErrorResponse(code, message, Instant.now(clock).toString(), UUID.randomUUID().toString()));
  }
}
