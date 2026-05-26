package com.syncro.auth.infrastructure;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

public class AuthUserPlantAssignmentId implements Serializable {
  private UUID authUserId;
  private UUID plantId;

  protected AuthUserPlantAssignmentId() {
  }

  public AuthUserPlantAssignmentId(UUID authUserId, UUID plantId) {
    this.authUserId = authUserId;
    this.plantId = plantId;
  }

  public UUID getAuthUserId() {
    return authUserId;
  }

  public UUID getPlantId() {
    return plantId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof AuthUserPlantAssignmentId that)) {
      return false;
    }
    return Objects.equals(authUserId, that.authUserId) && Objects.equals(plantId, that.plantId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(authUserId, plantId);
  }
}

