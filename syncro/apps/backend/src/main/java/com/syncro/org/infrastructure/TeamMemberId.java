package com.syncro.org.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Composite primary key for a {@link TeamMemberEntity} (team_id, user_id). */
@Embeddable
public class TeamMemberId implements Serializable {

  @Column(name = "team_id", nullable = false)
  private UUID teamId;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  protected TeamMemberId() {
  }

  public TeamMemberId(UUID teamId, UUID userId) {
    this.teamId = teamId;
    this.userId = userId;
  }

  public UUID getTeamId() {
    return teamId;
  }

  public UUID getUserId() {
    return userId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof TeamMemberId that)) {
      return false;
    }
    return Objects.equals(teamId, that.teamId) && Objects.equals(userId, that.userId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(teamId, userId);
  }
}
