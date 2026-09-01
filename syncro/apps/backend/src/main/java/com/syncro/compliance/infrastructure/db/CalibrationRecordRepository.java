package com.syncro.compliance.infrastructure.db;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code calibration_records} (blueprint H3, story 15-2). */
public interface CalibrationRecordRepository extends JpaRepository<CalibrationRecordEntity, UUID> {

  List<CalibrationRecordEntity> findByInstrumentIdOrderByCalibrationDateDesc(UUID instrumentId);
}
