package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.maintenance.api.DashboardDtos.MtbfMttrResponse;
import com.syncro.maintenance.api.DashboardDtos.TechnicianKpiResponse;
import com.syncro.maintenance.domain.workorder.RatingType;
import com.syncro.maintenance.infrastructure.db.RatingDimensionEntity;
import com.syncro.maintenance.infrastructure.db.RatingDimensionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingScoreEntity;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingScoreRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Dashboard analytics integration tests (story 14-2, FR-173/FR-174): scope filtering,
 * insufficient MTBF (<2 stopped breakdowns), insufficient MTTR, on-time %, technician KPI
 * with ratings and objective KPIs, empty-scope guard with null window bounds, plantId
 * filtering, DONE-transition vs updatedAt window semantics, repair-session attribution,
 * and technician name fallback.
 *
 * <p>Redis is unavailable in the Postgres-only test container; the
 * {@link DashboardAnalyticsRedisCache} degrades to recompute (log warn, never throw).
 * Tests verify the recompute path exclusively.
 */
class DashboardAnalyticsServiceIntegrationTest extends AbstractPostgresIntegrationTest {

  @PersistenceContext
  private EntityManager entityManager;

  @Autowired
  private DashboardAnalyticsService analyticsService;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private PlantRepository plants;

  @Autowired
  private WorkOrderCategoryRepository categories;

  @Autowired
  private RatingDimensionRepository ratingDimensions;

  @Autowired
  private WorkorderRatingRepository ratings;

  @Autowired
  private WorkorderRatingScoreRepository ratingScores;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbc;

  private static final Timestamp TS = Timestamp.from(Instant.parse("2026-08-25T09:00:00Z"));
  private static final AtomicInteger seq = new AtomicInteger();

  private Chain chain;

  @BeforeEach
  void seed() {
    chain = seedChain();
  }

  // ---------------------------------------------------------------------------
  // MTBF/MTTR
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("14.2-ANALYTICS-001 P1 MTBF/MTTR with >=2 stopped breakdown WOs returns available values")
  void mtbfMttrWithEnoughData() {
    var admin = superAdmin();
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    // Second breakdown WO — needed for MTBF (>=2).
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 180L, 45L, chain.breakdownCatId(),
        Instant.parse("2026-08-22T10:00:00Z"));

    var result = analyticsService.mtbfMttr(admin, null);

    assertThat(result.mtbf().status()).isEqualTo("AVAILABLE");
    assertThat(result.mtbf().valueHours()).isNotNull();
    assertThat(result.mtbf().workorderCount()).isGreaterThanOrEqualTo(2);
    assertThat(result.mttr().status()).isEqualTo("AVAILABLE");
    assertThat(result.mttr().valueHours()).isNotNull();
    assertThat(result.computedAt()).isNotNull();
    assertThat(result.windowFrom()).isNotNull();
    assertThat(result.windowTo()).isNotNull();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-002 P1 MTBF insufficient when <2 stopped breakdown WOs")
  void mtbfInsufficientWithSingleStoppedBreakdown() {
    var admin = superAdmin();
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));

    var result = analyticsService.mtbfMttr(admin, null);
    assertThat(result.mtbf().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mtbf().valueHours()).isNull();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-002b P1 OPEN breakdown WOs are never counted as MTBF stops")
  void openBreakdownsNeverCountedAsStops() {
    var admin = superAdmin();
    // Two OPEN breakdown WOs — never stops, never MTBF rows.
    seedBreakdownWoWithStatus(chain.machine1Id(), "OPEN", null, null, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    seedBreakdownWoWithStatus(chain.machine1Id(), "IN_PROGRESS", null, null, chain.breakdownCatId(),
        Instant.parse("2026-08-22T10:00:00Z"));

    var result = analyticsService.mtbfMttr(admin, null);

    // No stopped breakdowns → MTBF and MTTR are both INSUFFICIENT_DATA.
    assertThat(result.mtbf().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mtbf().workorderCount()).isZero();
    assertThat(result.mttr().status()).isEqualTo("INSUFFICIENT_DATA");
  }

  @Test
  @DisplayName("14.2-ANALYTICS-003 P1 MTTR insufficient when no completed breakdown WOs have mttrMinutes")
  void mttrInsufficientWithNoCompletedMttr() {
    var admin = superAdmin();
    // Two stopped breakdown WOs, but neither has mttrMinutes persisted.
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", null, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", null, 45L, chain.breakdownCatId(),
        Instant.parse("2026-08-22T10:00:00Z"));

    var result = analyticsService.mtbfMttr(admin, null);

    assertThat(result.mtbf().status()).isEqualTo("AVAILABLE"); // 2 stopped breakdowns
    assertThat(result.mttr().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mttr().valueHours()).isNull();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-004 P1 empty scope returns empty MTBF/MTTR with null window bounds")
  void mtbfMttrEmptyScope() {
    var outsider = new AuthenticatedUser(UUID.randomUUID().toString(), "outsider@syncro.dev", ApplicationRole.AUDITOR);

    var result = analyticsService.mtbfMttr(outsider, null);

    assertThat(result.mtbf().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mtbf().valueHours()).isNull();
    assertThat(result.mttr().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mttr().valueHours()).isNull();
    // No fabricated window for empty scope.
    assertThat(result.windowFrom()).isNull();
    assertThat(result.windowTo()).isNull();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-005 P1 plantId outside scope returns empty")
  void mtbfMttrPlantIdOutsideScopeReturnsEmpty() {
    var admin = superAdmin();

    var result = analyticsService.mtbfMttr(admin, UUID.randomUUID());

    assertThat(result.mtbf().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mttr().status()).isEqualTo("INSUFFICIENT_DATA");
  }

  @Test
  @DisplayName("14.2-ANALYTICS-005b P1 in-scope plantId filters to that plant only")
  void mtbfMttrPlantIdFiltersToPlant() {
    var admin = superAdmin();
    // Plant 1: two stopped breakdowns → MTBF available.
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 180L, 45L, chain.breakdownCatId(),
        Instant.parse("2026-08-22T10:00:00Z"));
    // Plant 2: one stopped breakdown → MTBF insufficient on plant 1's view.
    seedStoppedBreakdownWo(chain.machine2Id(), "PENDING_REVIEW", 90L, 30L, chain.breakdownCatId(),
        Instant.parse("2026-08-21T10:00:00Z"));

    var result = analyticsService.mtbfMttr(admin, chain.plant1Id());

    // Only plant 1's two breakdowns count.
    assertThat(result.mtbf().status()).isEqualTo("AVAILABLE");
    assertThat(result.mtbf().workorderCount()).isEqualTo(2);
    assertThat(result.mttr().status()).isEqualTo("AVAILABLE");
    assertThat(result.mttr().workorderCount()).isEqualTo(2);
  }

  @Test
  @DisplayName("14.2-ANALYTICS-005c P1 DONE-transition ≠ updatedAt: window membership follows the derived stop time")
  void mtbfMttrWindowFollowsDerivedStopTime() {
    var admin = superAdmin();
    var now = Instant.now();
    // WO-A: updatedAt outside the 30-day window (60 days ago), DONE transition inside (8 days ago).
    // Its derived stop time is the DONE transition → inside the window → counted.
    var woA = "WO-STOP-A-" + seq.incrementAndGet();
    seedWorkOrderRow(woA, chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        now.minus(java.time.Duration.ofDays(60))); // updatedAt = 60 days ago
    seedDoneHistory(woA, now.minus(java.time.Duration.ofDays(8))); // DONE transition = 8 days ago

    // WO-B: updatedAt inside the window (5 days ago), DONE transition outside (60 days ago).
    // Its derived stop time is the DONE transition → outside the window → NOT counted.
    var woB = "WO-STOP-B-" + seq.incrementAndGet();
    seedWorkOrderRow(woB, chain.machine1Id(), "PENDING_REVIEW", 180L, 45L, chain.breakdownCatId(),
        now.minus(java.time.Duration.ofDays(5))); // updatedAt = 5 days ago
    seedDoneHistory(woB, now.minus(java.time.Duration.ofDays(60))); // DONE transition = 60 days ago

    // WO-C: DONE with no history row → fallback to updatedAt (3 days ago) → inside window.
    var woC = "WO-STOP-C-" + seq.incrementAndGet();
    seedWorkOrderRow(woC, chain.machine1Id(), "PENDING_REVIEW", 240L, 30L, chain.breakdownCatId(),
        now.minus(java.time.Duration.ofDays(3))); // no DONE history row

    var result = analyticsService.mtbfMttr(admin, null);

    // WO-A and WO-C are in the window (2 stopped breakdowns) → MTBF available.
    // WO-B's DONE transition is outside → excluded.
    assertThat(result.mtbf().status()).isEqualTo("AVAILABLE");
    assertThat(result.mtbf().workorderCount()).isEqualTo(2);
    assertThat(result.mttr().workorderCount()).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------
  // Technician KPI
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("14.2-ANALYTICS-006 P1 technician KPI returns objective KPIs with ratings")
  void technicianKpiReturnsObjectiveAndRatings() {
    var admin = superAdmin();
    var techUser = persistTechUser("tech-a@syncro.dev");
    var techId = UUID.fromString(techUser.id());
    var woId = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    // Assign the technician to the workorder.
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ? WHERE id = ?", techId, woId);

    // Add a rating for the technician.
    var ratingId = UUID.randomUUID();
    ratings.saveAndFlush(new WorkorderRatingEntity(ratingId, woId, RatingType.TECHNICIAN, techId,
        UUID.randomUUID(), TS.toInstant()));
    ratingScores.saveAndFlush(new WorkorderRatingScoreEntity(ratingId, chain.dim1Id(), (short) 4));
    ratingScores.saveAndFlush(new WorkorderRatingScoreEntity(ratingId, chain.dim2Id(), (short) 5));

    var result = analyticsService.technicianKpi(admin, null);

    assertThat(result.technicians()).isNotEmpty();
    var techRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(techId))
        .findFirst();
    assertThat(techRow).isPresent();
    assertThat(techRow.get().completedCount()).isEqualTo(1);
    assertThat(techRow.get().averageMttrHours()).isCloseTo(2.0, org.assertj.core.api.Assertions.within(0.001)); // 120/60
    assertThat(techRow.get().onTimePercentage()).isNotNull();
    // Has ratings.
    assertThat(techRow.get().ratings()).isNotEmpty();
    // The dimension codes should match the seed.
    assertThat(techRow.get().ratings()).anyMatch(r -> r.dimensionCode().equals("RESPONSIVENESS"));
    assertThat(techRow.get().ratings()).anyMatch(r -> r.dimensionCode().equals("COMMUNICATION"));
  }

  @Test
  @DisplayName("14.2-ANALYTICS-007 P1 technician KPI shows objective KPIs when no ratings exist")
  void technicianKpiObjectiveWithoutRatings() {
    var admin = superAdmin();
    var techUser = persistTechUser("tech-b@syncro.dev");
    var techId = UUID.fromString(techUser.id());
    var woId = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ? WHERE id = ?", techId, woId);

    var result = analyticsService.technicianKpi(admin, null);

    var techRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(techId))
        .findFirst();
    assertThat(techRow).isPresent();
    assertThat(techRow.get().completedCount()).isEqualTo(1);
    assertThat(techRow.get().ratings()).isEmpty();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-008 P1 technician KPI empty scope returns empty with null window")
  void technicianKpiEmptyScope() {
    var outsider = new AuthenticatedUser(UUID.randomUUID().toString(), "outsider@syncro.dev", ApplicationRole.AUDITOR);

    var result = analyticsService.technicianKpi(outsider, null);

    assertThat(result.technicians()).isEmpty();
    assertThat(result.windowFrom()).isNull();
    assertThat(result.windowTo()).isNull();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-009 P1 technician KPI plantId outside scope returns empty")
  void technicianKpiPlantIdOutsideScope() {
    var admin = superAdmin();

    var result = analyticsService.technicianKpi(admin, UUID.randomUUID());

    assertThat(result.technicians()).isEmpty();
  }

  @Test
  @DisplayName("14.2-ANALYTICS-010 P1 on-time % excludes WOs without target or response time")
  void onTimeExcludesIncompleteRows() {
    var admin = superAdmin();
    var techUser = persistTechUser("tech-c@syncro.dev");
    var techId = UUID.fromString(techUser.id());
    // WO with both responseTime and target → on time.
    var wo1 = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 30L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ? WHERE id = ?", techId, wo1);
    // WO with responseTime but no target → excluded.
    var wo2 = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 90L, chain.breakdownCatId(),
        Instant.parse("2026-08-21T10:00:00Z"));
    // Remove the category association so targetResponseMinutes is null.
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ?, category_id = NULL WHERE id = ?",
        techId, wo2);

    var result = analyticsService.technicianKpi(admin, null);

    var techRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(techId))
        .findFirst();
    assertThat(techRow).isPresent();
    // Only WO1 is countable (has both responseTime and target).
    assertThat(techRow.get().onTimePercentage()).isCloseTo(100.0, org.assertj.core.api.Assertions.within(0.01));
  }

  @Test
  @DisplayName("14.2-ANALYTICS-011 P1 technician avg MTTR scoped to breakdown WOs only")
  void technicianAverageMttrScopedToBreakdown() {
    var admin = superAdmin();
    var techUser = persistTechUser("tech-d@syncro.dev");
    var techId = UUID.fromString(techUser.id());
    // Breakdown WO with mttrMinutes 120 → counts.
    var wo1 = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ? WHERE id = ?", techId, wo1);
    // Non-breakdown WO with mttrMinutes 60 → excluded from the MTTR average.
    var wo2 = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 60L, 30L, chain.preventiveCatId(),
        Instant.parse("2026-08-21T10:00:00Z"));
    jdbc.update("UPDATE work_orders SET assigned_technician_id = ? WHERE id = ?", techId, wo2);

    var result = analyticsService.technicianKpi(admin, null);

    var techRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(techId))
        .findFirst();
    assertThat(techRow).isPresent();
    // Only the breakdown WO (120 min) counts → 2.0h.
    assertThat(techRow.get().averageMttrHours()).isCloseTo(2.0, org.assertj.core.api.Assertions.within(0.001));
    assertThat(techRow.get().completedCount()).isEqualTo(2); // both DONE count as completed
  }

  @Test
  @DisplayName("14.2-ANALYTICS-012 P1 repair-session attribution: null-assigned WO appears under the session technician")
  void repairSessionAttribution() {
    var admin = superAdmin();
    var techUser = persistTechUser("tech-e@syncro.dev");
    var techId = UUID.fromString(techUser.id());
    // WO with NO assigned technician, but a completed repair session by the technician.
    var woId = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, description, started_at, ended_at,
          duration_minutes, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), woId, techId, "Repair session", Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")),
        Timestamp.from(Instant.parse("2026-08-20T10:00:00Z")), 120L, TS, TS);

    var result = analyticsService.technicianKpi(admin, null);

    var techRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(techId))
        .findFirst();
    assertThat(techRow).isPresent();
    assertThat(techRow.get().completedCount()).isEqualTo(1);
    assertThat(techRow.get().averageMttrHours()).isCloseTo(2.0, org.assertj.core.api.Assertions.within(0.001));
  }

  @Test
  @DisplayName("14.2-ANALYTICS-013 P1 technician without auth_users row falls back to UUID name")
  void technicianNameFallbackToUuid() {
    var admin = superAdmin();
    // A session technician UUID with no auth_users row.
    var ghostTechId = UUID.randomUUID();
    var woId = seedStoppedBreakdownWo(chain.machine1Id(), "PENDING_REVIEW", 120L, 60L, chain.breakdownCatId(),
        Instant.parse("2026-08-20T10:00:00Z"));
    jdbc.update("""
        INSERT INTO repair_sessions (id, work_order_id, technician_id, description, started_at, ended_at,
          duration_minutes, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), woId, ghostTechId, "Ghost session", Timestamp.from(Instant.parse("2026-08-20T08:00:00Z")),
        Timestamp.from(Instant.parse("2026-08-20T10:00:00Z")), 120L, TS, TS);

    var result = analyticsService.technicianKpi(admin, null);

    var ghostRow = result.technicians().stream()
        .filter(t -> t.technicianId().equals(ghostTechId))
        .findFirst();
    assertThat(ghostRow).isPresent();
    assertThat(ghostRow.get().technicianName()).isEqualTo(ghostTechId.toString());
  }

  // ---------------------------------------------------------------------------
  // Seed helpers
  // ---------------------------------------------------------------------------

  /** Seeds a stopped (PENDING_REVIEW/CLOSED) breakdown WO with a PENDING_REVIEW history row at the given stop time. */
  private String seedStoppedBreakdownWo(UUID machineId, String status, Long mttr, Long responseTime,
      UUID catId, Instant stopAt) {
    int s = seq.incrementAndGet();
    var id = "WO-ANALYTICS-" + s;
    seedWorkOrderRow(id, machineId, status, mttr, responseTime, catId, stopAt);
    seedDoneHistory(id, stopAt);
    return id;
  }

  private void seedWorkOrderRow(String id, UUID machineId, String status, Long mttr, Long responseTime,
      UUID catId, Instant updatedAt) {
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, machine_id, category_id, mttr_minutes, response_time_minutes,
          created_at, updated_at, sync_version)
        VALUES (?,?,?,?,?,?,?,?,?,?)
        """, id, "INTERNAL", status, machineId, catId, mttr, responseTime, Timestamp.from(updatedAt),
        Timestamp.from(updatedAt), 1);
  }

  /** Seeds a breakdown WO with a given status (no PENDING_REVIEW history row). */
  private String seedBreakdownWoWithStatus(UUID machineId, String status, Long mttr, Long responseTime,
      UUID catId, Instant stopAt) {
    int s = seq.incrementAndGet();
    var id = "WO-ANALYTICS-" + s;
    seedWorkOrderRow(id, machineId, status, mttr, responseTime, catId, stopAt);
    return id;
  }

  private void seedDoneHistory(String workOrderId, Instant transitionedAt) {
    jdbc.update("""
        INSERT INTO work_order_status_history (id, work_order_id, from_status, to_status, source, actor, transitioned_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        """, UUID.randomUUID(), workOrderId, "IN_PROGRESS", "PENDING_REVIEW", "MANUAL", "SYSTEM",
        Timestamp.from(transitionedAt));
  }

  private AuthenticatedUser persistTechUser(String loginIdentifier) {
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(),
        loginIdentifier,
        passwordEncoder.encode("syncro-test-password"),
        ApplicationRole.TECHNICIAN,
        true,
        TS.toInstant(),
        TS.toInstant()));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), ApplicationRole.TECHNICIAN);
  }

  private static AuthenticatedUser superAdmin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private Chain seedChain() {
    int s = seq.incrementAndGet();
    String tag = "ANALYTICS-" + s;
    UUID plant1 = UUID.randomUUID();
    UUID plant2 = UUID.randomUUID();
    UUID section = UUID.randomUUID();
    UUID group1 = UUID.randomUUID();
    UUID group2 = UUID.randomUUID();
    UUID machine1 = UUID.randomUUID();
    UUID machine2 = UUID.randomUUID();
    UUID breakdownCatId = UUID.randomUUID();
    UUID preventiveCatId = UUID.randomUUID();
    UUID dim1Id = UUID.randomUUID();
    UUID dim2Id = UUID.randomUUID();

    // Plants
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plant1, "P1-" + tag, "Plant 1 " + tag, TS, TS);
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?,?,?,?,?)",
        plant2, "P2-" + tag, "Plant 2 " + tag, TS, TS);

    // Section
    jdbc.update("INSERT INTO sections (id, plant_id, code, name, active, version, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        section, plant1, "MACHINERY", "Machinery", true, 0, TS, TS);

    // Machine groups
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group1, plant1, "Group1 " + s, section, TS, TS);
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, section_id, created_at, updated_at) VALUES (?,?,?,?,?,?)",
        group2, plant2, "Group2 " + s, section, TS, TS);

    // Machines
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine1, plant1, group1, "M1-" + tag, "Machine 1 " + tag, "ACTIVE", TS, TS);
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at) VALUES (?,?,?,?,?,?,?,?)",
        machine2, plant2, group2, "M2-" + tag, "Machine 2 " + tag, "ACTIVE", TS, TS);

    // Breakdown category (code "01")
    categories.saveAndFlush(new WorkOrderCategoryEntity(breakdownCatId, "01", "Breakdown",
        UUID.randomUUID(), TS.toInstant(), TS.toInstant(), 60));
    // Non-breakdown category (code "03" — unique, not seeded by migrations)
    categories.saveAndFlush(new WorkOrderCategoryEntity(preventiveCatId, "03", "Non-Breakdown",
        UUID.randomUUID(), TS.toInstant(), TS.toInstant(), 120));

    // Rating dimensions — use unique codes that won't collide with the V53 seed (SPEED, WORK_QUALITY, TIDINESS).
    ratingDimensions.saveAndFlush(new RatingDimensionEntity(dim1Id, "RESPONSIVENESS", "Responsiveness", 1,
        UUID.randomUUID(), TS.toInstant()));
    ratingDimensions.saveAndFlush(new RatingDimensionEntity(dim2Id, "COMMUNICATION", "Communication", 2,
        UUID.randomUUID(), TS.toInstant()));

    return new Chain(plant1, plant2, section, group1, group2, machine1, machine2,
        breakdownCatId, preventiveCatId, dim1Id, dim2Id);
  }

  private record Chain(UUID plant1Id, UUID plant2Id, UUID sectionId, UUID group1Id, UUID group2Id,
      UUID machine1Id, UUID machine2Id, UUID breakdownCatId, UUID preventiveCatId, UUID dim1Id, UUID dim2Id) {
  }
}