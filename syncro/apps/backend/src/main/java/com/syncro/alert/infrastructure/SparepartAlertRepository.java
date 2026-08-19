package com.syncro.alert.infrastructure;

import com.syncro.alert.domain.SparepartAlertStatus;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SparepartAlertRepository extends JpaRepository<SparepartAlertEntity, UUID> {

  boolean existsByMachineSparepartInstallationIdAndThresholdPercentageAndStatusNot(
      UUID installationId, int thresholdPercentage, SparepartAlertStatus excludedStatus);
}
