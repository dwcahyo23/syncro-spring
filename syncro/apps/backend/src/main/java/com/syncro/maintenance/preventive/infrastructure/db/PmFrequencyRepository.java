package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_frequencies} (blueprint F1, story 15-2). */
public interface PmFrequencyRepository extends JpaRepository<PmFrequencyEntity, UUID> {

  Optional<PmFrequencyEntity> findByCode(String code);
}
