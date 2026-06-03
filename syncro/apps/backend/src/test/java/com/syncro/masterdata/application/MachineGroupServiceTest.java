package com.syncro.masterdata.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.masterdata.application.MachineGroupService.CreateMachineGroupCommand;
import com.syncro.masterdata.application.MachineGroupService.DuplicateMachineGroupNameException;
import com.syncro.masterdata.application.MachineGroupService.MachineGroupDataIntegrityException;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class MachineGroupServiceTest {
  @Mock
  private MachineGroupRepository machineGroups;

  @Mock
  private PlantRepository plants;

  @Mock
  private PlantScopeService plantScopes;

  private final Clock clock = Clock.fixed(Instant.parse("2026-05-27T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void createMapsCaseInsensitiveUniqueIndexViolationToDuplicateName() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(machineGroups.existsByPlantIdAndNameIgnoreCase(plantId, "Forming")).thenReturn(false);
    when(machineGroups.saveAndFlush(any())).thenThrow(uniqueViolation("uq_machine_groups_plant_id_lower_name"));
    var machineGroupService = new MachineGroupService(machineGroups, plants, plantScopes, clock);

    assertThatThrownBy(() -> machineGroupService.create(
        user(ApplicationRole.SUPER_ADMIN),
        new CreateMachineGroupCommand(plantId, "Forming")))
        .isInstanceOf(DuplicateMachineGroupNameException.class);
  }

  @Test
  void createMapsOtherIntegrityViolationToConflict() {
    var plantId = UUID.randomUUID();
    var plant = new PlantEntity(plantId, "GM1", "Plant GM1", Instant.now(clock), Instant.now(clock));
    when(plants.findById(plantId)).thenReturn(Optional.of(plant));
    when(machineGroups.existsByPlantIdAndNameIgnoreCase(plantId, "Forming")).thenReturn(false);
    when(machineGroups.saveAndFlush(any())).thenThrow(uniqueViolation("other_constraint"));
    var machineGroupService = new MachineGroupService(machineGroups, plants, plantScopes, clock);

    assertThatThrownBy(() -> machineGroupService.create(
        user(ApplicationRole.SUPER_ADMIN),
        new CreateMachineGroupCommand(plantId, "Forming")))
        .isInstanceOf(MachineGroupDataIntegrityException.class);
  }

  private static DataIntegrityViolationException uniqueViolation(String constraintName) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraintName + "\""));
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
