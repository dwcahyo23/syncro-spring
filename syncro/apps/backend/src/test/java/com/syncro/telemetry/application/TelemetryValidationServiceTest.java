package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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
  private TelemetryLookupCache lookupCache;

  private TelemetryValidationService service;

  @BeforeEach
  void setUp() {
    service = new TelemetryValidationService(lookupCache);
  }

  @Test
  void rejectsMalformedTopic() {
    var result = service.validate("factory/only-one", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("malformed_topic");
  }

  @Test
  void rejectsUnknownPlant() {
    when(lookupCache.findPlant("XX1")).thenReturn(Optional.empty());

    var result = service.validate("factory/XX1/BF-08410/telemetry", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_plant");
  }

  @Test
  void rejectsUnknownMachine() {
    var plant = plant();
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "UNKNOWN")).thenReturn(Optional.empty());

    var result = service.validate("factory/GM1/UNKNOWN/telemetry", "{}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_machine");
  }

  @Test
  void rejectsInvalidPayload() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\",\"running\":true}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("missing_base_field");
  }

  @Test
  void rejectsInactiveMachine() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.INACTIVE);
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

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
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

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
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.machine()).isSameAs(machine);
    assertThat(accepted.payload())
        .isEqualTo(new TelemetryPayload(true, 12.5, 100, "1.0", "m-1", Instant.parse("2026-08-14T09:30:00Z")));
  }

  @Test
  void acceptsConfiguredScalarOptionalFields() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.ACTIVE, List.of("vibration", "rpm"));
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4,\"rpm\":1200}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.machine()).isSameAs(machine);
    assertThat(accepted.payload().optionalFields()).containsOnlyKeys("vibration", "rpm");
    assertThat(accepted.payload().optionalFields().get("vibration").asDouble()).isEqualTo(2.4);
    assertThat(accepted.payload().optionalFields().get("rpm").asLong()).isEqualTo(1200L);
  }

  @Test
  void rejectsConfiguredNonScalarOptionalField() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.ACTIVE, List.of("vibration"));
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":{\"x\":1}}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("invalid_field_type");
    assertThat(rejected.field()).isEqualTo("vibration");
  }

  @Test
  void toleratesDuplicateNamesInStoredConfig() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.ACTIVE, List.of("vibration", "vibration"));
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"vibration\":2.4}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.payload().optionalFields()).containsOnlyKeys("vibration");
  }

  @Test
  void rejectsNonTextualMachineCodeAsIdentityMismatch() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"machineCode\":12345}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("identity_mismatch");
    assertThat(rejected.field()).isEqualTo("machineCode");
  }

  @Test
  void rejectsNonTextualPlantCodeAsIdentityMismatch() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"plantCode\":true}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("identity_mismatch");
    assertThat(rejected.field()).isEqualTo("plantCode");
  }

  @Test
  void rejectsMachineCodeMismatch() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"machineCode\":\"BF-99999\"}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("identity_mismatch");
    assertThat(rejected.field()).isEqualTo("machineCode");
  }

  @Test
  void rejectsPlantCodeMismatch() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"plantCode\":\"XX1\"}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("identity_mismatch");
    assertThat(rejected.field()).isEqualTo("plantCode");
  }

  @Test
  void acceptsMatchingMachineAndPlantCodes() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,"
            + "\"machineCode\":\"BF-08410\",\"plantCode\":\"GM1\"}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
  }

  @Test
  void acceptsCaseInsensitiveMachineCodeMatch() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,"
            + "\"machineCode\":\"bf-08410\",\"plantCode\":\"gm1\"}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
  }

  @Test
  void acceptsPayloadWithoutIdentityFields() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
  }

  @Test
  void inactiveMachineRejectedBeforeIdentityCheck() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.INACTIVE);
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100,\"machineCode\":\"WRONG\"}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("inactive_machine");
  }

  @Test
  void rejectsNonActiveMachineStatus() {
    var plant = plant();
    var machine = machine(plant, "BF-08410", MachineStatus.INACTIVE);
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("inactive_machine");
    assertThat(rejected.field()).isNull();
  }

  @Test
  void delegatesPlantLookupToCache() {
    when(lookupCache.findPlant("XX1")).thenReturn(Optional.empty());

    service.validate("factory/XX1/BF-08410/telemetry", "{}");
    service.validate("factory/XX1/BF-08410/telemetry", "{}");

    verify(lookupCache, times(2)).findPlant("XX1");
  }

  @Test
  void machineAssociationsAccessibleAfterValidation() {
    var plant = plant();
    var machine = machine(plant, "BF-08410");
    when(lookupCache.findPlant("GM1")).thenReturn(Optional.of(plant));
    when(lookupCache.findMachine(plant.getId(), "BF-08410")).thenReturn(Optional.of(machine));

    var result = service.validate("factory/GM1/BF-08410/telemetry",
        "{\"schemaVersion\":\"1.0\",\"messageId\":\"m-1\",\"timestamp\":\"2026-08-14T09:30:00Z\","
            + "\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.machine().getPlant()).isNotNull();
    assertThat(accepted.machine().getMachineGroup()).isNotNull();
  }

  private PlantEntity plant() {
    return new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", NOW, NOW);
  }

  private MachineEntity machine(PlantEntity plant, String code) {
    return machine(plant, code, MachineStatus.ACTIVE);
  }

  private MachineEntity machine(PlantEntity plant, String code, MachineStatus status) {
    return machine(plant, code, status, List.of());
  }

  private MachineEntity machine(PlantEntity plant, String code, MachineStatus status, List<String> optionalTelemetryFields) {
    var group = new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", NOW, NOW);
    return new MachineEntity(UUID.randomUUID(), plant, group, code, "JBF19", status, "Juki", LocalDate.parse("2026-05-27"),
        null, optionalTelemetryFields, NOW, NOW);
  }
}
