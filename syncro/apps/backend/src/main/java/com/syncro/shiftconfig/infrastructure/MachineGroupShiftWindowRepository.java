package com.syncro.shiftconfig.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineGroupShiftWindowRepository extends JpaRepository<MachineGroupShiftWindowEntity, UUID> {
  @Modifying
  @Query("delete from MachineGroupShiftWindowEntity w where w.machineGroup.id = :machineGroupId")
  void deleteByMachineGroupId(@Param("machineGroupId") UUID machineGroupId);

  List<MachineGroupShiftWindowEntity> findAllByMachineGroupIdOrderByShiftNumber(UUID machineGroupId);
}