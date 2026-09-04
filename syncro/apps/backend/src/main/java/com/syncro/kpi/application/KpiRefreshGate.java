package com.syncro.kpi.application;

import com.syncro.kpi.domain.KpiAggregateRefreshStatus;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogEntity;
import com.syncro.kpi.infrastructure.db.KpiAggregateRefreshLogRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-log gating for KPI materialization (story 20-1, blueprint G7, AD-20). One
 * row per {@code refresh_key} (e.g. {@code mtbf:2026-08}): RUNNING marks an in-flight
 * pass and blocks a concurrent refresh of the same key; SUCCESS/FAILED allows retry.
 *
 * <p>Both methods commit in their own transaction ({@code REQUIRES_NEW}) so the
 * RUNNING marker is visible to a concurrent caller before the (possibly long) compute
 * finishes, and a FAILED outcome survives the compute transaction's rollback.
 *
 * <p>Review hardening (20-1): the claim takes a pessimistic row lock so two concurrent
 * passes cannot both observe a non-RUNNING row; a first insert racing the unique
 * constraint falls back to the locked claim; and a RUNNING marker older than
 * {@link #STALE_RUNNING_AFTER} is reclaimed, so a crashed pass cannot wedge a key
 * forever.
 */
@Service
public class KpiRefreshGate {

  /** A RUNNING marker older than this is treated as a crashed pass and reclaimed. */
  static final Duration STALE_RUNNING_AFTER = Duration.ofHours(2);

  private final KpiAggregateRefreshLogRepository logs;
  private final Clock clock;

  public KpiRefreshGate(KpiAggregateRefreshLogRepository logs, Clock clock) {
    this.logs = logs;
    this.clock = clock;
  }

  /**
   * Claims the key for a refresh pass. Returns false when a live RUNNING pass already
   * owns it (concurrent refresh skipped); otherwise flips the row to RUNNING and
   * returns true. Stale RUNNING rows (crashed pass) are reclaimed.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean tryStart(String refreshKey) {
    var now = Instant.now(clock);
    var existing = logs.findByRefreshKeyForUpdate(refreshKey);
    if (existing.isPresent()) {
      return claim(existing.get(), now);
    }
    try {
      logs.saveAndFlush(new KpiAggregateRefreshLogEntity(UUID.randomUUID(), refreshKey, now,
          KpiAggregateRefreshStatus.RUNNING, null));
      return true;
    } catch (DataIntegrityViolationException race) {
      // A concurrent pass inserted the row first and owns the RUNNING marker — skip;
      // that pass materializes this key. (ponytail: no retry-in-new-tx; the sweep is
      // idempotent and the next pass covers any miss.)
      return false;
    }
  }

  private boolean claim(KpiAggregateRefreshLogEntity log, Instant now) {
    if (log.getStatus() == KpiAggregateRefreshStatus.RUNNING
        && !log.getRefreshedAt().isBefore(now.minus(STALE_RUNNING_AFTER))) {
      return false;
    }
    log.finish(KpiAggregateRefreshStatus.RUNNING, null, now);
    logs.saveAndFlush(log);
    return true;
  }

  /** Terminal outcome with window/status evidence (traceId + row count in the message). */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String refreshKey, KpiAggregateRefreshStatus status, String message) {
    logs.findByRefreshKey(refreshKey).ifPresent(log -> {
      log.finish(status, message, Instant.now(clock));
      logs.saveAndFlush(log);
    });
  }
}
