package com.syncro.org.infrastructure.db;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for {@code machine_areas} (blueprint A11, story 15-2). */
public interface MachineAreaRepository extends JpaRepository<MachineAreaEntity, UUID> {
}
