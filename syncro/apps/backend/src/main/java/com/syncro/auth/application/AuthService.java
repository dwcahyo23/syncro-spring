package com.syncro.auth.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.api.AuthDtos.UpdateUserRequest;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final String DUMMY_PASSWORD_HASH = "$2a$10$7EqJtq98hPqEX7fNZaFWoOhiI6BFSH9upL7M9PdPIpEBaU8UQFJ6i";

  private static final String NIK_UNIQUE_INDEX = "uq_auth_users_nik";
  private static final String PHONE_UNIQUE_INDEX = "uq_auth_users_phone";

  private final AuthUserRepository users;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService tokens;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public AuthService(AuthUserRepository users, PasswordEncoder passwordEncoder, JwtTokenService tokens,
      AuditLogWriter auditLog, Clock clock) {
    this.users = users;
    this.passwordEncoder = passwordEncoder;
    this.tokens = tokens;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  private static final int MAX_FAILED_LOGIN_ATTEMPTS = 5;

  @Transactional
  public LoginResponse login(String loginIdentifier, String password) {
    var user = users.findByLoginIdentifierIgnoreCase(loginIdentifier.trim());
    var passwordHash = user.map(candidate -> candidate.getPasswordHash()).orElse(DUMMY_PASSWORD_HASH);
    var passwordMatches = passwordEncoder.matches(password, passwordHash);

    if (user.isPresent()) {
      var candidate = user.get();
      if (candidate.getLockedAt() != null) {
        throw new AccountLockedException();
      }
      if (!passwordMatches || !candidate.isEnabled()) {
        candidate.recordLoginFailure(MAX_FAILED_LOGIN_ATTEMPTS);
        users.save(candidate);
        throw new BadCredentialsException();
      }
      candidate.resetLoginFailures();
      users.save(candidate);
      var authenticatedUser = candidate;
      return new LoginResponse(
          "Bearer",
          tokens.createToken(authenticatedUser),
          tokens.expiresInSeconds(),
          toView(authenticatedUser));
    }
    // Unknown user: burn a dummy hash comparison and fail without side effects.
    throw new BadCredentialsException();
  }

  public AuthUserView currentUser(JwtTokenService.AuthenticatedUser user) {
    return toView(users.findById(UUID.fromString(user.id())).orElseThrow(UserNotFoundException::new));
  }

  @Transactional(readOnly = true)
  public List<AuthUserView> listUsers() {
    return users.findAllByOrderByLoginIdentifierAsc().stream()
        .map(this::toView)
        .toList();
  }

  @Transactional
  public AuthUserView updateUser(JwtTokenService.AuthenticatedUser actor, UUID userId, UpdateUserRequest request) {
    requireUserMasterRole(actor);
    var user = users.findById(userId).orElseThrow(UserNotFoundException::new);
    var displayName = normalizeOrNull(request.displayName(), 200);
    var nik = normalizeOrNull(request.nik(), 50);
    var phoneNumber = normalizeOrNull(request.phoneNumber(), 32);
    var jobTitleId = request.jobTitleId();
    var departmentId = request.departmentId();

    // Null-safe uniqueness: an empty string clears to null (spec), and duplicates
    // are only rejected among non-NULL values (partial unique index enforces at DB).
    if (nik != null) {
      users.findByNikIgnoreCase(nik)
          .filter(existing -> !existing.getId().equals(userId))
          .ifPresent(existing -> {
            throw new DuplicateUserIdentifierException("NIK");
          });
    }
    if (phoneNumber != null) {
      users.findByPhoneNumber(phoneNumber)
          .filter(existing -> !existing.getId().equals(userId))
          .ifPresent(existing -> {
            throw new DuplicateUserIdentifierException("PHONE");
          });
    }

    var entityLabel = user.getLoginIdentifier();
    var previous = UserMasterAuditValues.of(user);
    user.updateMasterFields(displayName, nik, phoneNumber, jobTitleId, departmentId, Instant.now(clock));
    var saved = saveUser(user);
    auditLog.record(actor, new AuditRecord(AuditAction.UPDATE, AuditEntityType.USER, userId, entityLabel,
        null, previous, UserMasterAuditValues.of(saved), null));
    return toView(saved);
  }

  private AuthUserEntity saveUser(AuthUserEntity user) {
    try {
      return users.saveAndFlush(user);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(NIK_UNIQUE_INDEX)) {
        throw new DuplicateUserIdentifierException("NIK");
      }
      if (message.contains(PHONE_UNIQUE_INDEX)) {
        throw new DuplicateUserIdentifierException("PHONE");
      }
      throw new UserDataIntegrityException();
    }
  }

  private void requireUserMasterRole(JwtTokenService.AuthenticatedUser actor) {
    if (actor.applicationRole() != ApplicationRole.SUPER_ADMIN
        && actor.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new UserMasterForbiddenException();
    }
  }

  private String normalizeOrNull(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private AuthUserView toView(AuthUserEntity user) {
    return new AuthUserView(
        user.getId().toString(),
        user.getLoginIdentifier(),
        user.getDisplayName(),
        user.getNik(),
        user.getPhoneNumber(),
        user.getApplicationRole(),
        user.isEnabled(),
        user.getJobTitleId(),
        user.getDepartmentId(),
        user.isForcePasswordChange(),
        user.getFailedLoginAttempts(),
        user.getLockedAt() == null ? null : user.getLockedAt().toString(),
        user.getLockReason(),
        user.getPhoneVerifiedAt() == null ? null : user.getPhoneVerifiedAt().toString());
  }

  public record UpdateUserCommand(String displayName, String nik, String phoneNumber, UUID jobTitleId,
      UUID departmentId) {
  }

  public static class BadCredentialsException extends RuntimeException {
  }

  public static class AccountLockedException extends RuntimeException {
  }

  public static class UserNotFoundException extends RuntimeException {
  }

  public static class DuplicateUserIdentifierException extends RuntimeException {
    private final String field;

    public DuplicateUserIdentifierException(String field) {
      this.field = field;
    }

    public String getField() {
      return field;
    }
  }

  public static class UserMasterForbiddenException extends RuntimeException {
  }

  public static class UserDataIntegrityException extends RuntimeException {
  }

  /** Audit snapshot helper for the user-master fields (kept private to auth service). */
  private static final class UserMasterAuditValues {
    private UserMasterAuditValues() {
    }

    static Map<String, Object> of(AuthUserEntity user) {
      var values = new java.util.HashMap<String, Object>();
      values.put("loginIdentifier", user.getLoginIdentifier());
      values.put("displayName", user.getDisplayName());
      values.put("nik", user.getNik());
      values.put("phoneNumber", user.getPhoneNumber());
      values.put("jobTitleId", user.getJobTitleId() == null ? null : user.getJobTitleId().toString());
      values.put("departmentId", user.getDepartmentId() == null ? null : user.getDepartmentId().toString());
      return values;
    }
  }
}
