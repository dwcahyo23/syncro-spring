package com.syncro.org.infrastructure;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Target-machine link for a cross-plant team. Composite PK (team_id, machine_id);
 * deleting the team cascades, deleting the machine is RESTRICTed. Machines' active
 * status is NOT checked — repairs happen on stopped machines.
 */
@Entity
@Table(name = "team_machines")
public class TeamMachineEntity {

  @EmbeddedId
  private TeamMachineId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("teamId")
  @JoinColumn(name = "team_id", nullable = false)
  private TeamEntity team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("machineId")
  @JoinColumn(name = "machine_id", nullable = false)
  private com.syncro.machine.infrastructure.MachineEntity machine;

  protected TeamMachineEntity() {
  }

  public TeamMachineEntity(TeamEntity team, com.syncro.machine.infrastructure.MachineEntity machine) {
    this.id = new TeamMachineId(team.getId(), machine.getId());
    this.team = team;
    this.machine = machine;
  }

  public TeamMachineId getId() {
    return id;
  }

  public TeamEntity getTeam() {
    return team;
  }

  public com.syncro.machine.infrastructure.MachineEntity getMachine() {
    return machine;
  }
}
