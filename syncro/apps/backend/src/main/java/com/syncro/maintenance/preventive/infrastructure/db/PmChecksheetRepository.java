package com.syncro.maintenance.preventive.infrastructure.db;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for {@code pm_checksheets} (blueprint F2, story 15-2). */
public interface PmChecksheetRepository extends JpaRepository<PmChecksheetEntity, UUID> {

  Optional<PmChecksheetEntity> findByMachineIdAndFrequencyIdAndRevisionNo(UUID machineId,
      UUID frequencyId, int revisionNo);

  List<PmChecksheetEntity> findByMachineIdOrderByRevisionNoDesc(UUID machineId);

  List<PmChecksheetEntity> findByFrequencyIdOrderByRevisionNoDesc(UUID frequencyId);

  List<PmChecksheetEntity> findByMachineIdAndFrequencyIdOrderByRevisionNoAsc(UUID machineId,
      UUID frequencyId);

  boolean existsByMachineIdAndFrequencyId(UUID machineId, UUID frequencyId);

  /** Highest revision number for a (machine, frequency) pair; empty when none exist. */
  @Query("select max(c.revisionNo) from PmChecksheetEntity c "
      + "where c.machineId = :machineId and c.frequencyId = :frequencyId")
  Optional<Integer> findMaxRevisionNo(@Param("machineId") UUID machineId,
      @Param("frequencyId") UUID frequencyId);
}
