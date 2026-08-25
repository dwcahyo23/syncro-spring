package com.syncro.org.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamMachineRepository extends JpaRepository<TeamMachineEntity, TeamMachineId> {

  @Query("""
      select tm from TeamMachineEntity tm
      join fetch tm.machine m
      where tm.id.teamId = :teamId
      order by m.code asc
      """)
  List<TeamMachineEntity> findAllByTeamId(@Param("teamId") UUID teamId);

  boolean existsByIdTeamIdAndIdMachineId(UUID teamId, UUID machineId);

  long countByIdTeamId(UUID teamId);

  void deleteByIdTeamIdAndIdMachineId(UUID teamId, UUID machineId);

  void deleteByIdTeamId(UUID teamId);
}
