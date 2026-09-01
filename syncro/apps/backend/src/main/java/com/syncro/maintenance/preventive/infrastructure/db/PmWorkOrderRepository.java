package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code pm_work_orders} (blueprint F6, story 15-2). */
public interface PmWorkOrderRepository extends JpaRepository<PmWorkOrderEntity, UUID> {
}
