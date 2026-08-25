package com.syncro.masterdata.infrastructure;

import com.syncro.org.application.SectionActiveMachineGroupReader;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Adapter implementing the org module's {@link SectionActiveMachineGroupReader}
 * port: a section has an active machine group when at least one machine group
 * with that sectionId contains at least one ACTIVE machine.
 */
@Component
public class MachineGroupActiveMachineAdapter implements SectionActiveMachineGroupReader {

  private final MachineGroupRepository machineGroups;

  public MachineGroupActiveMachineAdapter(MachineGroupRepository machineGroups) {
    this.machineGroups = machineGroups;
  }

  @Override
  public boolean hasActiveMachineGroup(UUID sectionId) {
    return machineGroups.existsGroupInSectionWithActiveMachine(sectionId);
  }
}