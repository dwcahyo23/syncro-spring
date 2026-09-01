package com.syncro.maintenance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Persisted {@code work_order_quality_rating_technicians} row (blueprint C5, story
 * 15-2). Multi-technician WO rating membership: which technician participated in the
 * rated workorder, optionally via the originating work assignment; unique
 * {@code (quality_rating_id, technician_id)} via V1 constraint.
 */
@Entity
@Table(name = "work_order_quality_rating_technicians")
public class WorkOrderQualityRatingTechnicianEntity {

  @Id
  private UUID id;

  @Column(name = "quality_rating_id", nullable = false)
  private UUID qualityRatingId;

  @Column(name = "work_assignment_id")
  private UUID workAssignmentId;

  @Column(name = "technician_id", nullable = false)
  private UUID technicianId;

  protected WorkOrderQualityRatingTechnicianEntity() {
  }

  public WorkOrderQualityRatingTechnicianEntity(UUID id, UUID qualityRatingId,
      UUID workAssignmentId, UUID technicianId) {
    this.id = id;
    this.qualityRatingId = qualityRatingId;
    this.workAssignmentId = workAssignmentId;
    this.technicianId = technicianId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getQualityRatingId() {
    return qualityRatingId;
  }

  public UUID getWorkAssignmentId() {
    return workAssignmentId;
  }

  public UUID getTechnicianId() {
    return technicianId;
  }
}
