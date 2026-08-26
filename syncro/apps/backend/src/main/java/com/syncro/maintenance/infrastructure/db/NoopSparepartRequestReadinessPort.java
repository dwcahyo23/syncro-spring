package com.syncro.maintenance.infrastructure.db;

import com.syncro.maintenance.application.SparepartRequestReadinessPort;
import org.springframework.stereotype.Component;

/**
 * Placeholder readiness port (AD-5): reports "no live non-READY request" so manual
 * ON_PROCUREMENT placement works and derivation stays a no-op until Epic 12 replaces
 * this bean with the real sparepart-request implementation.
 */
@Component
public class NoopSparepartRequestReadinessPort implements SparepartRequestReadinessPort {

  @Override
  public boolean hasLiveNonReadyRequest(String workOrderId) {
    return false;
  }
}
