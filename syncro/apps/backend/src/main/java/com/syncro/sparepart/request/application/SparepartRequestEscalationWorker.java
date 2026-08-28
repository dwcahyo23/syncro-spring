package com.syncro.sparepart.request.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled poller for sparepart-request escalation (story 12-3, FR-147). Runs every
 * configurable interval (default 60s) and delegates to the escalation service. Per-step
 * and per-request error isolation lives inside the service; this class only wraps the
 * top-level call so one unexpected failure never kills the schedule.
 */
@Component
public class SparepartRequestEscalationWorker {

  private static final Logger log = LoggerFactory.getLogger(SparepartRequestEscalationWorker.class);

  private final SparepartRequestEscalationService escalationService;

  public SparepartRequestEscalationWorker(SparepartRequestEscalationService escalationService) {
    this.escalationService = escalationService;
  }

  @Scheduled(fixedDelayString = "${syncro.sparepart.escalation.poll-interval-ms:60000}")
  public void poll() {
    try {
      escalationService.escalateStale();
    } catch (Exception e) {
      log.error("[SparepartRequestEscalationWorker] Unexpected error during escalation run: {}",
          e.getMessage(), e);
    }
  }
}