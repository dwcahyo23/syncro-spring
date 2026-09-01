package com.syncro.maintenance.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code work_log_ratings} (blueprint C2, story 15-2). */
public interface WorkLogRatingRepository extends JpaRepository<WorkLogRatingEntity, UUID> {
}
