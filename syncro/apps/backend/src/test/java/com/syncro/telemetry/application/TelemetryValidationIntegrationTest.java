package com.syncro.telemetry.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "server.port=0",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=factory/+/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
@Transactional
class TelemetryValidationIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private TelemetryValidationService validationService;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Test
  @DisplayName("3.2-VAL-001 P0 valid topic and payload accepted for registered machine")
  void acceptsValidTopicAndPayload() {
    seedPlantAndMachine();

    var result = validationService.validate("factory/GM1/BF-08410/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    var accepted = (TelemetryValidationService.Result.Accepted) result;
    assertThat(accepted.machine().getCode()).isEqualTo("BF-08410");
    assertThat(accepted.payload()).isEqualTo(new TelemetryPayload(true, 12.5, 100));
  }

  @Test
  @DisplayName("3.2-VAL-002 P0 unknown machine rejected")
  void rejectsUnknownMachine() {
    seedPlantAndMachine();

    var result = validationService.validate("factory/GM1/UNKNOWN/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_machine");
  }

  @Test
  @DisplayName("3.2-VAL-003 P0 unknown plant rejected")
  void rejectsUnknownPlant() {
    seedPlantAndMachine();

    var result = validationService.validate("factory/XX1/BF-08410/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    assertThat(((TelemetryValidationService.Result.Rejected) result).reason()).isEqualTo("unknown_plant");
  }

  @Test
  @DisplayName("3.2-VAL-004 P1 case-insensitive plant and machine codes resolve")
  void acceptsCaseInsensitiveCodes() {
    seedPlantAndMachine();

    var result = validationService.validate("factory/gm1/bf-08410/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    assertThat(((TelemetryValidationService.Result.Accepted) result).machine().getCode()).isEqualTo("BF-08410");
  }

  @Test
  @DisplayName("3.3-VAL-001 P0 inactive machine telemetry rejected")
  void rejectsInactiveMachine() {
    seedPlantAndMachine(MachineStatus.INACTIVE);

    var result = validationService.validate("factory/GM1/BF-08410/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(result).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var rejected = (TelemetryValidationService.Result.Rejected) result;
    assertThat(rejected.reason()).isEqualTo("inactive_machine");
    assertThat(rejected.field()).isNull();
  }

  @Test
  @DisplayName("3.3-VAL-002 P0 active machine accepted alongside inactive rejected")
  void acceptsActiveMachineWhileInactiveRejected() {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19",
        MachineStatus.INACTIVE, "Juki", LocalDate.parse("2026-05-27"), null, now, now));
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08411", "JBF19",
        MachineStatus.ACTIVE, "Juki", LocalDate.parse("2026-05-27"), null, now, now));

    var inactive = validationService.validate("factory/GM1/BF-08410/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(inactive).isInstanceOf(TelemetryValidationService.Result.Rejected.class);
    var inactiveRejected = (TelemetryValidationService.Result.Rejected) inactive;
    assertThat(inactiveRejected.reason()).isEqualTo("inactive_machine");
    assertThat(inactiveRejected.field()).isNull();

    var active = validationService.validate("factory/GM1/BF-08411/telemetry",
        "{\"running\":true,\"runtimeHours\":12.5,\"counting\":100}");

    assertThat(active).isInstanceOf(TelemetryValidationService.Result.Accepted.class);
    assertThat(((TelemetryValidationService.Result.Accepted) active).machine().getCode()).isEqualTo("BF-08411");
  }

  private void seedPlantAndMachine() {
    seedPlantAndMachine(MachineStatus.ACTIVE);
  }

  private void seedPlantAndMachine(MachineStatus status) {
    var now = Instant.parse("2026-05-27T00:00:00Z");
    var plant = plants.saveAndFlush(new PlantEntity(UUID.randomUUID(), "GM1", "Plant GM1", now, now));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(UUID.randomUUID(), plant, "Forming", now, now));
    machines.saveAndFlush(new MachineEntity(UUID.randomUUID(), plant, group, "BF-08410", "JBF19",
        status, "Juki", LocalDate.parse("2026-05-27"), null, now, now));
  }
}
