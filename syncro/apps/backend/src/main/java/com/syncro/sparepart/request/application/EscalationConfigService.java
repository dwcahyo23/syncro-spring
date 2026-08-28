package com.syncro.sparepart.request.application;

import com.syncro.sparepart.request.domain.EscalationConfig;
import com.syncro.sparepart.request.infrastructure.db.EscalationConfigEntity;
import com.syncro.sparepart.request.infrastructure.db.EscalationConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves approval tiers and escalation durations from the escalation_configs table
 * (story 12-3, FR-142/FR-147). The config is read from the database, not hardcoded.
 */
@Service
public class EscalationConfigService {

  private static final Logger log = LoggerFactory.getLogger(EscalationConfigService.class);

  private static final String SPAREPART_REQUEST_SCOPE = "SPAREPART_REQUEST";

  private final EscalationConfigRepository repository;

  public EscalationConfigService(EscalationConfigRepository repository) {
    this.repository = repository;
  }

  /**
   * Resolves the required approval role for a given estimated cost.
   * Tiers are matched by [minCost, maxCost) — null min = unbounded below, null max = unbounded above.
   * When the request has no price (hasPrice = false), returns SECTION_LEADER regardless of quantity.
   */
  public String requiredApprovalRole(BigDecimal estimatedCost, boolean hasPrice) {
    if (!hasPrice) {
      return "SECTION_LEADER";
    }
    var configs = repository.findByScopeOrderByStepAsc(SPAREPART_REQUEST_SCOPE);
    // Only approval tiers have a non-null approval_role
    var tiers = configs.stream()
        .filter(c -> c.getApprovalRole() != null)
        .toList();

    for (var tier : tiers) {
      var min = tier.getMinCost();
      var max = tier.getMaxCost();
      boolean inRange = true;
      if (min != null && estimatedCost.compareTo(min) < 0) {
        inRange = false;
      }
      if (max != null && estimatedCost.compareTo(max) >= 0) {
        inRange = false;
      }
      if (inRange) {
        return tier.getApprovalRole();
      }
    }
    // No tier matched — fail closed to the most restrictive tier (the one with null max,
    // i.e. the highest-role tier), never the lowest. A cost in a gap or beyond all tiers
    // must not downgrade the required role.
    var highest = tiers.stream()
        .filter(t -> t.getMaxCost() == null && t.getMinCost() != null)
        .findFirst()
        .or(() -> tiers.isEmpty() ? Optional.empty() : Optional.of(tiers.getLast()));
    if (highest.isPresent()) {
      return highest.get().getApprovalRole();
    }
    // No config rows — fail closed to SECTION_LEADER (lowest tier), never null.
    return "SECTION_LEADER";
  }

  /**
   * Returns the duration in minutes for a given escalation step, or 0 if not found.
   */
  public int durationMinutes(String step) {
    var configs = repository.findByScopeOrderByStepAsc(SPAREPART_REQUEST_SCOPE);
    return configs.stream()
        .filter(c -> step.equals(c.getStep()))
        .findFirst()
        .map(EscalationConfigEntity::getDurationMinutes)
        .orElse(0);
  }

  /**
   * Returns all SPAREPART_REQUEST config rows as domain records.
   */
  public List<EscalationConfig> allForScope() {
    return repository.findByScopeOrderByStepAsc(SPAREPART_REQUEST_SCOPE).stream()
        .map(e -> new EscalationConfig(e.getScope(), e.getStep(), e.getMinCost(), e.getMaxCost(),
            e.getDurationMinutes(), e.getApprovalRole()))
        .toList();
  }
}