package com.syncro.shiftconfig.infrastructure;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MachineShiftWindowRepository extends JpaRepository<MachineShiftWindowEntity, UUID> {
  @Modifying
  @Query("delete from MachineShiftWindowEntity w where w.machine.id = :machineId")
  void deleteByMachineId(@Param("machineId") UUID machineId);

  List<MachineShiftWindowEntity> findAllByMachineIdOrderByShiftNumber(UUID machineId);
}