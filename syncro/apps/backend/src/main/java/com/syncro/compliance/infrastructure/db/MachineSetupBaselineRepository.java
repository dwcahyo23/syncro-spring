package com.syncro.compliance.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code machine_setup_baselines} (blueprint H5, story 15-2;
 * scope queries for 21-3). Baseline scope follows the 21-1 {@code findScoped}
 * predicate minus the null-machine clause — {@code machine_id} is NOT NULL, so
 * every baseline is visible exactly when its machine's plant OR machine-group is
 * in the caller's derived scope (EquipmentChangeNoticeRepository shape).
 * SUPER_ADMIN passes {@code unrestricted=true}.
 */
public interface MachineSetupBaselineRepository
    extends JpaRepository<MachineSetupBaselineEntity, UUID> {

  Optional<MachineSetupBaselineEntity> findByMachineIdAndVersion(UUID machineId, int version);

  /** The machine's currently-active baselines (normally one; supersede flips them). */
  List<MachineSetupBaselineEntity> findByMachineIdAndActiveTrue(UUID machineId);

  /** Server-assigned version source: max+1 per machine (spec Design Notes). */
  @Query("select coalesce(max(b.version), 0) from MachineSetupBaselineEntity b "
      + "where b.machineId = :machineId")
  int maxVersion(@Param("machineId") UUID machineId);

  /** Scope-filtered list with optional machine + active-only filters. */
  @Query("""
      select b from MachineSetupBaselineEntity b
      left join MachineEntity m on m.id = b.machineId
      where (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
        and (:machineId is null or b.machineId = :machineId)
        and (:activeOnly = false or b.active = true)
      order by b.machineId asc, b.version desc
      """)
  List<MachineSetupBaselineEntity> findScoped(@Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds,
      @Param("machineId") UUID machineId, @Param("activeOnly") boolean activeOnly);

  /** Visibility twin of {@link #findScoped} for a single baseline (detail/mutation gate). */
  @Query("""
      select count(b) from MachineSetupBaselineEntity b
      left join MachineEntity m on m.id = b.machineId
      where b.id = :id
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      """)
  long countVisible(@Param("id") UUID id, @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds);

  /** Plant + group of a machine — create-time scope gate and audit plantId resolution. */
  interface MachineScopeView {
    UUID getPlantId();

    UUID getGroupId();
  }

  @Query("select m.plant.id as plantId, m.machineGroup.id as groupId from MachineEntity m "
      + "where m.id = :machineId")
  Optional<MachineScopeView> findMachineScope(@Param("machineId") UUID machineId);

  /**
   * Create-time reference validation (review 21-3 M1): the optional ECN link must
   * resolve AND be visible in the caller's scope — the 21-2 ECN countVisible
   * predicate — so a user cannot pre-empt another plant's ECN through
   * uq_machine_setup_baselines_ecn.
   */
  @Query("""
      select count(e) from EquipmentChangeNoticeEntity e
      left join MachineEntity m on m.id = e.machineId
      where e.id = :ecnId
        and (:unrestricted = true or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
      """)
  long countEcnVisible(@Param("ecnId") UUID ecnId, @Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds);
}
