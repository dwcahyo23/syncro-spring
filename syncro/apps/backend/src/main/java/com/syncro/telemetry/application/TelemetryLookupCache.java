package com.syncro.telemetry.application;

import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
class TelemetryLookupCache {

  private final PlantRepository plants;
  private final MachineRepository machines;

  TelemetryLookupCache(PlantRepository plants, MachineRepository machines) {
    this.plants = plants;
    this.machines = machines;
  }

  @Cacheable(value = "telemetry-plants", key = "#plantCode.toLowerCase()", unless = "#result.isEmpty()")
  public Optional<PlantEntity> findPlant(String plantCode) {
    return plants.findByCodeIgnoreCase(plantCode);
  }

  @Cacheable(value = "telemetry-machines", key = "#plantId + ':' + #machineCode.toLowerCase()", unless = "#result.isEmpty()")
  public Optional<MachineEntity> findMachine(UUID plantId, String machineCode) {
    return machines.findByPlantIdAndCodeIgnoreCaseWithPlantAndGroup(plantId, machineCode);
  }
}
