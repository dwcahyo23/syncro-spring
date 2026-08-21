package com.syncro.notification.application;

import com.syncro.config.NotificationProperties;
import com.syncro.notification.domain.NotificationJobStatus;
import com.syncro.notification.infrastructure.NotificationAttemptEntity;
import com.syncro.notification.infrastructure.NotificationAttemptRepository;
import com.syncro.notification.infrastructure.NotificationJobEntity;
import com.syncro.notification.infrastructure.NotificationJobRepository;
import com.syncro.notification.infrastructure.WahaClient;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Assembles the notification dispatch worker health status from the poll tracker, the WAHA
 * circuit breaker state, and the notification job/attempt tables.
 *
 * <p>State derivation (scheduled poller, so state is inferred — there is no thread to inspect):
 * <ul>
 *   <li>never polled → {@code STOPPED} (worker never ran)</li>
 *   <li>WAHA circuit OPEN / FORCED_OPEN / DISABLED → {@code DEGRADED} (dispatch cannot succeed
 *       while the circuit blocks calls)</li>
 *   <li>last poll older than the stale threshold → {@code DEGRADED} (worker not polling,
 *       reason + timestamp)</li>
 *   <li>otherwise → {@code RUNNING}</li>
 * </ul>
 *
 * <p>Never-polled is checked first so a worker that never ran reports the strongest signal
 * ({@code STOPPED}/CRITICAL) even if the circuit is also open. When the worker has polled,
 * an open circuit degrades the status regardless of poll freshness.
 */
@Service
public class NotificationWorkerStatusService {

  private static final List<NotificationJobStatus> PENDING_STATUSES =
      List.of(NotificationJobStatus.PENDING, NotificationJobStatus.RATE_LIMITED);

  private final NotificationWorkerTracker tracker;
  private final NotificationJobRepository jobRepository;
  private final NotificationAttemptRepository attemptRepository;
  private final WahaClient wahaClient;
  private final NotificationProperties properties;
  private final Clock clock;

  public NotificationWorkerStatusService(NotificationWorkerTracker tracker,
      NotificationJobRepository jobRepository,
      NotificationAttemptRepository attemptRepository,
      WahaClient wahaClient,
      NotificationProperties properties,
      Clock clock) {
    this.tracker = tracker;
    this.jobRepository = jobRepository;
    this.attemptRepository = attemptRepository;
    this.wahaClient = wahaClient;
    this.properties = properties;
    this.clock = clock;
  }

  public NotificationWorkerStatus status() {
    Instant lastPollAt = tracker.lastPollAt();
    Instant now = clock.instant();
    Duration staleThreshold = properties.worker().staleThreshold();

    NotificationWorkerState state;
    String reason;
    Instant staleSince = null;

    CircuitBreaker.State circuitState = wahaClient.getCircuitBreaker().getState();
    if (lastPollAt == null) {
      state = NotificationWorkerState.STOPPED;
      reason = "Worker never polled";
    } else if (circuitState == CircuitBreaker.State.OPEN
        || circuitState == CircuitBreaker.State.FORCED_OPEN
        || circuitState == CircuitBreaker.State.DISABLED) {
      state = NotificationWorkerState.DEGRADED;
      reason = "WAHA circuit breaker is " + circuitState;
    } else {
      Duration elapsed = Duration.between(lastPollAt, now);
      if (!elapsed.isNegative() && elapsed.compareTo(staleThreshold) > 0) {
        state = NotificationWorkerState.DEGRADED;
        reason = "Worker last polled at " + lastPollAt;
        staleSince = lastPollAt.plus(staleThreshold);
      } else {
        state = NotificationWorkerState.RUNNING;
        reason = null;
      }
    }

    long pendingJobCount = jobRepository.countByStatusIn(PENDING_STATUSES);
    Instant failedWindowStart = now.minus(properties.worker().failedWindow());
    long recentFailedCount = attemptRepository.countByStatusAndAttemptedAtAfter("FAILED", failedWindowStart);

    Optional<NotificationAttemptEntity> lastFailedAttempt = attemptRepository
        .findTopByStatusAndAttemptedAtAfterOrderByAttemptedAtDesc("FAILED", failedWindowStart);

    String lastFailureReason = lastFailedAttempt
        .map(NotificationAttemptEntity::getResponseDetail)
        .map(this::truncate)
        .orElse(null);

    UUID lastFailedAlertId = lastFailedAttempt
        .flatMap(attempt -> jobRepository.findById(attempt.getJobId()))
        .map(NotificationJobEntity::getAlertId)
        .orElse(null);

    String lastSuccessfulSendAt = attemptRepository
        .findTopByStatusAndAttemptedAtAfterOrderByAttemptedAtDesc("SENT", failedWindowStart)
        .map(a -> a.getAttemptedAt().toString())
        .orElse(null);

    return new NotificationWorkerStatus(
        state,
        state.statusLabel(),
        state.statusSeverity(),
        reason,
        now.toString(),
        lastPollAt == null ? null : lastPollAt.toString(),
        staleSince == null ? null : staleSince.toString(),
        pendingJobCount,
        recentFailedCount,
        lastFailureReason,
        lastFailedAlertId,
        lastSuccessfulSendAt,
        circuitState.name());
  }

  private String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 512 ? value.substring(0, 512) : value;
  }
}
