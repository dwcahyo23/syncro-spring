package com.syncro.compliance.infrastructure.db;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code machine_setup_baselines} (blueprint H5, story 15-2). */
public interface MachineSetupBaselineRepository extends JpaRepository<MachineSetupBaselineEntity, UUID> {

  Optional<MachineSetupBaselineEntity> findByMachineIdAndVersion(UUID machineId, int version);
}
