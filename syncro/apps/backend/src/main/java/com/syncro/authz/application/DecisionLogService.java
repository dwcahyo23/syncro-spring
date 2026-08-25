package com.syncro.authz.application;

import com.syncro.authz.api.AuthzDtos.AuthzDecisionsPageView;
import com.syncro.authz.api.AuthzDtos.AuthzDecisionView;
import com.syncro.authz.domain.AuthzDecision;
import com.syncro.authz.infrastructure.AuthzDecisionEntity;
import com.syncro.authz.infrastructure.AuthzDecisionRepository;
import com.syncro.config.AuthzProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for OPA enforcement decisions (FR-164 / NFR-P2-7). One row per
 * {@link PolicyDecisionPoint} evaluation; masked by construction (only structural
 * fields — the OPA input schema never carries bodies, WAHA secrets, or phone numbers).
 *
 * <p>Logging is fail-open: a database failure must never break the enforcement path, so
 * {@link #record} swallows and logs persistence errors while the authz outcome (fail-deny)
 * stands. The retention purge is a {@code @Scheduled} job driven by
 * {@code syncro.authz.decision-log.retention-days} (default 30).
 */
@Service
public class DecisionLogService {

  private static final Logger log = LoggerFactory.getLogger(DecisionLogService.class);
  private static final int DEFAULT_PAGE_SIZE = 50;
  private static final int MAX_PAGE_SIZE = 200;

  private final AuthzDecisionRepository repository;
  private final AuthzProperties authzProperties;
  private final Clock clock;

  public DecisionLogService(AuthzDecisionRepository repository, AuthzProperties authzProperties,
      Clock clock) {
    this.repository = repository;
    this.authzProperties = authzProperties;
    this.clock = clock;
  }

  /**
   * Persists one enforcement decision. Never throws: failures are logged and the caller
   * (enforcement path) continues with its outcome untouched.
   *
   * <p>Deliberately not {@code @Transactional}: each {@code repository.save()} runs in its
   * own transaction (SimpleJpaRepository), so a failed insert rolls back in isolation and
   * the swallowed exception can never surface as an UnexpectedRollbackException later.
   */
  public void record(String decisionId, String policyRevision, boolean allowed, boolean degraded,
      UUID userId, String action, String resourceType) {
    record(new AuthzDecision(parseDecisionId(decisionId), policyRevision, allowed, degraded,
        userId, truncateAction(action), resourceType, Instant.now(clock)));
  }

  /**
   * Persists one enforcement decision; never throws (fail-open on logging).
   *
   * <p>The action column is VARCHAR(255); a longer action (theoretically possible from an
   * over-long request path) is truncated so the FR-164 audit row is never silently lost to
   * a DataIntegrityViolationException.
   */
  public void record(AuthzDecision decision) {
    try {
      repository.save(new AuthzDecisionEntity(
          new AuthzDecision(decision.decisionId(), decision.policyRevision(), decision.allowed(),
              decision.degraded(), decision.userId(), truncateAction(decision.action()),
              decision.resourceType(), decision.decidedAt())));
    } catch (Exception failure) {
      // Fail-open on logging: the enforcement outcome has already been decided (fail-deny
      // on authz); a broken decision log must not turn an allow into a 500.
      log.warn("[AUTHZ] Failed to persist decision {} for action {} — logging skipped",
          decision.decisionId(), decision.action(), failure);
    }
  }

  /** Newest-first paged read view for the decision-log endpoint (SUPER_ADMIN/AUDITOR). */
  @Transactional(readOnly = true)
  public AuthzDecisionsPageView list(int page, int size) {
    var pageable = PageRequest.of(Math.max(page, 0), normalizeSize(size));
    var result = repository.findAllByOrderByDecidedAtDesc(pageable);
    return new AuthzDecisionsPageView(
        result.stream().map(this::toView).toList(),
        result.getTotalElements(),
        pageable.getPageNumber(),
        pageable.getPageSize());
  }

  /** Deletes rows decided strictly before {@code cutoff}; returns the removed count. */
  @Transactional
  public long purgeOlderThan(Instant cutoff) {
    return repository.deleteByDecidedAtBefore(cutoff);
  }

  /**
   * Retention purge: deletes decisions older than
   * {@code syncro.authz.decision-log.retention-days} (default 30). Runs hourly.
   *
   * <p>{@code @Transactional} here (not on the helper) so the self-invocation from the
   * {@code @Scheduled} job still runs the {@code @Modifying} delete inside a transaction —
   * a plain derived-call would throw TransactionRequiredException on the hourly run.
   */
  @Scheduled(fixedDelayString = "${syncro.authz.decision-log.purge-interval-ms:3600000}")
  @Transactional
  public void purgeExpired() {
    var retentionDays = authzProperties.decisionLogRetentionDays();
    if (retentionDays <= 0) {
      return;
    }
    var cutoff = Instant.now(clock).minus(retentionDays, ChronoUnit.DAYS);
    var removed = purgeOlderThan(cutoff);
    if (removed > 0) {
      log.info("[AUTHZ] Decision-log purge removed {} rows older than {}", removed, cutoff);
    }
  }

  private AuthzDecisionView toView(AuthzDecisionEntity entity) {
    return new AuthzDecisionView(
        entity.getId(),
        entity.getDecisionId(),
        entity.getPolicyRevision(),
        entity.isAllowed(),
        entity.isDegraded(),
        entity.getSubjectUserId(),
        entity.getAction(),
        entity.getResourceType(),
        entity.getDecidedAt());
  }

  private int normalizeSize(int size) {
    if (size < 1) {
      return DEFAULT_PAGE_SIZE;
    }
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private static UUID parseDecisionId(String decisionId) {
    if (decisionId == null || decisionId.isBlank()) {
      // No OPA decision_id (degraded allowlist / OPA-down deny): keep null so the stored
      // row never fabricates a UUID that cannot be correlated with the audit record.
      return null;
    }
    try {
      return UUID.fromString(decisionId);
    } catch (IllegalArgumentException malformed) {
      return null;
    }
  }

  private static String truncateAction(String action) {
    if (action == null || action.length() <= 255) {
      return action;
    }
    return action.substring(0, 255);
  }
}
