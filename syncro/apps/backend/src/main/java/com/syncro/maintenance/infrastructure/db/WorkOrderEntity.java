package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Schema-only JPA pass-through for the {@code work_orders} table (story 10-1).
 * Cross-aggregate FKs (machine_id, category_id, created_by) are plain UUID columns —
 * this entity is never queried or persisted through JPA; it exists solely so
 * ddl-auto=validate matches the V47 DDL. Workorder CRUD/state machine arrive in 10.2/10.3.
 */
@Entity
@Table(name = "work_orders")
public class WorkOrderEntity {

  @Id
  @Column(length = 50)
  private String id;

  @Column(nullable = false, length = 8)
  private String source;

  @Column(name = "parent_id", length = 50)
  private String parentId;

  @Column(nullable = false, length = 20)
  private String status;

  @Column(name = "category_id")
  private UUID categoryId;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(columnDefinition = "text")
  private String description;

  @Column(name = "sync_version", nullable = false)
  private long syncVersion;

  @Column(name = "created_by")
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected WorkOrderEntity() {
  }
}
