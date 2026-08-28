package com.syncro.sparepart.request.infrastructure.db;

import com.syncro.maintenance.application.SparepartRequestReadinessPort;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * Real sparepart-request readiness port (AD-5, story 12-2). Replaces the Noop placeholder
 * from Epic 10. Queries the sparepart_requests table for any live non-READY request on
 * the given workorder. The {@code @Primary} annotation takes precedence over the Noop
 * bean (which stays for test isolation).
 *
 * <p>Module boundary respected: sparepart.request module queries its own repository and
 * implements the maintenance module's port interface.
 */
@Component
@Primary
public class SparepartRequestReadinessService implements SparepartRequestReadinessPort {

  private final SparepartRequestRepository requests;

  public SparepartRequestReadinessService(SparepartRequestRepository requests) {
    this.requests = requests;
  }

  @Override
  public boolean hasLiveNonReadyRequest(String workOrderId) {
    return requests.hasLiveNonReadyRequest(workOrderId);
  }
}