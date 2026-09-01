package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.org.application.MachineAreaService.CreateMachineAreaCommand;
import com.syncro.org.application.MachineAreaService.DuplicateMachineAreaCodeException;
import com.syncro.org.application.MachineAreaService.DuplicateMachineAreaNameException;
import com.syncro.org.application.MachineAreaService.MachineAreaHasMachinesException;
import com.syncro.org.application.MachineAreaService.MachineAreaMutationForbiddenException;
import com.syncro.org.application.MachineAreaService.UpdateMachineAreaCommand;
import com.syncro.org.infrastructure.db.MachineAreaEntity;
import com.syncro.org.infrastructure.db.MachineAreaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MachineAreaServiceTest {

  @Mock private MachineAreaRepository areas;
  @Mock private MachineRepository machines;
  @Mock private PlantRepository plants;
  @Mock private PlantScopeService plantScopes;
  @Mock private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

  private MachineAreaService service() {
    return new MachineAreaService(areas, machines, plants, plantScopes, auditLog, clock);
  }

  @Test
  void createPersistsAreaWithAudit() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(areas.findByPlantIdAndNameIgnoreCase(plantId, "Production Floor 1")).thenReturn(Optional.empty());
    when(areas.findByPlantIdAndCodeIgnoreCase(plantId, "FLOOR-1")).thenReturn(Optional.empty());
    when(areas.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var created = service().create(manager(),
        new CreateMachineAreaCommand(plantId, "FLOOR-1", "Production Floor 1", "Main floor"));

    assertThat(created.name()).isEqualTo("Production Floor 1");
    assertThat(created.code()).isEqualTo("FLOOR-1");
    assertThat(created.plantCode()).isEqualTo("GM1");
    assertThat(created.active()).isTrue();
    verify(auditLog).record(any(), any());
  }

  @Test
  void createRejectsDuplicateName() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(areas.findByPlantIdAndNameIgnoreCase(plantId, "Production Floor 1"))
        .thenReturn(Optional.of(area(plantId, "Production Floor 1", "FLOOR-1")));

    assertThatThrownBy(() -> service().create(manager(),
        new CreateMachineAreaCommand(plantId, "FLOOR-2", "Production Floor 1", null)))
        .isInstanceOf(DuplicateMachineAreaNameException.class);
  }

  @Test
  void createRejectsDuplicateCode() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(areas.findByPlantIdAndNameIgnoreCase(plantId, "Other Floor")).thenReturn(Optional.empty());
    when(areas.findByPlantIdAndCodeIgnoreCase(plantId, "FLOOR-1"))
        .thenReturn(Optional.of(area(plantId, "Production Floor 1", "FLOOR-1")));

    assertThatThrownBy(() -> service().create(manager(),
        new CreateMachineAreaCommand(plantId, "FLOOR-1", "Other Floor", null)))
        .isInstanceOf(DuplicateMachineAreaCodeException.class);
  }

  @Test
  void deleteRejectedWhileMachinesReferenceArea() {
    var plantId = UUID.randomUUID();
    var areaId = UUID.randomUUID();
    when(areas.findById(areaId)).thenReturn(Optional.of(area(plantId, "Production Floor 1", "FLOOR-1", areaId)));
    when(machines.countByAreaId(areaId)).thenReturn(2L);

    assertThatThrownBy(() -> service().delete(manager(), areaId))
        .isInstanceOf(MachineAreaHasMachinesException.class);
    verify(areas, never()).saveAndFlush(any());
  }

  @Test
  void deleteSoftInactivatesWhenNoMachinesReference() {
    var plantId = UUID.randomUUID();
    var areaId = UUID.randomUUID();
    var existing = area(plantId, "Production Floor 1", "FLOOR-1", areaId);
    when(areas.findById(areaId)).thenReturn(Optional.of(existing));
    when(machines.countByAreaId(areaId)).thenReturn(0L);
    when(areas.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    service().delete(manager(), areaId);

    assertThat(existing.isActive()).isFalse();
    verify(auditLog).record(any(), any());
  }

  @Test
  void mutationRejectedForNonManagerRole() {
    assertThatThrownBy(() -> service().create(technician(),
        new CreateMachineAreaCommand(UUID.randomUUID(), null, "Floor", null)))
        .isInstanceOf(MachineAreaMutationForbiddenException.class);
  }

  @Test
  void updateRenamesAndKeepsPlant() {
    var plantId = UUID.randomUUID();
    var areaId = UUID.randomUUID();
    var existing = area(plantId, "Production Floor 1", "FLOOR-1", areaId);
    when(areas.findById(areaId)).thenReturn(Optional.of(existing));
    when(areas.findByPlantIdAndNameIgnoreCase(plantId, "Production Floor 2")).thenReturn(Optional.empty());
    when(areas.findByPlantIdAndCodeIgnoreCase(plantId, "FLOOR-1"))
        .thenReturn(Optional.of(existing));
    when(areas.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var updated = service().update(manager(), areaId,
        new UpdateMachineAreaCommand("FLOOR-1", "Production Floor 2", null, true));

    assertThat(updated.name()).isEqualTo("Production Floor 2");
    assertThat(updated.plantId()).isEqualTo(plantId);
  }

  private MachineAreaEntity area(UUID plantId, String name, String code) {
    return area(plantId, name, code, UUID.randomUUID());
  }

  private MachineAreaEntity area(UUID plantId, String name, String code, UUID areaId) {
    return new MachineAreaEntity(areaId, plantId, code, name, null, true,
        Instant.now(clock), Instant.now(clock));
  }

  private AuthenticatedUser manager() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "manager", ApplicationRole.MANAGER_MAINTENANCE);
  }

  private AuthenticatedUser technician() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "tech", ApplicationRole.TECHNICIAN);
  }
}
