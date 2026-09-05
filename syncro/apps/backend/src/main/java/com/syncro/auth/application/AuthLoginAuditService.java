package com.syncro.auth.application;

import com.syncro.auth.api.AuthDtos.LoginAuditListResponse;
import com.syncro.auth.api.AuthDtos.LoginAuditView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthLoginAuditEntity;
import com.syncro.auth.infrastructure.AuthLoginAuditRepository;
import com.syncro.common.LikePattern;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Append-only login audit trail (story 22-2, blueprint I2): one {@code auth_login_audits}
 * row per login attempt — success, bad credentials, unknown user, locked — never any
 * password or hash material. Reads are SUPER_ADMIN/AUDITOR-only (service gate here, rego
 * {@code auth_audit_read_paths} as the coarse parity gate), filterable by identifier /
 * success / occurred-at range, paged newest-first.
 *
 * <p>{@link #recordAttempt} runs in its own {@code REQUIRES_NEW} transaction on purpose:
 * {@code AuthService.login} throws a {@code RuntimeException} on every failure path, which
 * rolls back the outer transaction (the failure-counter write included — lockout
 * semantics stay exactly as they are today). Riding the outer transaction would make
 * AC1 ("exactly one audit row per attempt") false for every failed login, so the audit
 * insert commits independently while the login contract is untouched.
 */
@Service
public class AuthLoginAuditService {

  private static final int DEFAULT_PAGE_SIZE = 20;
  private static final int MAX_PAGE_SIZE = 100;
  private static final Instant NO_LOWER_BOUND = Instant.EPOCH;
  private static final Instant NO_UPPER_BOUND =
      Instant.ofEpochSecond(Long.MAX_VALUE / 1_000_000_000L, Long.MAX_VALUE % 1_000_000_000L);

  private final AuthLoginAuditRepository audits;
  private final Clock clock;

  public AuthLoginAuditService(AuthLoginAuditRepository audits, Clock clock) {
    this.audits = audits;
    this.clock = clock;
  }

  /** Writes exactly one audit row for one login attempt; never carries credential material. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void recordAttempt(UUID userId, String identifier, String ipAddress, String userAgent,
      boolean success, String failureReason) {
    audits.save(new AuthLoginAuditEntity(
        UUID.randomUUID(),
        userId,
        truncate(identifier, 255),
        truncate(ipAddress, 64),
        userAgent,
        success,
        truncate(failureReason, 255),
        Instant.now(clock)));
  }

  @Transactional(readOnly = true)
  public LoginAuditListResponse list(AuthenticatedUser user, String identifier, Boolean success,
      Instant from, Instant to, int page, int size) {
    requireReadRole(user);
    var normalizedPage = Math.max(page, 0);
    var normalizedSize = normalizeSize(size);
    // id DESC tiebreaker: occurred_at is not unique (same-clock rows), and an
    // unstable order would duplicate/drop rows across pages.
    var pageable = PageRequest.of(normalizedPage, normalizedSize,
        Sort.by(Sort.Direction.DESC, "occurredAt").and(Sort.by(Sort.Direction.DESC, "id")));
    var result = audits.search(
        normalizeIdentifier(identifier),
        success,
        from == null ? NO_LOWER_BOUND : from,
        to == null ? NO_UPPER_BOUND : to,
        pageable);
    return new LoginAuditListResponse(
        result.stream().map(AuthLoginAuditService::toView).toList(),
        result.getTotalElements(),
        result.getTotalPages(),
        normalizedPage,
        normalizedSize,
        "occurredAt,id:desc");
  }

  @Transactional(readOnly = true)
  public LoginAuditView get(AuthenticatedUser user, UUID id) {
    requireReadRole(user);
    return audits.findById(id).map(AuthLoginAuditService::toView)
        .orElseThrow(LoginAuditNotFoundException::new);
  }

  private void requireReadRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.AUDITOR) {
      throw new AuditReadForbiddenException();
    }
  }

  private String normalizeIdentifier(String identifier) {
    if (identifier == null || identifier.isBlank()) {
      return null;
    }
    return LikePattern.containsLower(identifier.trim());
  }

  private int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  static LoginAuditView toView(AuthLoginAuditEntity audit) {
    return new LoginAuditView(
        audit.getId(),
        audit.getUserId(),
        audit.getIdentifier(),
        audit.getIpAddress(),
        audit.getUserAgent(),
        audit.wasSuccess(),
        audit.getFailureReason(),
        audit.getOccurredAt());
  }

  public static class AuditReadForbiddenException extends RuntimeException {
  }

  public static class LoginAuditNotFoundException extends RuntimeException {
  }
}
