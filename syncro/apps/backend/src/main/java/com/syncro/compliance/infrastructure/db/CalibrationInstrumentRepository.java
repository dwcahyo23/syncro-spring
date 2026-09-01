package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code calibration_instruments} (blueprint H3, story 15-2). */
public interface CalibrationInstrumentRepository extends JpaRepository<CalibrationInstrumentEntity, UUID> {

  Optional<CalibrationInstrumentEntity> findByInstrumentCode(String instrumentCode);
}
