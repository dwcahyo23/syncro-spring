package com.syncro.kpi.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Organizational scope for KPI reads (story 20-1, AD-2). Thin consumer of the org
 * module's single scope-derivation service — the kpi module never recomputes scope
 * (AD-2 forbids divergent scope sets). The derived dimensions are applied to
 * materialized-row filtering in {@link KpiQueryService}: leaders see their machine
 * groups, managers their plants, global roles everything.
 */
@Service
public class KpiScopeService {

  private final OperationalScopeService scopes;

  public KpiScopeService(OperationalScopeService scopes) {
    this.scopes = scopes;
  }

  /** The caller's derived scope ({plantIds, machineGroupIds, activeTeamIds}). */
  public OperationalScope derive(AuthenticatedUser user) {
    return scopes.derive(user);
  }

  /**
   * Whether the scope restricts reads to the given plant. SUPER_ADMIN (null plantIds)
   * sees all; a restricted user sees a plant only when it is in their plantIds or they
   * lead a group inside it (group membership is resolved by the caller against rows).
   */
  public boolean plantVisible(OperationalScope scope, UUID plantId) {
    return scope.plantIds() == null || scope.plantIds().contains(plantId);
  }

  /** Machine-group ids in scope (leader groups + active team groups), as a list for queries. */
  public List<UUID> groupIds(OperationalScope scope) {
    var merged = new java.util.LinkedHashSet<UUID>();
    if (scope.machineGroupIds() != null) {
      merged.addAll(scope.machineGroupIds());
    }
    if (scope.activeTeamIds() != null) {
      merged.addAll(scope.activeTeamIds());
    }
    return List.copyOf(merged);
  }
}
