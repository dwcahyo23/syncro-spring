package com.syncro.auth.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@IdClass(AuthUserPlantAssignmentId.class)
@Table(name = "auth_user_plant_assignments")
public class AuthUserPlantAssignmentEntity {

  @Id
  @Column(name = "auth_user_id", nullable = false)
  private UUID authUserId;

  @Id
  @Column(name = "plant_id", nullable = false)
  private UUID plantId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AuthUserPlantAssignmentEntity() {
  }

  public AuthUserPlantAssignmentEntity(UUID authUserId, UUID plantId, Instant createdAt) {
    this.authUserId = authUserId;
    this.plantId = plantId;
    this.createdAt = createdAt;
  }

  public UUID getAuthUserId() {
    return authUserId;
  }

  public UUID getPlantId() {
    return plantId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
