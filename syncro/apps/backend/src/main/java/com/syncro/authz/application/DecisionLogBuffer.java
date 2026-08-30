package com.syncro.authz.application;

import com.syncro.authz.domain.AuthzDecision;
import com.syncro.authz.infrastructure.AuthzDecisionEntity;
import com.syncro.authz.infrastructure.AuthzDecisionRepository;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write-behind buffer for decision-log persistence (DW-132). {@link DecisionLogService}
 * enqueues a decision per enforcement call; a single bounded worker persists them off the
 * request hot path, so a slow DB can no longer double the enforced request latency or trip
 * the lowered OPA breaker.
 *
 * <p>Fail-open is preserved end-to-end: a persistence failure is logged and never thrown,
 * and a full queue (sustained DB outage) drops the newest decisions with a WARN rather than
 * blocking the enforcement path. Decisions already submitted to the executor are drained on
 * graceful shutdown; only a hard crash loses buffered rows — the same ceiling as any
 * async audit logger.
 */
public class DecisionLogBuffer {

  private static final Logger log = LoggerFactory.getLogger(DecisionLogBuffer.class);
  private static final int QUEUE_CAPACITY = 10_000;

  private final AuthzDecisionRepository repository;
  private final ExecutorService executor;

  public DecisionLogBuffer(AuthzDecisionRepository repository) {
    this.repository = repository;
    this.executor = new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(QUEUE_CAPACITY),
        runnable -> {
          Thread thread = new Thread(runnable, "decision-log-writer");
          thread.setDaemon(true);
          return thread;
        });
  }

  /** Queues one decision for asynchronous persistence; never throws (fail-open on overload). */
  public void enqueue(AuthzDecision decision) {
    try {
      executor.execute(() -> persist(decision));
    } catch (RejectedExecutionException failure) {
      log.warn("[AUTHZ] Decision-log queue full, dropping decision {} for action {}",
          decision.decisionId(), decision.action());
    }
  }

  /**
   * Waits until every previously enqueued decision has been persisted (or failed). A no-op
   * barrier task on the single-writer FIFO executor; used by tests and any shutdown-critical
   * caller that must observe the rows before continuing. Never throws.
   */
  public void flush() {
    try {
      executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
    } catch (Exception failure) {
      log.warn("[AUTHZ] Decision-log flush timed out or was interrupted", failure);
    }
  }

  void shutdown() {
    executor.shutdown();
  }

  private void persist(AuthzDecision decision) {
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

  private static String truncateAction(String action) {
    if (action == null || action.length() <= 255) {
      return action;
    }
    return action.substring(0, 255);
  }
}
