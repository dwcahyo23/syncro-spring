package com.syncro.telemetry.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import org.springframework.stereotype.Service;

@Service
public class TelemetryValidationService {

  public sealed interface Result permits Result.Accepted, Result.Rejected {
    record Accepted(MachineEntity machine, TelemetryPayload payload) implements Result {
    }

    record Rejected(String reason, String field) implements Result {
    }
  }

  private final PlantRepository plants;
  private final MachineRepository machines;
  private final ObjectMapper objectMapper = new ObjectMapper();

  public TelemetryValidationService(PlantRepository plants, MachineRepository machines) {
    this.plants = plants;
    this.machines = machines;
  }

  public Result validate(String topic, String payload) {
    var parsedTopic = TelemetryTopic.parse(topic);
    if (parsedTopic.isEmpty()) {
      return new Result.Rejected("malformed_topic", null);
    }
    var plant = plants.findByCodeIgnoreCase(parsedTopic.get().plantCode());
    if (plant.isEmpty()) {
      return new Result.Rejected("unknown_plant", null);
    }
    var machine = machines.findByPlantIdAndCodeIgnoreCase(plant.get().getId(), parsedTopic.get().machineCode());
    if (machine.isEmpty()) {
      return new Result.Rejected("unknown_machine", null);
    }
    return switch (TelemetryPayload.parse(payload, objectMapper)) {
      case TelemetryPayload.ParseResult.Rejected rejected -> new Result.Rejected(rejected.reason(), rejected.field());
      case TelemetryPayload.ParseResult.Accepted accepted -> new Result.Accepted(machine.get(), accepted.payload());
    };
  }
}