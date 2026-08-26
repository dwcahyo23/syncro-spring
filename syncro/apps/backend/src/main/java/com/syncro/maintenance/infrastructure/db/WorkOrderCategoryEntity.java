package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Global work-order category master data (story 10-1). Code is the stable external
 * identifier (unique, normalized uppercase); the UUID id exists for audit correlation.
 */
@Entity
@Table(name = "work_order_categories")
public class WorkOrderCategoryEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 16, unique = true)
  private String code;

  @Column(nullable = false, length = 100)
  private String label;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "target_response_minutes")
  private Integer targetResponseMinutes;

  protected WorkOrderCategoryEntity() {
  }

  public WorkOrderCategoryEntity(UUID id, String code, String label, UUID createdBy,
      Instant createdAt, Instant updatedAt) {
    this(id, code, label, createdBy, createdAt, updatedAt, null);
  }

  public WorkOrderCategoryEntity(UUID id, String code, String label, UUID createdBy,
      Instant createdAt, Instant updatedAt, Integer targetResponseMinutes) {
    this.id = id;
    this.code = code;
    this.label = label;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
    this.targetResponseMinutes = targetResponseMinutes;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getLabel() {
    return label;
  }

  public UUID getCreatedBy() {
    return createdBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public Integer getTargetResponseMinutes() {
    return targetResponseMinutes;
  }

  public void update(String code, String label, Instant updatedAt) {
    this.code = code;
    this.label = label;
    this.updatedAt = updatedAt;
  }

  public void update(String code, String label, Integer targetResponseMinutes, Instant updatedAt) {
    this.code = code;
    this.label = label;
    this.targetResponseMinutes = targetResponseMinutes;
    this.updatedAt = updatedAt;
  }
}
