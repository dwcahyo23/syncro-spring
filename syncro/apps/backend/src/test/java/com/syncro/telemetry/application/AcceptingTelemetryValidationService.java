package com.syncro.telemetry.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;

class AcceptingTelemetryValidationService extends TelemetryValidationService {

  AcceptingTelemetryValidationService() {
    super(null, null);
  }

  @Override
  public Result validate(String topic, String payload) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now);
    var group = new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now);
    var machine = new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19", MachineStatus.ACTIVE,
        "Juki", LocalDate.parse("2026-05-27"), null, now, now);
    return new Result.Accepted(machine, new TelemetryPayload(true, 12.5, 100));
  }
}