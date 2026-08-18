package com.syncro.telemetry.infrastructure;

import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TelemetryQuarantineRepository extends JpaRepository<TelemetryQuarantineEntity, UUID> {

  Page<TelemetryQuarantineEntity> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
