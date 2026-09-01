package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code equipment_change_notices} (blueprint H4, story 15-2). */
public interface EquipmentChangeNoticeRepository extends JpaRepository<EquipmentChangeNoticeEntity, UUID> {

  Optional<EquipmentChangeNoticeEntity> findByEcnNumber(String ecnNumber);
}
