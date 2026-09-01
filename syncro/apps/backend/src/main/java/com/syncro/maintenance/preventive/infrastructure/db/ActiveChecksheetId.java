package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for an {@link ActiveChecksheetEntity} (machine_id, frequency_id). */
@Embeddable
public class ActiveChecksheetId implements Serializable {

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "frequency_id", nullable = false)
  private UUID frequencyId;

  protected ActiveChecksheetId() {
  }

  public ActiveChecksheetId(UUID machineId, UUID frequencyId) {
    this.machineId = machineId;
    this.frequencyId = frequencyId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public UUID getFrequencyId() {
    return frequencyId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof ActiveChecksheetId that)) {
      return false;
    }
    return Objects.equals(machineId, that.machineId) && Objects.equals(frequencyId, that.frequencyId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(machineId, frequencyId);
  }
}
