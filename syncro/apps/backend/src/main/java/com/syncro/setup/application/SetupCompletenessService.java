package com.syncro.setup.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.setup.api.SetupCompletenessDtos.ScopeInfo;
import com.syncro.setup.api.SetupCompletenessDtos.SetupCompletenessResponse;
import com.syncro.setup.api.SetupCompletenessDtos.Step;
import com.syncro.sparepart.infrastructure.MachineSparepartInstallationRepository;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SetupCompletenessService {
  private static final String COMPLETE = "COMPLETE";
  private static final String INCOMPLETE = "INCOMPLETE";
  private static final String BLOCKED = "BLOCKED";

  private final PlantRepository plants;
  private final MachineGroupRepository machineGroups;
  private final MachineRepository machines;
  private final SparepartRepository spareparts;
  private final MachineSparepartInstallationRepository installations;
  private final MachineResponsibilityRepository responsibilities;
  private final PlantScopeService plantScopes;

  public SetupCompletenessService(PlantRepository plants, MachineGroupRepository machineGroups,
      MachineRepository machines, SparepartRepository spareparts,
      MachineSparepartInstallationRepository installations,
      MachineResponsibilityRepository responsibilities, PlantScopeService plantScopes) {
    this.plants = plants;
    this.machineGroups = machineGroups;
    this.machines = machines;
    this.spareparts = spareparts;
    this.installations = installations;
    this.responsibilities = responsibilities;
    this.plantScopes = plantScopes;
  }

  @Transactional(readOnly = true)
  public SetupCompletenessResponse get(AuthenticatedUser user) {
    var scope = plantScopes.effectiveScope(user);
    var plantIds = resolvePlantIds(user, scope);

    var steps = new ArrayList<Step>();
    steps.add(plantStep(scope, plantIds));
    steps.add(machineGroupStep(scope, plantIds));
    steps.add(machineStep(scope, plantIds));
    steps.add(sparepartStep(scope, plantIds));
    steps.add(installationStep(scope, plantIds));
    steps.add(responsibilityStep(scope, plantIds));

    var allComplete = steps.stream().allMatch(step -> COMPLETE.equals(step.status()));
    var machineCount = scopeIsEmpty(scope) ? 0 : machines.countByPlantIdIn(plantIds);
    var machinesEligibleCount = scopeIsEmpty(scope) ? 0 : machines.countEligibleByPlantIdIn(plantIds);

    return new SetupCompletenessResponse(
        new ScopeInfo(scope.mode(), plantIds, scope.emptyReason()),
        allComplete ? COMPLETE : INCOMPLETE,
        machineCount,
        machinesEligibleCount,
        List.copyOf(steps));
  }

  private List<UUID> resolvePlantIds(AuthenticatedUser user, com.syncro.auth.api.AuthDtos.PlantScopeResponse scope) {
    if ("EMPTY".equals(scope.mode())) {
      return List.of();
    }
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return plants.findAll().stream().map(com.syncro.auth.infrastructure.PlantEntity::getId).toList();
    }
    return scope.availablePlants().stream()
        .map(plant -> UUID.fromString(plant.id()))
        .toList();
  }

  private Step plantStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return new Step("PLANT", "Plant", BLOCKED, "Assign a plant to your scope", "/master-data/plants");
    }
    return completeOrIncomplete(plants.countByIdIn(plantIds) > 0, "PLANT", "Plant", "Create a plant", "/master-data/plants");
  }

  private Step machineGroupStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return blocked("MACHINE_GROUP", "Machine Group", "Create a plant first", "/master-data/plants");
    }
    if (plants.countByIdIn(plantIds) == 0) {
      return blocked("MACHINE_GROUP", "Machine Group", "Create a plant first", "/master-data/plants");
    }
    return completeOrIncomplete(machineGroups.countByPlantIdIn(plantIds) > 0, "MACHINE_GROUP", "Machine Group",
        "Create a machine group", "/master-data/machine-groups");
  }

  private Step machineStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return blocked("MACHINE", "Machine", "Create a plant first", "/master-data/plants");
    }
    if (machineGroups.countByPlantIdIn(plantIds) == 0) {
      return blocked("MACHINE", "Machine", "Create a machine group first", "/master-data/machine-groups");
    }
    return completeOrIncomplete(machines.countByPlantIdIn(plantIds) > 0, "MACHINE", "Machine", "Create a machine",
        "/master-data/machines");
  }

  private Step sparepartStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return blocked("SPAREPART", "Sparepart", "Create a plant first", "/master-data/plants");
    }
    if (machines.countByPlantIdIn(plantIds) == 0) {
      return blocked("SPAREPART", "Sparepart", "Create a machine first", "/master-data/machines");
    }
    return completeOrIncomplete(spareparts.countByMachinePlantIdIn(plantIds) > 0, "SPAREPART", "Sparepart",
        "Create a sparepart", "/master-data/spareparts");
  }

  private Step installationStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return blocked("INSTALLATION", "Installation", "Create a plant first", "/master-data/plants");
    }
    if (machines.countByPlantIdIn(plantIds) == 0) {
      return blocked("INSTALLATION", "Installation", "Create a machine first", "/master-data/machines");
    }
    if (spareparts.countByMachinePlantIdIn(plantIds) == 0) {
      return blocked("INSTALLATION", "Installation", "Create a sparepart first", "/master-data/spareparts");
    }
    return completeOrIncomplete(installations.countByMachinePlantIdIn(plantIds) > 0, "INSTALLATION", "Installation",
        "Install a sparepart on a machine", "/master-data/installations");
  }

  private Step responsibilityStep(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope, List<UUID> plantIds) {
    if (scopeIsEmpty(scope)) {
      return blocked("RESPONSIBILITY", "Responsibility", "Create a plant first", "/master-data/plants");
    }
    if (machines.countByPlantIdIn(plantIds) == 0) {
      return blocked("RESPONSIBILITY", "Responsibility", "Create a machine first", "/master-data/machines");
    }
    return completeOrIncomplete(responsibilities.countByMachinePlantIdIn(plantIds) > 0, "RESPONSIBILITY",
        "Responsibility", "Assign a user to a machine", "/master-data/responsibilities");
  }

  private boolean scopeIsEmpty(com.syncro.auth.api.AuthDtos.PlantScopeResponse scope) {
    return "EMPTY".equals(scope.mode());
  }

  private Step completeOrIncomplete(boolean complete, String key, String label, String nextAction, String href) {
    return new Step(key, label, complete ? COMPLETE : INCOMPLETE, complete ? null : nextAction, complete ? null : href);
  }

  private Step blocked(String key, String label, String nextAction, String href) {
    return new Step(key, label, BLOCKED, nextAction, href);
  }
}
