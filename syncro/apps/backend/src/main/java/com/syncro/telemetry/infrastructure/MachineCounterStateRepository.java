package com.syncro.telemetry.infrastructure;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MachineCounterStateRepository extends JpaRepository<MachineCounterStateEntity, UUID> {
}
