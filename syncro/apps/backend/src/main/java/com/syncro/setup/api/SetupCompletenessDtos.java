package com.syncro.setup.api;

import java.util.List;
import java.util.UUID;

public final class SetupCompletenessDtos {
  private SetupCompletenessDtos() {
  }

  public record ScopeInfo(String mode, List<UUID> plantIds, String emptyReason) {
  }

  public record Step(String key, String label, String status, String nextAction, String href) {
  }

  public record SetupCompletenessResponse(ScopeInfo scope, String overallStatus, long machineCount,
      long machinesEligibleCount, List<Step> steps) {
  }
}
