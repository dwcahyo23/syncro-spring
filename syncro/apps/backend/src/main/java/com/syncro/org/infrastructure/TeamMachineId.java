package com.syncro.org.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for a {@link TeamMachineEntity} (team_id, machine_id). */
@Embeddable
public class TeamMachineId implements Serializable {

  @Column(name = "team_id", nullable = false)
  private UUID teamId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  protected TeamMachineId() {
  }

  public TeamMachineId(UUID teamId, UUID machineId) {
    this.teamId = teamId;
    this.machineId = machineId;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TeamMachineId that)) {
      return false;
    }
    return Objects.equals(teamId, that.teamId) && Objects.equals(machineId, that.machineId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(teamId, machineId);
  }
}
