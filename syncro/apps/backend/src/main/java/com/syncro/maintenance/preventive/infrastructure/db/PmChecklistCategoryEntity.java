package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code pm_checklist_categories} row (blueprint F4, story 15-2). Groups
 * checklist items inside a checksheet revision; items reference it via the plain
 * {@code pm_checklist_items.category_id} column.
 */
@Entity
@Table(name = "pm_checklist_categories")
public class PmChecklistCategoryEntity {

  @Id
  private UUID id;

  @Column(name = "checksheet_id", nullable = false)
  private UUID checksheetId;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(name = "sort_order", nullable = false)
  private int sortOrder;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected PmChecklistCategoryEntity() {
  }

  public PmChecklistCategoryEntity(UUID id, UUID checksheetId, String name, int sortOrder,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.checksheetId = checksheetId;
    this.name = name;
    this.sortOrder = sortOrder;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getChecksheetId() {
    return checksheetId;
  }

  public String getName() {
    return name;
  }

  public int getSortOrder() {
    return sortOrder;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Edit (story 19-2): rename and/or reorder the category within its checksheet. */
  public void update(String name, int sortOrder, Instant updatedAt) {
    this.name = name;
    this.sortOrder = sortOrder;
    this.updatedAt = updatedAt;
  }
}
