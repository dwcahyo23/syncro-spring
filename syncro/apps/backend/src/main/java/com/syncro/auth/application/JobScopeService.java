package com.syncro.auth.application;

import com.syncro.auth.domain.ApplicationRole;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Server-side job-scope enforcement — the first concrete ABAC step (FR-025/FR-088).
 *
 * <p>Job scope comes from machine responsibility assignments, stored separately from the
 * application role. The level ladder mirrors the persisted {@code ResponsibilityLevel} enum
 * declaration order (TECHNICIAN &lt; STAFF &lt; LEADER &lt; SPV &lt; MANAGER), kept here as
 * contract strings so this auth-side service stays decoupled from the machine module.
 *
 * <p>SUPER_ADMIN bypasses job-scope checks: platform administrators are unrestricted in every
 * existing path (plant access, alert direct-resolve override) and bootstrap flows must not
 * depend on seeded responsibility rows.
 */
@Service
public class JobScopeService {

  /** Rank order of job-scope levels; index comparison is the "or above" rule. */
  private static final List<String> LEVEL_ORDER =
      List.of("TECHNICIAN", "STAFF", "LEADER", "SPV", "MANAGER");

  private final UserJobScopeReader userJobScopeReader;

  public JobScopeService(UserJobScopeReader userJobScopeReader) {
    this.userJobScopeReader = userJobScopeReader;
  }

  /** Exposed for the ladder-drift pin test. */
  List<String> levelOrder() {
    return LEVEL_ORDER;
  }

  /**
   * @throws IllegalArgumentException if {@code level} is not a known job-scope level
   */
  public boolean hasLevelOrAbove(JwtTokenService.AuthenticatedUser user, String level) {
    int minimumIndex = LEVEL_ORDER.indexOf(level);
    if (minimumIndex < 0) {
      throw new IllegalArgumentException("Unknown job scope level: " + level);
    }
    if (user.applicationRole() == ApplicationRole.SUPER_ADMIN) {
      return true;
    }
    Set<String> qualifying = Set.copyOf(LEVEL_ORDER.subList(minimumIndex, LEVEL_ORDER.size()));
    return userJobScopeReader.hasAnyLevel(UUID.fromString(user.id()), qualifying);
  }

  /**
   * @throws JobScopeForbiddenException if the user holds no assignment at or above {@code level}
   */
  public void requireLevelOrAbove(JwtTokenService.AuthenticatedUser user, String level) {
    if (!hasLevelOrAbove(user, level)) {
      throw new JobScopeForbiddenException(level);
    }
  }
}
