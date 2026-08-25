package com.syncro.org.application;

import com.syncro.org.infrastructure.TeamEntity;
import java.util.HashMap;
import java.util.Map;

/** Audit snapshot for a team (name/expiresAt/memberCount/machineCount) — Phase 1 actor-correlated model. */
public final class TeamAuditValues {
  private TeamAuditValues() {
  }

  public static Map<String, Object> of(TeamEntity team, long memberCount, long machineCount) {
    var values = new HashMap<String, Object>();
    values.put("name", team.getName());
    values.put("expiresAt", team.getExpiresAt().toString());
    values.put("memberCount", memberCount);
    values.put("machineCount", machineCount);
    return values;
  }
}
