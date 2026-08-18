package com.syncro.telemetry.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
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
    if (machine.get().getStatus() == MachineStatus.INACTIVE) {
      return new Result.Rejected("inactive_machine", null);
    }
    return switch (TelemetryPayload.parse(payload, objectMapper, configuredOptionalFields(machine.get()))) {
      case TelemetryPayload.ParseResult.Rejected rejected -> new Result.Rejected(rejected.reason(), rejected.field());
      case TelemetryPayload.ParseResult.Accepted accepted -> {
        var identityRejection = checkIdentity(payload, parsedTopic.get());
        if (identityRejection.isPresent()) {
          yield identityRejection.get();
        }
        yield new Result.Accepted(machine.get(), accepted.payload());
      }
    };
  }

  private Optional<Result.Rejected> checkIdentity(String rawPayload, TelemetryTopic topic) {
    try {
      JsonNode root = objectMapper.readTree(rawPayload);
      JsonNode mc = root.get("machineCode");
      if (mc != null && !mc.isNull()) {
        if (!mc.isTextual() || !topic.machineCode().equalsIgnoreCase(mc.asText())) {
          return Optional.of(new Result.Rejected("identity_mismatch", "machineCode"));
        }
      }
      JsonNode pc = root.get("plantCode");
      if (pc != null && !pc.isNull()) {
        if (!pc.isTextual() || !topic.plantCode().equalsIgnoreCase(pc.asText())) {
          return Optional.of(new Result.Rejected("identity_mismatch", "plantCode"));
        }
      }
    } catch (Exception ignored) {
      // payload already confirmed parseable by TelemetryPayload.parse; silently pass
    }
    return Optional.empty();
  }

  private static Set<String> configuredOptionalFields(MachineEntity machine) {
    var configured = machine.getOptionalTelemetryFields();
    return configured == null ? Set.of() : new HashSet<>(configured);
  }
}
