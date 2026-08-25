package com.syncro.audit.infrastructure;

import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {
  @Id
  private UUID id;

  @Column(name = "actor_id", nullable = false)
  private UUID actorId;

  @Column(name = "actor_name", nullable = false, length = 255)
  private String actorName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private AuditAction action;

  @Enumerated(EnumType.STRING)
  @Column(name = "entity_type", nullable = false, length = 32)
  private AuditEntityType entityType;

  @Column(name = "entity_id", nullable = false)
  private UUID entityId;

  @Column(name = "entity_label", nullable = false, length = 255)
  private String entityLabel;

  @Column(name = "plant_id")
  private UUID plantId;

  @Column(name = "previous_value")
  private String previousValue;

  @Column(name = "new_value")
  private String newValue;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "decision_id")
  private UUID decisionId;

  protected AuditLogEntity() {
  }

  public AuditLogEntity(UUID id, UUID actorId, String actorName, AuditAction action, AuditEntityType entityType,
      UUID entityId, String entityLabel, UUID plantId, String previousValue, String newValue, Instant createdAt) {
    this(id, actorId, actorName, action, entityType, entityId, entityLabel, plantId, previousValue, newValue,
        createdAt, null);
  }

  public AuditLogEntity(UUID id, UUID actorId, String actorName, AuditAction action, AuditEntityType entityType,
      UUID entityId, String entityLabel, UUID plantId, String previousValue, String newValue, Instant createdAt,
      UUID decisionId) {
    this.id = id;
    this.actorId = actorId;
    this.actorName = actorName;
    this.action = action;
    this.entityType = entityType;
    this.entityId = entityId;
    this.entityLabel = entityLabel;
    this.plantId = plantId;
    this.previousValue = previousValue;
    this.newValue = newValue;
    this.createdAt = createdAt;
    this.decisionId = decisionId;
  }

  public UUID getId() {
    return id;
  }

  public UUID getActorId() {
    return actorId;
  }

  public String getActorName() {
    return actorName;
  }

  public AuditAction getAction() {
    return action;
  }

  public AuditEntityType getEntityType() {
    return entityType;
  }

  public UUID getEntityId() {
    return entityId;
  }

  public String getEntityLabel() {
    return entityLabel;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public String getPreviousValue() {
    return previousValue;
  }

  public String getNewValue() {
    return newValue;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public UUID getDecisionId() {
    return decisionId;
  }

  public void setDecisionId(UUID decisionId) {
    this.decisionId = decisionId;
  }
}
