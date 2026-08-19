package com.syncro.telemetry.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "machine_counter_states")
public class MachineCounterStateEntity {

  @Id
  @Column(name = "machine_id")
  private UUID machineId;

  @Column(name = "counting", nullable = false)
  private long counting;

  @CreationTimestamp
  @UpdateTimestamp
  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  public MachineCounterStateEntity() {
  }

  public MachineCounterStateEntity(UUID machineId, long counting) {
    this.machineId = machineId;
    this.counting = counting;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public long getCounting() {
    return counting;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
