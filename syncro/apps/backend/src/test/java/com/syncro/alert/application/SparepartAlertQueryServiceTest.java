package com.syncro.alert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.syncro.alert.application.SparepartAlertQueryService.AlertNotFoundException;
import com.syncro.alert.domain.SparepartAlertStatus;
import com.syncro.alert.infrastructure.SparepartAlertEntity;
import com.syncro.alert.infrastructure.SparepartAlertRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationEntity;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class SparepartAlertQueryServiceTest {

  @Mock private SparepartAlertRepository alertRepository;
  @Mock private AuthUserPlantAssignmentRepository assignments;
  @Mock private PlantScopeService plantScopes;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-19T00:00:00Z"), ZoneOffset.UTC);

  private SparepartAlertQueryService service() {
    return new SparepartAlertQueryService(alertRepository, assignments, plantScopes);
  }

  // --- helpers ---

  private AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin", ApplicationRole.SUPER_ADMIN);
  }

  private AuthenticatedUser manageUser(UUID userId) {
    return new AuthenticatedUser(userId.toString(), "manager", ApplicationRole.MANAGE);
  }

  private PlantEntity plant(UUID plantId) {
    return new PlantEntity(plantId, "P1", "Plant 1", Instant.now(clock), Instant.now(clock));
  }

  private MachineGroupEntity machineGroup(UUID groupId, PlantEntity plant) {
    return new MachineGroupEntity(groupId, plant, "Group 1", Instant.now(clock), Instant.now(clock));
  }

  private MachineEntity machine(UUID machineId, PlantEntity plant, MachineGroupEntity group) {
    return new MachineEntity(machineId, plant, group, "M1", "Machine 1",
        com.syncro.machine.domain.MachineStatus.ACTIVE, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
  }

  private SparepartEntity sparepart(UUID sparepartId) {
    return new SparepartEntity(sparepartId, "SP-001", "Sparepart 1", null, null, null, null, null,
        Instant.now(clock), Instant.now(clock));
  }

  private MachineSparepartInstallationEntity installation(UUID installationId,
      MachineEntity machine, SparepartEntity sparepart) {
    return new MachineSparepartInstallationEntity(
        installationId, machine, sparepart, "func", 1000L, 0L, 90,
        Instant.now(clock), Instant.now(clock), Instant.now(clock));
  }

  private SparepartAlertEntity alert(UUID alertId, MachineSparepartInstallationEntity installation) {
    var alert = new SparepartAlertEntity(
        alertId,
        installation.getMachine().getId(),
        installation.getId(),
        90,
        950L,
        950L,
        new BigDecimal("95.00"),
        "trace-001",
        SparepartAlertStatus.OPEN,
        null,
        Instant.now(clock),
        Instant.now(clock));
    // inject installation via reflection to simulate JPA @ManyToOne fetch
    try {
      var field = SparepartAlertEntity.class.getDeclaredField("installation");
      field.setAccessible(true);
      field.set(alert, installation);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return alert;
  }

  // --- list tests ---

  @Test
  void list_superAdmin_callsUnscoped() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var installationId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var sparepartId = UUID.randomUUID();

    var p = plant(plantId);
    var g = machineGroup(groupId, p);
    var m = machine(machineId, p, g);
    var sp = sparepart(sparepartId);
    var inst = installation(installationId, m, sp);
    var a = alert(alertId, inst);

    when(alertRepository.findAllUnscoped(isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(a)));

    var result = service().list(superAdmin(), null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).id()).isEqualTo(alertId);
    assertThat(result.items().get(0).machineCode()).isEqualTo("M1");
    assertThat(result.items().get(0).plantCode()).isEqualTo("P1");
    assertThat(result.items().get(0).sparepartCode()).isEqualTo("SP-001");
    assertThat(result.items().get(0).status()).isEqualTo(SparepartAlertStatus.OPEN);
  }

  @Test
  void list_managedUser_callsScoped() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var installationId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var sparepartId = UUID.randomUUID();

    var p = plant(plantId);
    var g = machineGroup(groupId, p);
    var m = machine(machineId, p, g);
    var sp = sparepart(sparepartId);
    var inst = installation(installationId, m, sp);
    var a = alert(alertId, inst);

    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, Instant.now(clock))));
    when(alertRepository.findAllScoped(eq(List.of(plantId)), isNull(), isNull(), isNull(), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(a)));

    var result = service().list(manageUser(userId), null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).id()).isEqualTo(alertId);
  }

  @Test
  void list_managedUser_emptyPlantScope_returnsEmptyList() {
    var userId = UUID.randomUUID();
    when(assignments.findByAuthUserId(userId)).thenReturn(List.of());

    var result = service().list(manageUser(userId), null, null, null, 0, 50, "createdAt,desc");

    assertThat(result.items()).isEmpty();
    assertThat(result.totalElements()).isZero();
  }

  @Test
  void list_filterByStatus_passesStatusToRepository() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var installationId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var sparepartId = UUID.randomUUID();

    var p = plant(plantId);
    var g = machineGroup(groupId, p);
    var m = machine(machineId, p, g);
    var sp = sparepart(sparepartId);
    var inst = installation(installationId, m, sp);
    var a = alert(alertId, inst);

    when(alertRepository.findAllUnscoped(isNull(), isNull(), eq(SparepartAlertStatus.OPEN), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(a)));

    var result = service().list(superAdmin(), null, null, SparepartAlertStatus.OPEN, 0, 50, "createdAt,desc");

    assertThat(result.items()).hasSize(1);
    assertThat(result.items().get(0).status()).isEqualTo(SparepartAlertStatus.OPEN);
  }

  // --- get tests ---

  @Test
  void get_superAdmin_returnsEnrichedView() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var installationId = UUID.randomUUID();
    var alertId = UUID.randomUUID();
    var sparepartId = UUID.randomUUID();

    var p = plant(plantId);
    var g = machineGroup(groupId, p);
    var m = machine(machineId, p, g);
    var sp = sparepart(sparepartId);
    var inst = installation(installationId, m, sp);
    var a = alert(alertId, inst);

    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.of(a));

    var view = service().get(superAdmin(), alertId);

    assertThat(view.id()).isEqualTo(alertId);
    assertThat(view.machineId()).isEqualTo(machineId);
    assertThat(view.plantId()).isEqualTo(plantId);
    assertThat(view.installationId()).isEqualTo(installationId);
    assertThat(view.sparepartId()).isEqualTo(sparepartId);
    assertThat(view.expectedProductionCount()).isEqualTo(1000L);
    assertThat(view.baselineCounter()).isZero();
    assertThat(view.consumedPercentageSnapshot()).isEqualByComparingTo("95.00");
  }

  @Test
  void get_alertNotFound_throwsAlertNotFoundException() {
    var alertId = UUID.randomUUID();
    when(alertRepository.findByIdWithDetails(alertId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().get(superAdmin(), alertId))
        .isInstanceOf(AlertNotFoundException.class);
  }

  @Test
  void get_managedUser_alertNotInScope_throwsAlertNotFoundException() {
    var userId = UUID.randomUUID();
    var plantId = UUID.randomUUID();
    var alertId = UUID.randomUUID();

    when(assignments.findByAuthUserId(userId))
        .thenReturn(List.of(new AuthUserPlantAssignmentEntity(userId, plantId, Instant.now(clock))));
    when(alertRepository.findByIdWithDetailsScopedToPlants(eq(alertId), eq(List.of(plantId))))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> service().get(manageUser(userId), alertId))
        .isInstanceOf(AlertNotFoundException.class);
  }
}
