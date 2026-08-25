package com.syncro.maintenance.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkOrderCategoryRepository extends JpaRepository<WorkOrderCategoryEntity, UUID> {

  Optional<WorkOrderCategoryEntity> findByCode(String code);

  boolean existsByCode(String code);

  List<WorkOrderCategoryEntity> findAllByOrderByCodeAsc();
}
