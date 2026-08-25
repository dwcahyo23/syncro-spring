package com.syncro.org.infrastructure;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;

/**
 * Membership link between a cross-plant team and a user. Composite PK
 * (team_id, user_id); deleting the team cascades, deleting the user is RESTRICTed.
 */
@Entity
@Table(name = "team_members")
public class TeamMemberEntity {

  @EmbeddedId
  private TeamMemberId id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("teamId")
  @JoinColumn(name = "team_id", nullable = false)
  private TeamEntity team;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @MapsId("userId")
  @JoinColumn(name = "user_id", nullable = false)
  private com.syncro.auth.infrastructure.AuthUserEntity user;

  protected TeamMemberEntity() {
  }

  public TeamMemberEntity(TeamEntity team, com.syncro.auth.infrastructure.AuthUserEntity user) {
    this.id = new TeamMemberId(team.getId(), user.getId());
    this.team = team;
    this.user = user;
  }

  public TeamMemberId getId() {
    return id;
  }

  public TeamEntity getTeam() {
    return team;
  }

  public com.syncro.auth.infrastructure.AuthUserEntity getUser() {
    return user;
  }
}
