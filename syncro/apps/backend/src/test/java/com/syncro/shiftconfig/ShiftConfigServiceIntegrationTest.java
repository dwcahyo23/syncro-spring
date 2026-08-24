package com.syncro.shiftconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityEntity;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.shiftconfig.application.ShiftConfigService;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineGroupNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MachineNotFoundException;
import com.syncro.shiftconfig.application.ShiftConfigService.MutationForbiddenException;
import com.syncro.shiftconfig.application.ShiftConfigService.ShiftWindowCommand;
import com.syncro.shiftconfig.application.ShiftConfigService.ValidationException;
import com.syncro.shiftconfig.infrastructure.MachineGroupShiftWindowRepository;
import com.syncro.shiftconfig.infrastructure.MachineShiftWindowRepository;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "GARAGE_HOST=localhost",
    "GARAGE_S3_PORT=3900",
    "GARAGE_ACCESS_KEY=test",
    "GARAGE_SECRET_KEY=test",
    "GARAGE_BUCKET=test",
    "GARAGE_REGION=garage",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
@Testcontainers
@Transactional
class ShiftConfigServiceIntegrationTest {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private ShiftConfigService shiftConfigs;

  @Autowired
  private MachineGroupShiftWindowRepository groupWindows;

  @Autowired
  private MachineShiftWindowRepository machineWindows;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private com.syncro.auth.infrastructure.PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private AuditLogRepository auditLogs;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("8.5-SVC-001 P0 SET_GROUP: LEADER+ MANAGE stores two windows (incl. cross-midnight) and audits CREATE")
  void setGroupStoresWindowsAndAuditsCreate() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "sg-create@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);

    var view = shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0)),
        new ShiftWindowCommand(LocalTime.of(23, 0), LocalTime.of(6, 0))));

    assertThat(view.shifts()).hasSize(2);
    assertThat(view.shifts().get(0).shiftNumber()).isEqualTo(1);
    assertThat(view.shifts().get(0).startTime()).isEqualTo(LocalTime.of(7, 0));
    assertThat(view.shifts().get(0).endTime()).isEqualTo(LocalTime.of(15, 0));
    assertThat(view.shifts().get(1).shiftNumber()).isEqualTo(2);
    assertThat(view.shifts().get(1).startTime()).isEqualTo(LocalTime.of(23, 0));
    assertThat(view.shifts().get(1).endTime()).isEqualTo(LocalTime.of(6, 0));

    var rows = groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getShiftNumber()).isEqualTo(1);
    assertThat(rows.get(0).getStartTime()).isEqualTo(LocalTime.of(7, 0));
    assertThat(rows.get(1).getShiftNumber()).isEqualTo(2);
    assertThat(rows.get(1).getStartTime()).isEqualTo(LocalTime.of(23, 0));

    var audit = latestAuditEntryFor(group.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getEntityType()).isEqualTo(AuditEntityType.MACHINE_GROUP);
    assertThat(audit.getEntityLabel()).isEqualTo(group.getName());
    assertThat(audit.getPlantId()).isEqualTo(group.getPlant().getId());
    assertThat(audit.getActorName()).isEqualTo("sg-create@syncro.dev");
    assertThat(audit.getPreviousValue()).isNull();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var newValue = mapper.readTree(audit.getNewValue());
    assertThat(newValue.get("shifts").size()).isEqualTo(2);
    assertThat(newValue.get("shifts").get(1).get("startTime").asText()).isEqualTo("23:00");
    assertThat(newValue.get("shifts").get(1).get("endTime").asText()).isEqualTo("06:00");
  }

  @Test
  @DisplayName("8.5-SVC-002 P0 REPLACE_GROUP: replaces old windows and audits UPDATE with snapshots")
  void replaceGroupWindowsAndAuditsUpdate() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "sg-replace@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0)),
        new ShiftWindowCommand(LocalTime.of(15, 0), LocalTime.of(23, 0)),
        new ShiftWindowCommand(LocalTime.of(23, 0), LocalTime.of(6, 0))));

    var replaced = shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(6, 0), LocalTime.of(14, 0)),
        new ShiftWindowCommand(LocalTime.of(14, 0), LocalTime.of(22, 0))));

    assertThat(replaced.shifts()).hasSize(2);
    var rows = groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId());
    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).getShiftNumber()).isEqualTo(1);
    assertThat(rows.get(0).getStartTime()).isEqualTo(LocalTime.of(6, 0));

    var audit = latestAuditEntryFor(group.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.UPDATE);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var previous = mapper.readTree(audit.getPreviousValue());
    assertThat(previous.get("shifts").size()).isEqualTo(3);
    assertThat(previous.get("shifts").get(0).get("startTime").asText()).isEqualTo("07:00");
    var newValue = mapper.readTree(audit.getNewValue());
    assertThat(newValue.get("shifts").size()).isEqualTo(2);
    assertThat(newValue.get("shifts").get(1).get("startTime").asText()).isEqualTo("14:00");
  }

  @Test
  @DisplayName("8.5-SVC-003 P0 CLEAR_GROUP: empty PUT clears config and audits DELETE")
  void clearGroupConfigAndAuditsDelete() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "sg-clear@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0))));

    var cleared = shiftConfigs.setGroupConfig(user, group.getId(), List.of());

    assertThat(cleared.shifts()).isEmpty();
    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
    var audit = latestAuditEntryFor(group.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.DELETE);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readTree(audit.getPreviousValue()).get("shifts").size()).isEqualTo(1);
    assertThat(audit.getNewValue()).isNull();
  }

  @Test
  @DisplayName("8.5-SVC-004 P0 MACHINE_OVERRIDE_WINS: machine override returns source MACHINE and audits CREATE")
  void machineOverrideWinsAndAuditsCreate() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "mo-create@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0))));

    var view = shiftConfigs.setMachineConfig(user, machine.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(9, 0), LocalTime.of(17, 0))));

    assertThat(view.source()).isEqualTo("MACHINE");
    assertThat(view.inheritedFromGroup()).isFalse();
    assertThat(view.shifts()).hasSize(1);
    assertThat(view.shifts().get(0).startTime()).isEqualTo(LocalTime.of(9, 0));

    var resolved = shiftConfigs.getMachineConfig(user, machine.getId());
    assertThat(resolved.source()).isEqualTo("MACHINE");

    var audit = latestAuditEntryFor(machine.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getEntityType()).isEqualTo(AuditEntityType.MACHINE);
    assertThat(audit.getEntityLabel()).isEqualTo("MCH-1");
    assertThat(audit.getPlantId()).isEqualTo(group.getPlant().getId());
  }

  @Test
  @DisplayName("8.5-SVC-005 P0 MACHINE_FALLBACK: no override returns source MACHINE_GROUP with inheritedFromGroup true")
  void machineFallbackReturnsGroupConfig() {
    var user = persistedUser(ApplicationRole.MANAGE, "mf-fallback@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0)),
        new ShiftWindowCommand(LocalTime.of(23, 0), LocalTime.of(6, 0))));

    var view = shiftConfigs.getMachineConfig(user, machine.getId());

    assertThat(view.source()).isEqualTo("MACHINE_GROUP");
    assertThat(view.inheritedFromGroup()).isTrue();
    assertThat(view.shifts()).hasSize(2);
    assertThat(view.shifts().get(0).startTime()).isEqualTo(LocalTime.of(7, 0));
  }

  @Test
  @DisplayName("8.5-SVC-006 P0 NO_CONFIG: neither group nor override returns source NONE with empty shifts")
  void noConfigReturnsSourceNone() {
    var user = persistedUser(ApplicationRole.MANAGE, "nc-none@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);

    var view = shiftConfigs.getMachineConfig(user, machine.getId());

    assertThat(view.source()).isEqualTo("NONE");
    assertThat(view.inheritedFromGroup()).isFalse();
    assertThat(view.shifts()).isEmpty();
  }

  @Test
  @DisplayName("8.5-SVC-007 P0 CLEAR_OVERRIDE: DELETE clears override, falls back to group, audits DELETE")
  void clearOverrideAndFallback() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "co-clear@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(7, 0), LocalTime.of(15, 0))));
    shiftConfigs.setMachineConfig(user, machine.getId(), List.of(
        new ShiftWindowCommand(LocalTime.of(9, 0), LocalTime.of(17, 0))));

    shiftConfigs.clearMachineConfig(user, machine.getId());

    var audit = latestAuditEntryFor(machine.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.DELETE);
    assertThat(audit.getEntityType()).isEqualTo(AuditEntityType.MACHINE);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readTree(audit.getPreviousValue()).get("shifts").size()).isEqualTo(1);
    assertThat(audit.getNewValue()).isNull();

    var view = shiftConfigs.getMachineConfig(user, machine.getId());
    assertThat(view.source()).isEqualTo("MACHINE_GROUP");
    assertThat(view.inheritedFromGroup()).isTrue();
    assertThat(view.shifts()).hasSize(1);
  }

  @Test
  @DisplayName("8.5-SVC-008 P0 CLEAR_OVERRIDE when absent is idempotent no-op with no audit")
  void clearOverrideWhenAbsentIsNoop() {
    var user = persistedUser(ApplicationRole.MANAGE, "co-noop@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    long countBefore = auditCount(machine.getId());

    shiftConfigs.clearMachineConfig(user, machine.getId());

    assertThat(auditCount(machine.getId())).isEqualTo(countBefore);
    assertThat(machineWindows.findAllByMachineIdOrderByShiftNumber(machine.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.5-SVC-009 P0 FOUR_SHIFTS: validation error with no write")
  void fourShiftsRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "fs-reject@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);

    var exception = catchThrowableOfType(() -> shiftConfigs.setGroupConfig(user, group.getId(),
        List.of(w(7, 15), w(15, 23), w(23, 6), w(6, 14))), ValidationException.class);

    assertThat(exception.getFieldErrors()).containsKey("shifts");
    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.5-SVC-010 P0 ZERO_LENGTH: startTime equals endTime returns validation error")
  void zeroLengthWindowRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "zl-reject@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);

    var exception = catchThrowableOfType(() -> shiftConfigs.setGroupConfig(user, group.getId(),
        List.of(new ShiftWindowCommand(LocalTime.of(8, 0), LocalTime.of(8, 0)))),
        ValidationException.class);

    assertThat(exception.getFieldErrors()).containsKey("shifts");
    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.5-SVC-011 P0 MISSING_TIMES: null startTime or endTime returns validation error")
  void missingTimesRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "mt-reject@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    assignJobScope(user, machine(), ResponsibilityLevel.LEADER);

    var exception = catchThrowableOfType(() -> shiftConfigs.setGroupConfig(user, group.getId(),
        List.of(new ShiftWindowCommand(null, LocalTime.of(15, 0)))),
        ValidationException.class);

    assertThat(exception.getFieldErrors()).containsKey("shifts");
    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
  }

  @Test
  @DisplayName("8.5-SVC-012 P0 BELOW_LEADER: MANAGE without LEADER job scope is denied with no write/audit")
  void belowLeaderDenied() {
    var user = persistedUser(ApplicationRole.MANAGE, "bl-denied@syncro.dev");
    var group = group();
    assign(user, group.getPlant());
    long countBefore = auditCount(group.getId());

    assertThatThrownBy(() -> shiftConfigs.setGroupConfig(user, group.getId(), List.of(w(7, 15))))
        .isInstanceOf(JobScopeForbiddenException.class);
    assertThatThrownBy(() -> shiftConfigs.setMachineConfig(user, UUID.randomUUID(), List.of(w(7, 15))))
        .isInstanceOf(JobScopeForbiddenException.class);
    assertThatThrownBy(() -> shiftConfigs.clearMachineConfig(user, UUID.randomUUID()))
        .isInstanceOf(JobScopeForbiddenException.class);

    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
    assertThat(auditCount(group.getId())).isEqualTo(countBefore);
  }

  @Test
  @DisplayName("8.5-SVC-013 P0 VIEWER is rejected by app-role gate before job scope")
  void viewerRejected() {
    var viewer = persistedUser(ApplicationRole.VIEWER, "viewer-rejected@syncro.dev");
    var group = group();
    var machine = machine();
    assign(viewer, group.getPlant());
    assignJobScope(viewer, machine, ResponsibilityLevel.LEADER);
    long countBefore = auditCount(group.getId());

    assertThatThrownBy(() -> shiftConfigs.setGroupConfig(viewer, group.getId(), List.of(w(7, 15))))
        .isInstanceOf(MutationForbiddenException.class);
    assertThatThrownBy(() -> shiftConfigs.setMachineConfig(viewer, machine.getId(), List.of(w(7, 15))))
        .isInstanceOf(MutationForbiddenException.class);
    assertThatThrownBy(() -> shiftConfigs.clearMachineConfig(viewer, machine.getId()))
        .isInstanceOf(MutationForbiddenException.class);

    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).isEmpty();
    assertThat(auditCount(group.getId())).isEqualTo(countBefore);
  }

  @Test
  @DisplayName("8.5-SVC-014 P0 SUPER_ADMIN bypasses job scope without responsibility rows")
  void superAdminBypassesJobScope() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "sa-bypass@syncro.dev");
    var group = group();

    var view = shiftConfigs.setGroupConfig(admin, group.getId(), List.of(w(7, 15)));

    assertThat(view.shifts()).hasSize(1);
    assertThat(groupWindows.findAllByMachineGroupIdOrderByShiftNumber(group.getId())).hasSize(1);
  }

  @Test
  @DisplayName("8.5-SVC-015 P0 WRONG_PLANT: MANAGE+LEADER on unassigned plant is denied")
  void wrongPlantDenied() {
    var outsider = persistedUser(ApplicationRole.MANAGE, "wp-denied@syncro.dev");
    var plant1 = plant();
    var plant2 = otherPlant();
    var group2 = otherGroup(plant2);
    var machine2 = otherMachine(group2);
    assign(outsider, plant1);
    assignJobScope(outsider, machine2, ResponsibilityLevel.MANAGER);

    assertThatThrownBy(() -> shiftConfigs.setGroupConfig(outsider, group2.getId(), List.of(w(7, 15))))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> shiftConfigs.setMachineConfig(outsider, machine2.getId(), List.of(w(7, 15))))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> shiftConfigs.getMachineConfig(outsider, machine2.getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
    assertThatThrownBy(() -> shiftConfigs.clearMachineConfig(outsider, machine2.getId()))
        .isInstanceOf(PlantAccessDeniedException.class);
  }

  @Test
  @DisplayName("8.5-SVC-016 P0 UNKNOWN_ID: all operations return not-found for nonexistent ids")
  void unknownIdReturnsNotFound() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "unknown-id@syncro.dev");

    assertThatThrownBy(() -> shiftConfigs.getGroupConfig(admin, UUID.randomUUID()))
        .isInstanceOf(MachineGroupNotFoundException.class);
    assertThatThrownBy(() -> shiftConfigs.setGroupConfig(admin, UUID.randomUUID(), List.of(w(7, 15))))
        .isInstanceOf(MachineGroupNotFoundException.class);
    assertThatThrownBy(() -> shiftConfigs.getMachineConfig(admin, UUID.randomUUID()))
        .isInstanceOf(MachineNotFoundException.class);
    assertThatThrownBy(() -> shiftConfigs.setMachineConfig(admin, UUID.randomUUID(), List.of(w(7, 15))))
        .isInstanceOf(MachineNotFoundException.class);
    assertThatThrownBy(() -> shiftConfigs.clearMachineConfig(admin, UUID.randomUUID()))
        .isInstanceOf(MachineNotFoundException.class);
  }

  @Test
  @DisplayName("8.5-SVC-017 P1 plant-scoped user without job scope can still read config")
  void plantScopedReaderReadsWithoutJobScope() {
    var reader = persistedUser(ApplicationRole.MANAGE, "reader-no-scope@syncro.dev");
    var writer = persistedUser(ApplicationRole.SUPER_ADMIN, "writer-sa@syncro.dev");
    var group = group();
    var machine = machine();
    assign(reader, group.getPlant());
    shiftConfigs.setGroupConfig(writer, group.getId(), List.of(w(7, 15), w(23, 6)));

    var machineView = shiftConfigs.getMachineConfig(reader, machine.getId());
    assertThat(machineView.source()).isEqualTo("MACHINE_GROUP");
    assertThat(machineView.shifts()).hasSize(2);

    var groupView = shiftConfigs.getGroupConfig(reader, group.getId());
    assertThat(groupView.shifts()).hasSize(2);
  }

  @Test
  @DisplayName("8.5-SVC-018 P1 machine PUT with empty list clears override and audits DELETE")
  void machinePutEmptyListClearsOverride() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "mp-clear@syncro.dev");
    var group = group();
    var machine = machine();
    assign(user, group.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    shiftConfigs.setGroupConfig(user, group.getId(), List.of(w(7, 15)));
    shiftConfigs.setMachineConfig(user, machine.getId(), List.of(w(9, 17)));

    var view = shiftConfigs.setMachineConfig(user, machine.getId(), List.of());

    assertThat(view.source()).isEqualTo("MACHINE_GROUP");
    assertThat(view.inheritedFromGroup()).isTrue();
    assertThat(view.shifts()).hasSize(1);
    assertThat(machineWindows.findAllByMachineIdOrderByShiftNumber(machine.getId())).isEmpty();

    var audit = latestAuditEntryFor(machine.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.DELETE);
  }

  private static ShiftWindowCommand w(int startHour, int endHour) {
    return new ShiftWindowCommand(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
  }

  private PlantEntity plant() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plants.findByCodeIgnoreCase("PLANT-1")
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(
            UUID.randomUUID(), "PLANT-1", "Plant 1", now, now)));
  }

  private PlantEntity otherPlant() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return plants.findByCodeIgnoreCase("PLANT-2")
        .orElseGet(() -> plants.saveAndFlush(new PlantEntity(
            UUID.randomUUID(), "PLANT-2", "Plant 2", now, now)));
  }

  private MachineGroupEntity group() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var p = plant();
    return machineGroups.findByPlantIdAndNameIgnoreCase(p.getId(), "Assembly")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(
            UUID.randomUUID(), p, "Assembly", now, now)));
  }

  private MachineGroupEntity otherGroup(PlantEntity p) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return machineGroups.findByPlantIdAndNameIgnoreCase(p.getId(), "Other")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(
            UUID.randomUUID(), p, "Other", now, now)));
  }

  private MachineEntity machine() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var g = group();
    return machines.findByPlantIdAndCodeIgnoreCase(g.getPlant().getId(), "MCH-1")
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), g.getPlant(), g, "MCH-1", "Machine 1", MachineStatus.ACTIVE,
            null, null, null, List.of(), now, now)));
  }

  private MachineEntity otherMachine(MachineGroupEntity g) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    return machines.findByPlantIdAndCodeIgnoreCase(g.getPlant().getId(), "MCH-2")
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), g.getPlant(), g, "MCH-2", "Machine 2", MachineStatus.ACTIVE,
            null, null, null, List.of(), now, now)));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(
        UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-28T00:00:00Z")));
  }

  private void assignJobScope(AuthenticatedUser user, MachineEntity machine, ResponsibilityLevel level) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var userEntity = users.findById(UUID.fromString(user.id())).orElseThrow();
    responsibilities.saveAndFlush(new MachineResponsibilityEntity(
        UUID.randomUUID(), machine, userEntity, level, now, now));
  }

  private AuditLogEntity latestAuditEntryFor(UUID entityId) {
    return auditLogs.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
        .stream()
        .filter(entry -> entityId.equals(entry.getEntityId()))
        .findFirst()
        .orElseThrow();
  }

  private long auditCount(UUID entityId) {
    return auditLogs.findAll().stream()
        .filter(entry -> entityId.equals(entry.getEntityId()))
        .count();
  }
}