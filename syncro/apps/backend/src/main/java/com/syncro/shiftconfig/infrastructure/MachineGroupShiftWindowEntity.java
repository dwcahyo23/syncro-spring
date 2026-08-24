package com.syncro.shiftconfig.infrastructure;

import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "machine_group_shift_windows")
public class MachineGroupShiftWindowEntity {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "machine_group_id", nullable = false)
  private MachineGroupEntity machineGroup;

  @JdbcTypeCode(SqlTypes.SMALLINT)
  @Column(name = "shift_number", nullable = false)
  private int shiftNumber;

  @Column(name = "start_time", nullable = false)
  private LocalTime startTime;

  @Column(name = "end_time", nullable = false)
  private LocalTime endTime;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected MachineGroupShiftWindowEntity() {
  }

  public MachineGroupShiftWindowEntity(UUID id, MachineGroupEntity machineGroup, int shiftNumber,
      LocalTime startTime, LocalTime endTime, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineGroup = machineGroup;
    this.shiftNumber = shiftNumber;
    this.startTime = startTime;
    this.endTime = endTime;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public MachineGroupEntity getMachineGroup() {
    return machineGroup;
  }

  public int getShiftNumber() {
    return shiftNumber;
  }

  public LocalTime getStartTime() {
    return startTime;
  }

  public LocalTime getEndTime() {
    return endTime;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}