package com.syncro.compliance.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code calibration_instruments} (blueprint H3, story 15-2;
 * scope queries for 21-2). Instrument scope is plant-only: {@code plant_id IS
 * NULL} marks a global instrument visible to every authenticated user (the
 * NC-unlinked precedent), otherwise the plant must be in the caller's derived
 * scope. SUPER_ADMIN passes {@code unrestricted=true}. Calibration status is
 * derived on read in the service, so no status predicate lives here.
 */
public interface CalibrationInstrumentRepository extends JpaRepository<CalibrationInstrumentEntity, UUID> {

  Optional<CalibrationInstrumentEntity> findByInstrumentCode(String instrumentCode);

  boolean existsByInstrumentCode(String instrumentCode);

  /** Scope-filtered list, most-overdue first (next calibration date ascending). */
  @Query("""
      select i from CalibrationInstrumentEntity i
      where (:unrestricted = true or i.plantId is null or i.plantId in :plantIds)
      order by i.nextCalibrationDate asc, i.createdAt desc
      """)
  List<CalibrationInstrumentEntity> findScoped(@Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds);

  /** Visibility twin of {@link #findScoped} for a single instrument (detail/mutation gate). */
  @Query("""
      select count(i) from CalibrationInstrumentEntity i
      where i.id = :id
        and (:unrestricted = true or i.plantId is null or i.plantId in :plantIds)
      """)
  long countVisible(@Param("id") UUID id, @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds);

  /** Create/update-time reference validation: the owning plant must exist. */
  @Query("select count(p) from PlantEntity p where p.id = :plantId")
  long countPlant(@Param("plantId") UUID plantId);
}
