package com.syncro.kpi.scheduled;

import com.syncro.config.KpiProperties;
import com.syncro.kpi.application.KpiMaterializationService;
import com.syncro.maintenance.application.WorkorderSyncedEvent;
import jakarta.annotation.PreDestroy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * KPI refresh scheduler (story 20-1, AD-20). Two triggers:
 * <ul>
 *   <li>Scheduled sweep — UTC cron from {@code syncro.kpi.refresh-cron} (typed config,
 *       never hardcoded). Re-materializes the current month plus a lookback window so
 *       late corrections and backdated sync rows land in prior months.</li>
 *   <li>Sync invalidation — {@link WorkorderSyncedEvent} after a sync upsert commits
 *       (AFTER_COMMIT, so the refresh never reads pre-commit state and never runs
 *       inside the sync batch transaction). Coalesced: a burst of synced rows triggers
 *       one sweep, not one per row.</li>
 * </ul>
 *
 * <p>Review 20-1 hardening: the sweep runs on a dedicated single-thread executor (an
 * AFTER_COMMIT listener must not recompute months on the committing request thread),
 * and a burst arriving inside the cooldown schedules a trailing sweep instead of being
 * dropped — the last rows of a burst are never left unmaterialized until the cron.
 *
 * <p>Idempotency and concurrency are owned by {@link KpiMaterializationService} via the
 * refresh-log gate (RUNNING blocks a concurrent same-key pass) — this class adds no
 * locking of its own beyond the in-process coalescing.
 */
@Component
public class KpiRefreshScheduler {

  private static final Logger log = LoggerFactory.getLogger(KpiRefreshScheduler.class);

  private final KpiMaterializationService materialization;
  private final KpiProperties properties;
  private final Clock clock;
  /** Coalesces sync-triggered sweeps: a burst of synced rows triggers one sweep per cooldown. */
  private final ScheduledExecutorService sweepExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
    var thread = new Thread(runnable, "kpi-refresh-sweep");
    thread.setDaemon(true);
    return thread;
  });
  private volatile Instant lastSyncSweepAt = Instant.EPOCH;
  /** True while a sweep (running or queued) covers the current burst. */
  private volatile boolean sweepScheduled;
  private static final Duration SYNC_SWEEP_COOLDOWN = Duration.ofMinutes(1);

  public KpiRefreshScheduler(KpiMaterializationService materialization, KpiProperties properties,
      Clock clock) {
    this.materialization = materialization;
    this.properties = properties;
    this.clock = clock;
  }

  @Scheduled(cron = "${syncro.kpi.refresh-cron:0 0 3 * * *}", zone = "UTC")
  public void scheduledRefresh() {
    if (!properties.enabled()) {
      return;
    }
    sweepExecutor.execute(() -> runSweep("scheduled"));
  }

  /**
   * Sync mutation invalidation (AD-6/AD-20): re-materialize after synced workorder
   * changes. Coalesced: at most one sweep per cooldown; a burst arriving inside the
   * cooldown schedules a trailing sweep for the remaining window instead of dropping.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onWorkorderSynced(WorkorderSyncedEvent event) {
    if (!properties.enabled()) {
      return;
    }
    var now = Instant.now(clock);
    var cooldownRemaining = Duration.between(now, lastSyncSweepAt.plus(SYNC_SWEEP_COOLDOWN));
    if (!cooldownRemaining.isNegative() && !cooldownRemaining.isZero()) {
      if (sweepScheduled) {
        return; // a trailing sweep already covers this burst
      }
      sweepScheduled = true;
      var delayMs = Math.max(1L, cooldownRemaining.toMillis());
      log.debug("[KPI] sync invalidation trailing sweep in {}ms (workOrderId={})", delayMs,
          event.workOrderId());
      sweepExecutor.schedule(() -> {
        sweepScheduled = false;
        lastSyncSweepAt = Instant.now(clock);
        runSweep("sync-invalidation");
      }, delayMs, TimeUnit.MILLISECONDS);
      return;
    }
    sweepScheduled = true;
    sweepExecutor.execute(() -> {
      sweepScheduled = false;
      lastSyncSweepAt = Instant.now(clock);
      log.info("[KPI] sync invalidation sweep (workOrderId={})", event.workOrderId());
      runSweep("sync-invalidation");
    });
  }

  @PreDestroy
  public void shutdown() {
    sweepExecutor.shutdownNow();
  }

  private void runSweep(String trigger) {
    var currentMonth = YearMonth.now(clock.withZone(ZoneOffset.UTC)).atDay(1);
    var months = properties.lookbackMonths() + 1;
    for (int i = 0; i < months; i++) {
      var month = currentMonth.minusMonths(i);
      try {
        var outcomes = materialization.refreshMonth(month);
        outcomes.forEach(o -> log.info("[KPI] {} refresh {} {} {}", trigger, o.type().key(),
            month, o.status()));
      } catch (RuntimeException exception) {
        // One month's failure must not abort the sweep; each type/month is independently
        // gated and evidenced in kpi_aggregate_refresh_logs.
        log.error("[KPI] {} sweep failed for month {}: {}", trigger, month, exception.getMessage(),
            exception);
      }
    }
  }
}
