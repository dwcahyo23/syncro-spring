package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.config.DashboardAnalyticsProperties;
import com.syncro.maintenance.api.DashboardDtos.MtbfMttrResponse;
import com.syncro.maintenance.api.DashboardDtos.MtbfView;
import com.syncro.maintenance.api.DashboardDtos.MttrView;
import com.syncro.maintenance.api.DashboardDtos.TechnicianKpiResponse;
import com.syncro.maintenance.infrastructure.DashboardAnalyticsRedisCache;
import com.syncro.maintenance.infrastructure.db.RatingDimensionRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.infrastructure.db.WorkorderRatingRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link DashboardAnalyticsService} cache hit/stale/recompute
 * (story 14-2, FR-173/FR-174). Mocks the cache to verify that a fresh cached payload
 * is served with cacheAgeMs, and an expired payload triggers recompute with a new
 * computedAt.
 */
@ExtendWith(MockitoExtension.class)
class DashboardAnalyticsServiceCacheTest {

  private static final String USER_ID = UUID.randomUUID().toString();
  private static final AuthenticatedUser USER = new AuthenticatedUser(USER_ID, "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

  @Mock
  private OperationalScopeService scopes;
  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private WorkorderRatingRepository ratings;
  @Mock
  private RatingDimensionRepository ratingDimensions;
  @Mock
  private AuthUserRepository users;
  @Mock
  private DashboardAnalyticsRedisCache cache;
  @Mock
  private Clock clock;

  private DashboardAnalyticsProperties properties;
  private DashboardAnalyticsService service;

  @BeforeEach
  void setUp() {
    properties = new DashboardAnalyticsProperties(Duration.ofMinutes(30));
    service = new DashboardAnalyticsService(scopes, workOrders, ratings, ratingDimensions,
        users, cache, properties, clock);
  }

  @Test
  @DisplayName("14.2-CACHE-001 P1 fresh cached MTBF/MTTR payload is served with cacheAgeMs")
  void freshCacheServedForMtbf() {
    var now = Instant.parse("2026-08-29T10:00:00Z");
    var computedAt = Instant.parse("2026-08-29T09:45:00Z");
    when(clock.instant()).thenReturn(now);
    when(scopes.derive(USER)).thenReturn(new OperationalScope(null, Set.of(), Set.of()));

    var cached = new MtbfMttrResponse(
        new MtbfView("AVAILABLE", 48.0, 3),
        new MttrView("AVAILABLE", 2.5, 2),
        now.minus(Duration.ofDays(30)), now, computedAt, null, false);
    when(cache.get(eq(DashboardAnalyticsService.MTBF_MTTR_CACHE_KEY), any(), eq(MtbfMttrResponse.class)))
        .thenReturn(Optional.of(cached));

    var result = service.mtbfMttr(USER, null);

    assertThat(result.mtbf().valueHours()).isEqualTo(48.0);
    assertThat(result.mttr().valueHours()).isEqualTo(2.5);
    // 15 minutes after computedAt → cacheAgeMs = 900000
    assertThat(result.cacheAgeMs()).isEqualTo(15 * 60 * 1000L);
    assertThat(result.stale()).isFalse();
    // computedAt should be the cached payload's computedAt (not recomputed).
    assertThat(result.computedAt()).isEqualTo(computedAt);
  }

  @Test
  @DisplayName("14.2-CACHE-002 P1 expired cached MTBF/MTTR is recomputed with new computedAt")
  void expiredCacheRecomputedForMtbf() {
    var now = Instant.parse("2026-08-29T10:00:00Z");
    // Cached was computed 31 minutes ago — beyond the 30-min TTL.
    var computedAt = Instant.parse("2026-08-29T09:29:00Z");
    when(clock.instant()).thenReturn(now);
    when(scopes.derive(USER)).thenReturn(new OperationalScope(null, Set.of(), Set.of()));

    var stale = new MtbfMttrResponse(
        new MtbfView("AVAILABLE", 48.0, 3),
        new MttrView("AVAILABLE", 2.5, 2),
        now.minus(Duration.ofDays(30)), now, computedAt, null, false);
    when(cache.get(eq(DashboardAnalyticsService.MTBF_MTTR_CACHE_KEY), any(), eq(MtbfMttrResponse.class)))
        .thenReturn(Optional.of(stale));

    // No workorders in the DB → INSUFFICIENT_DATA (recompute path).
    when(workOrders.findStoppedBreakdownAnalyticsRows(any(), any(), anyBoolean(), any(), any(), any(), any()))
        .thenReturn(List.of());

    var result = service.mtbfMttr(USER, null);

    // Fresh recompute — new computedAt = now.
    assertThat(result.mtbf().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.mttr().status()).isEqualTo("INSUFFICIENT_DATA");
    assertThat(result.computedAt()).isEqualTo(now);
    // cacheAgeMs is null because this is a fresh recompute.
    assertThat(result.cacheAgeMs()).isNull();
    // stale is false after recompute.
    assertThat(result.stale()).isFalse();
  }

  @Test
  @DisplayName("14.2-CACHE-003 P1 fresh cached technician KPI is served with cacheAgeMs")
  void freshCacheServedForTechnicianKpi() {
    var now = Instant.parse("2026-08-29T10:00:00Z");
    var computedAt = Instant.parse("2026-08-29T09:45:00Z");
    when(clock.instant()).thenReturn(now);
    when(scopes.derive(USER)).thenReturn(new OperationalScope(null, Set.of(), Set.of()));

    var cached = new TechnicianKpiResponse(
        List.of(), now.minus(Duration.ofDays(30)), now, computedAt, null, false);
    when(cache.get(eq(DashboardAnalyticsService.TECHNICIAN_KPI_CACHE_KEY), any(), eq(TechnicianKpiResponse.class)))
        .thenReturn(Optional.of(cached));

    var result = service.technicianKpi(USER, null);

    assertThat(result.cacheAgeMs()).isEqualTo(15 * 60 * 1000L);
    assertThat(result.stale()).isFalse();
    assertThat(result.computedAt()).isEqualTo(computedAt);
  }

  @Test
  @DisplayName("14.2-CACHE-004 P1 expired cached technician KPI is recomputed with new computedAt")
  void expiredCacheRecomputedForTechnicianKpi() {
    var now = Instant.parse("2026-08-29T10:00:00Z");
    var computedAt = Instant.parse("2026-08-29T09:29:00Z");
    when(clock.instant()).thenReturn(now);
    when(scopes.derive(USER)).thenReturn(new OperationalScope(null, Set.of(), Set.of()));

    var stale = new TechnicianKpiResponse(
        List.of(), now.minus(Duration.ofDays(30)), now, computedAt, null, false);
    when(cache.get(eq(DashboardAnalyticsService.TECHNICIAN_KPI_CACHE_KEY), any(), eq(TechnicianKpiResponse.class)))
        .thenReturn(Optional.of(stale));
    when(workOrders.findTechnicianObjectiveRows(anyBoolean(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.findTechnicianObjectiveRowsFromSessions(anyBoolean(), any(), any(), any(), any()))
        .thenReturn(List.of());

    var result = service.technicianKpi(USER, null);

    assertThat(result.technicians()).isEmpty();
    assertThat(result.computedAt()).isEqualTo(now);
    assertThat(result.cacheAgeMs()).isNull();
    assertThat(result.stale()).isFalse();
  }
}