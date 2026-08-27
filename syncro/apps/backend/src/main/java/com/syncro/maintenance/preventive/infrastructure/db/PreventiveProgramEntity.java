package com.syncro.maintenance.preventive.infrastructure.db;

import com.syncro.maintenance.preventive.domain.PreventiveCategory;
import com.syncro.maintenance.preventive.domain.ScheduleType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "preventive_programs")
public class PreventiveProgramEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private PreventiveCategory category;

  @Enumerated(EnumType.STRING)
  @Column(name = "schedule_type", nullable = false, length = 10)
  private ScheduleType scheduleType;

  @Column(name = "day_of_month", nullable = false)
  private short dayOfMonth;

  @Column(name = "month_of_year")
  private Short monthOfYear;

  @Column(nullable = false, length = 200)
  private String title;

  @Column(columnDefinition = "text")
  private String description;

  @Column(nullable = false)
  private boolean active;

  @Column(name = "auto_workorder", nullable = false)
  private boolean autoWorkorder;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PreventiveProgramEntity() {
  }

  public PreventiveProgramEntity(UUID id, UUID machineId, PreventiveCategory category, ScheduleType scheduleType,
      short dayOfMonth, Short monthOfYear, String title, String description, boolean active, boolean autoWorkorder,
      UUID createdBy, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.category = category;
    this.scheduleType = scheduleType;
    this.dayOfMonth = dayOfMonth;
    this.monthOfYear = monthOfYear;
    this.title = title;
    this.description = description;
    this.active = active;
    this.autoWorkorder = autoWorkorder;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() { return id; }
  public UUID getMachineId() { return machineId; }
  public PreventiveCategory getCategory() { return category; }
  public ScheduleType getScheduleType() { return scheduleType; }
  public short getDayOfMonth() { return dayOfMonth; }
  public Short getMonthOfYear() { return monthOfYear; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public boolean isActive() { return active; }
  public boolean isAutoWorkorder() { return autoWorkorder; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }

  public void update(String title, String description, boolean active, boolean autoWorkorder, short dayOfMonth,
      Short monthOfYear, Instant updatedAt) {
    this.title = title;
    this.description = description;
    this.active = active;
    this.autoWorkorder = autoWorkorder;
    this.dayOfMonth = dayOfMonth;
    this.monthOfYear = monthOfYear;
    this.updatedAt = updatedAt;
  }
}