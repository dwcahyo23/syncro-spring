package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TelemetryValidationServiceTest {

  private static final Instant NOW = Instant.parse("2026-05-27T00:00:00Z");

  @Mock
  private PlantRepository plants;

  @Mock
  private MachineRepository machines;

  private TelemetryValidationService service;

  @BeforeEach
  void setUp() {
    service = new TelemetryValidationService(plants, machines);
  }

  @Test
  void rejectsMalformedTopic() {
    var result = service.validate("factory/only-one", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("malformed_topic");
  }

  @Test
  void rejectsUnknownPlant() {
    when(plants.findByCodeIgnoreCase("XX1")).thenReturn(Optional.empty());

    var result = service.validate("factory/XX1/BF-08410/telemetry", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_plant");
  }

  @Test
  void rejectsUnknownMachine() {
    var plant = plant();
    when(plants.findByCodeIgnoreCase("GM1")).thenReturn(Optional.of(plant));
    when(machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "UNKNOWN")).thenReturn(Optional.empty());

    var result = service.validate("factory/GM1/UNKNOWN/telemetry", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_machine");
  }

  @Test
  void rejectsInvalidPayload() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(plants.findByCodeIgnoreCase("GM1")).thenReturn(Optional.of(plant));
    when(machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry", "{\"running\":true}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("missing_base_field");
  }

  @Test
  void rejectsInactiveMachine() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.INACTIVE);
    when(plants.findByCodeIgnoreCase("GM1")).thenReturn(Optional.of(plant));
    when(machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry", "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("inactive_machine");
    assertThat(rejected.field()).isNull();
  }

  @Test
  void rejectsInactiveMachineBeforeParsingPayload() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.INACTIVE);
    when(plants.findByCodeIgnoreCase("GM1")).thenReturn(Optional.of(plant));
    when(machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry", "not json");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("inactive_machine");
    assertThat(rejected.field()).isNull();
  }

  @Test
  void acceptsValidTopicAndPayload() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(plants.findByCodeIgnoreCase("GM1")).thenReturn(Optional.of(plant));
    when(machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry", "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.machine()).isSameAs(machine);
    assertThat(accepted.payload()).isEqualTo(new TelemetryPayload(true, 12.5, 100));
  }

  private PlantEntity plant() {
    return new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", NOW, NOW);
  }

  private MachineEntity machine(PlantEntity plant, String code) {
    return machine(plant, code, MachineStatus.ACTIVE);
  }

  private MachineEntity machine(PlantEntity plant, String code, MachineStatus status) {
    var group = new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", NOW, NOW);
    return new MachineEntity(UUID.randomUUID(), plant, group, code, "JBF19", status, "Juki", LocalDate.parse("2026-05-27"),
        null, NOW, NOW);
  }
}
