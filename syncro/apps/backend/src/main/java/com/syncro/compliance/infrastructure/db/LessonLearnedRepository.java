package com.syncro.compliance.infrastructure.db;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@code lesson_learned} (blueprint H6, story 15-2; scope +
 * search queries for 21-3). Lesson scope is the 21-1 {@code findScoped}
 * predicate INCLUDING the null-machine clause — a lesson without a machine link
 * is visible to every authenticated user (the NC-unlinked precedent). SUPER_ADMIN
 * passes {@code unrestricted=true}. The search is a native query because the tag
 * filter needs the Postgres {@code tags::text} containment (spec "Always"); the
 * {@code q}/{@code tag} filters use the empty-string sentinel and pre-escaped
 * {@link com.syncro.common.LikePattern} patterns with {@code escape '\'}.
 * Native {@code IN} cannot render an empty collection, so the service passes a
 * never-matching sentinel id when a scope set is empty.
 */
public interface LessonLearnedRepository extends JpaRepository<LessonLearnedEntity, UUID> {

  Optional<LessonLearnedEntity> findByProjectId(String projectId);

  boolean existsByProjectId(String projectId);

  /** Scope-filtered search: q over title/problem_summary (case-insensitive), tag containment. */
  @Query(value = """
      select l.* from lesson_learned l
      left join machines m on m.id = l.machine_id
      where (:unrestricted = true or l.machine_id is null
             or m.plant_id in :plantIds or m.machine_group_id in :groupIds)
        and (:q = '' or lower(l.title) like :q escape '\\'
             or lower(l.problem_summary) like :q escape '\\')
        and (:tag = '' or cast(l.tags as text) ilike :tag escape '\\')
      order by l.created_at desc
      """, nativeQuery = true)
  List<LessonLearnedEntity> findScoped(@Param("unrestricted") boolean unrestricted,
      @Param("plantIds") Collection<UUID> plantIds, @Param("groupIds") Collection<UUID> groupIds,
      @Param("q") String q, @Param("tag") String tag);

  /** Visibility twin of {@link #findScoped} for a single lesson (detail/mutation gate). */
  @Query("""
      select count(l) from LessonLearnedEntity l
      left join MachineEntity m on m.id = l.machineId
      where l.id = :id
        and (:unrestricted = true or l.machineId is null
             or m.plant.id in :plantIds or m.machineGroup.id in :groupIds)
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

  /** Create/update-time reference validation: the event links must resolve. */
  @Query("select count(n) from NonConformanceEntity n where n.id = :ncId")
  long countNonConformance(@Param("ncId") UUID ncId);

  @Query("select count(e) from EightDReportEntity e where e.id = :eightDId")
  long countEightD(@Param("eightDId") UUID eightDId);

  @Query("select count(w) from WorkOrderEntity w where w.id = :workOrderId")
  long countWorkOrder(@Param("workOrderId") String workOrderId);
}
