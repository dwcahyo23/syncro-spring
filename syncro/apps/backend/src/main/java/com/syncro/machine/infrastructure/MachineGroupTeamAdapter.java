package com.syncro.machine.infrastructure;

import com.syncro.org.application.TeamMachineGroupReader;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the org module's {@link TeamMachineGroupReader} port: maps
 * a team's target machines to their distinct machine-group ids for the operational
 * scope's {@code activeTeamIds} (AD-13). An empty/absent machine set yields an empty
 * group set — no extra scope.
 */
@Component
public class MachineGroupTeamAdapter implements TeamMachineGroupReader {

  private final MachineRepository machines;

  public MachineGroupTeamAdapter(MachineRepository machines) {
    this.machines = machines;
  }

  @Override
  public Set<UUID> resolveMachineGroupIds(Collection<UUID> machineIds) {
    if (machineIds == null || machineIds.isEmpty()) {
      return Set.of();
    }
    return machines.findDistinctMachineGroupIdsByMachineIdIn(machineIds);
  }
}
