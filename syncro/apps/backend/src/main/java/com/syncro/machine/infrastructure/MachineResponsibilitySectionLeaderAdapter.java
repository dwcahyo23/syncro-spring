package com.syncro.machine.infrastructure;

import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.org.application.SectionLeaderMachineGroupReader;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the org module's {@link SectionLeaderMachineGroupReader}
 * port: a user leads a machine group iff they hold responsibility level LEADER or
 * above on it (AD-2). Demotion/removal immediately removes scope server-side.
 */
@Component
public class MachineResponsibilitySectionLeaderAdapter implements SectionLeaderMachineGroupReader {

  private final MachineResponsibilityRepository responsibilities;

  public MachineResponsibilitySectionLeaderAdapter(MachineResponsibilityRepository responsibilities) {
    this.responsibilities = responsibilities;
  }

  @Override
  public Set<UUID> findMachineGroupIdsWhereUserIsLeader(UUID userId) {
    return responsibilities.findDistinctMachineGroupIdsByUserIdAndLevelIn(
        userId, List.of(ResponsibilityLevel.LEADER, ResponsibilityLevel.SPV, ResponsibilityLevel.MANAGER));
  }
}
