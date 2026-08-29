package com.syncro.settings.infrastructure;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Settings persistence (story 14-3, FR-175). Single-row table (singleton_key = 1 CHECK).
 */
public interface SettingsRepository extends JpaRepository<SettingsEntity, Short> {

  Optional<SettingsEntity> findFirstByOrderBySingletonKeyAsc();
}
