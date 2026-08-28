package com.syncro.sparepart.request.domain;

import java.math.BigDecimal;

/**
 * Escalation configuration record (story 12-3, FR-142/FR-147). Represents a single row
 * from the escalation_configs table — an approval tier (cost threshold + required role)
 * or an escalation duration (step name + timeout).
 */
public record EscalationConfig(
    String scope,
    String step,
    BigDecimal minCost,
    BigDecimal maxCost,
    int durationMinutes,
    String approvalRole) {
}