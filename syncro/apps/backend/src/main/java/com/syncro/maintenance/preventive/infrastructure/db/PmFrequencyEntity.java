package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code pm_frequencies} row (blueprint F1, story 15-2). Preventive
 * frequency master (e.g. HARIAN/MINGGUAN/BULANAN); {@code code} is the unique stable
 * identifier copied onto {@code pm_work_orders.frequency_code}.
 */
@Entity
@Table(name = "pm_frequencies")
public class PmFrequencyEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 50)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmFrequencyEntity() {
  }

  public PmFrequencyEntity(UUID id, String code, String name, String description, int sortOrder,
      boolean active, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.description = description;
    this.sortOrder = sortOrder;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
