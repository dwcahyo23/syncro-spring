package com.syncro.sparepart.request.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.sparepart.request.infrastructure.db.EscalationConfigEntity;
import com.syncro.sparepart.request.infrastructure.db.EscalationConfigRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Direct unit tests for {@link EscalationConfigService} — the tier-resolution engine
 * that decides which role must approve a given estimated cost (story 12-3, FR-142/AD-16).
 * Every approval test in {@link SparepartRequestServiceTest} stubs the resolver; this
 * class tests the real matching logic at the decision surface.
 */
@ExtendWith(MockitoExtension.class)
class EscalationConfigServiceTest {

  private static final String SCOPE = "SPAREPART_REQUEST";

  @Mock
  private EscalationConfigRepository repository;

  private EscalationConfigService service;

  private List<EscalationConfigEntity> seededTiers;

  @BeforeEach
  void setUp() {
    service = new EscalationConfigService(repository);
    // Mirror the seeded rows from V60 (ordered by min_cost ASC nulls first)
    seededTiers = List.of(
        tier("SECTION_LEADER_APPROVAL", null, new BigDecimal("5000000"), 0, "SECTION_LEADER"),
        tier("MAINTENANCE_LEADER_APPROVAL", new BigDecimal("5000000"), new BigDecimal("50000000"), 0, "MAINTENANCE_LEADER"),
        tier("MANAGER_APPROVAL", new BigDecimal("50000000"), null, 0, "MANAGER_MAINTENANCE"),
        // Escalation durations (no approval role)
        tier("ACK_WAITING", null, null, 480, null),
        tier("PROCESS_WAITING", null, null, 1440, null),
        tier("PURCHASE_WAITING", null, null, 2880, null));
  }

  @Test
  @DisplayName("12.3-CFG-001 P0 cost ≤5M → SECTION_LEADER")
  void costLowSectionLeader() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("3000000"), true))
        .isEqualTo("SECTION_LEADER");
  }

  @Test
  @DisplayName("12.3-CFG-002 P0 cost exactly 5M → MAINTENANCE_LEADER (exclusive upper bound)")
  void costExactFiveMillion() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("5000000"), true))
        .isEqualTo("MAINTENANCE_LEADER");
  }

  @Test
  @DisplayName("12.3-CFG-003 P0 cost exactly 50M → MANAGER_MAINTENANCE (exclusive upper bound)")
  void costExactFiftyMillion() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("50000000"), true))
        .isEqualTo("MANAGER_MAINTENANCE");
  }

  @Test
  @DisplayName("12.3-CFG-004 P0 cost >50M → MANAGER_MAINTENANCE (unbounded above)")
  void costHighManager() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("999999999"), true))
        .isEqualTo("MANAGER_MAINTENANCE");
  }

  @Test
  @DisplayName("12.3-CFG-005 P0 no price → SECTION_LEADER regardless of quantity")
  void noPriceSectionLeader() {
    assertThat(service.requiredApprovalRole(null, false))
        .isEqualTo("SECTION_LEADER");
  }

  @Test
  @DisplayName("12.3-CFG-006 P0 cost in gap between tiers → highest tier (fail closed)")
  void costInGapFailsClosedToHighest() {
    // Simulate a gap: remove the middle tier, cost 10M is between SECTION_LEADER max=5M
    // and MANAGER min=50M — should fall back to MANAGER (highest), not SECTION_LEADER.
    var gapTiers = List.of(
        tier("SECTION_LEADER_APPROVAL", null, new BigDecimal("5000000"), 0, "SECTION_LEADER"),
        tier("MANAGER_APPROVAL", new BigDecimal("50000000"), null, 0, "MANAGER_MAINTENANCE"));
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(gapTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("10000000"), true))
        .isEqualTo("MANAGER_MAINTENANCE");
  }

  @Test
  @DisplayName("12.3-CFG-007 P0 no config rows → SECTION_LEADER (fail closed, never null)")
  void noConfigRowsSectionLeader() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(List.of());
    assertThat(service.requiredApprovalRole(new BigDecimal("1000000"), true))
        .isEqualTo("SECTION_LEADER");
  }

  @Test
  @DisplayName("12.3-CFG-008 P0 cost between 5M and 50M → MAINTENANCE_LEADER")
  void costMidMaintenanceLeader() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("25000000"), true))
        .isEqualTo("MAINTENANCE_LEADER");
  }

  @Test
  @DisplayName("12.3-CFG-009 P0 cost below 0 (negative) → SECTION_LEADER (below first tier)")
  void costNegativeSectionLeader() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.requiredApprovalRole(new BigDecimal("-1"), true))
        .isEqualTo("SECTION_LEADER");
  }

  // --- Duration resolution ---

  @Test
  @DisplayName("12.3-CFG-010 P0 ACK_WAITING duration is 480 minutes")
  void ackWaitingDuration() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.durationMinutes("ACK_WAITING")).isEqualTo(480);
  }

  @Test
  @DisplayName("12.3-CFG-011 P0 unknown step returns 0 (no escalation)")
  void unknownStepDurationZero() {
    when(repository.findByScopeOrderByMinCostAscNullsFirst(SCOPE)).thenReturn(seededTiers);
    assertThat(service.durationMinutes("UNKNOWN")).isZero();
  }

  private static EscalationConfigEntity tier(String step, BigDecimal minCost, BigDecimal maxCost,
      int durationMinutes, String approvalRole) {
    // The JPA no-arg constructor is protected — instantiate via reflection.
    var entity = instantiate();
    ReflectionTestUtils.setField(entity, "id", UUID.randomUUID());
    ReflectionTestUtils.setField(entity, "scope", SCOPE);
    ReflectionTestUtils.setField(entity, "step", step);
    ReflectionTestUtils.setField(entity, "minCost", minCost);
    ReflectionTestUtils.setField(entity, "maxCost", maxCost);
    ReflectionTestUtils.setField(entity, "durationMinutes", durationMinutes);
    ReflectionTestUtils.setField(entity, "approvalRole", approvalRole);
    ReflectionTestUtils.setField(entity, "createdAt", java.time.Instant.now());
    ReflectionTestUtils.setField(entity, "updatedAt", java.time.Instant.now());
    return entity;
  }

  private static EscalationConfigEntity instantiate() {
    try {
      var constructor = EscalationConfigEntity.class.getDeclaredConstructor();
      constructor.setAccessible(true);
      return constructor.newInstance();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}