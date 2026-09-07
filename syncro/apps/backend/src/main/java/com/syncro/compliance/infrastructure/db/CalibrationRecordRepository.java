package com.syncro.compliance.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@code calibration_records} (blueprint H3, story 15-2).
 * Append-only history — there is deliberately no update or delete path; the
 * single-row lookup serves the record detail endpoint.
 */
public interface CalibrationRecordRepository extends JpaRepository<CalibrationRecordEntity, UUID> {

  List<CalibrationRecordEntity> findByInstrumentIdOrderByCalibrationDateDesc(UUID instrumentId);

  Optional<CalibrationRecordEntity> findByInstrumentIdAndId(UUID instrumentId, UUID id);
}
