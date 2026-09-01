package com.syncro.compliance.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code historical_machine_records} (blueprint H7, story 15-2). */
public interface HistoricalMachineRecordRepository extends JpaRepository<HistoricalMachineRecordEntity, UUID> {
}
