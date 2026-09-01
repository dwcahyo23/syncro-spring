package com.syncro.org.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code menu_features} (blueprint A7, story 15-2). */
public interface MenuFeatureRepository extends JpaRepository<MenuFeatureEntity, UUID> {

  Optional<MenuFeatureEntity> findByCode(String code);
}
