package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.maintenance.application.WorkOrderListService.WorkOrderListValidationException;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderListRow;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderListServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-26T00:00:00Z");

  @Mock
  private WorkOrderRepository workOrders;
  @Mock
  private AuthUserRepository users;
  @Mock
  private OperationalScopeService scopes;

  private final UUID plantId = UUID.randomUUID();
  private final UUID groupId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();
  private final UUID technicianId = UUID.randomUUID();

  private WorkOrderListService service;

  @BeforeEach
  void setUp() {
    service = new WorkOrderListService(workOrders, users, scopes);
  }

  // -------------------------------------------------------------------------
  // List: page shape, scope filter, technician resolution
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("LIST_SVC-001 P0 list returns items/total/page/size and passes the derived scope")
  void listReturnsPageShape() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    var row = listRow(technicianId, WorkOrderStatus.OPEN);
    when(workOrders.findScopedPage(eq(false), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(row));
    when(workOrders.countScoped(eq(false), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(42L);
    when(users.findAllById(Set.of(technicianId))).thenReturn(List.of(techUser(technicianId)));

    var result = service.list(user, null, null, null, null, null, null, 0, 20);

    assertThat(result.total()).isEqualTo(42L);
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.items()).hasSize(1);
    assertThat(result.items().getFirst().id()).isEqualTo("WO-260800001");
    assertThat(result.items().getFirst().assignedTechnicianName()).isEqualTo("Tech User");
    assertThat(result.items().getFirst().machineCode()).isEqualTo("M-001");
    assertThat(result.items().getFirst().plantCode()).isEqualTo("P01");
  }

  @Test
  @DisplayName("LIST_SVC-002 P0 SUPER_ADMIN is unrestricted (plantIds null → true)")
  void listSuperAdminUnrestricted() {
    var user = scopedUser(ApplicationRole.SUPER_ADMIN);
    var scope = new OperationalScope(null, Set.of(), Set.of());
    when(scopes.derive(user)).thenReturn(scope);
    when(workOrders.findScopedPage(eq(true), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(eq(true), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    var result = service.list(user, null, null, null, null, null, null, 0, 20);

    assertThat(result.items()).isEmpty();
    assertThat(result.total()).isZero();
  }

  @Test
  @DisplayName("LIST_SVC-003 P0 active team group ids merge into the group filter")
  void listMergesActiveTeamIds() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    var teamGroupId = UUID.randomUUID();
    var scope = new OperationalScope(Set.of(plantId), Set.of(groupId), Set.of(teamGroupId));
    when(scopes.derive(user)).thenReturn(scope);
    when(workOrders.findScopedPage(eq(false), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(eq(false), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    service.list(user, null, null, null, null, null, null, 0, 20);

    org.mockito.Mockito.verify(workOrders).findScopedPage(
        eq(false), any(), org.mockito.ArgumentMatchers.argThat(
            ids -> ids.contains(teamGroupId) && ids.contains(groupId)),
        any(), any(), any(), any(), any(), any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // Filters: month range, status, machine, search
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("LIST_SVC-004 P0 month range maps to start-of-day / exclusive end-of-day UTC bounds")
  void listMonthFilterBounds() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    service.list(user, "2026-08-01", "2026-08-31", null, null, null, null, 0, 20);

    org.mockito.Mockito.verify(workOrders).findScopedPage(
        anyBoolean(), any(), any(),
        org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-01T00:00:00Z")),
        org.mockito.ArgumentMatchers.eq(Instant.parse("2026-09-01T00:00:00Z")),
        any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("LIST_SVC-005 P0 status/machine/search are forwarded to the query")
  void listForwardsFilters() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    service.list(user, null, null, WorkOrderStatus.OPEN, machineId, null, "WO-2608", 0, 20);

    org.mockito.Mockito.verify(workOrders).findScopedPage(
        anyBoolean(), any(), any(), any(), any(),
        org.mockito.ArgumentMatchers.eq("OPEN"),
        org.mockito.ArgumentMatchers.eq(machineId),
        org.mockito.ArgumentMatchers.eq(new UUID(0L, 0L)),
        org.mockito.ArgumentMatchers.eq(""),
        org.mockito.ArgumentMatchers.eq("%wo-2608%"),
        any());
  }

  @Test
  @DisplayName("LIST_SVC-006 P0 search escapes % _ and \\ wildcard characters")
  void listEscapesSearchWildcards() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    service.list(user, null, null, null, null, null, "50%_x\\y", 0, 20);

    org.mockito.Mockito.verify(workOrders).findScopedPage(
        anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(),
        org.mockito.ArgumentMatchers.eq("%50\\%\\_x\\\\y%"),
        any());
  }

  // -------------------------------------------------------------------------
  // Validation & edge cases
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("LIST_SVC-006b P0 categoryCode is forwarded to the query (blank when absent)")
  void listForwardsCategoryCode() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0L);

    service.list(user, null, null, null, null, "01", null, 0, 20);

    org.mockito.Mockito.verify(workOrders).findScopedPage(
        anyBoolean(), any(), any(), any(), any(), any(), any(), any(),
        org.mockito.ArgumentMatchers.eq("01"),
        org.mockito.ArgumentMatchers.eq(""),
        any());
  }

  @Test
  @DisplayName("LIST_SVC-007 P0 a bad from date is rejected with a from fieldError")
  void listBadFromDate() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.list(user, "not-a-date", null, null, null, null, null, 0, 20))
        .isInstanceOfSatisfying(WorkOrderListValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("from"));
  }

  @Test
  @DisplayName("LIST_SVC-008 P0 a bad to date is rejected with a to fieldError")
  void listBadToDate() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));

    assertThatThrownBy(() -> service.list(user, null, "2026-13-99", null, null, null, null, 0, 20))
        .isInstanceOfSatisfying(WorkOrderListValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("to"));
  }

  @Test
  @DisplayName("LIST_SVC-009 P0 a negative page is rejected")
  void listNegativePage() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> service.list(user, null, null, null, null, null, null, -1, 20))
        .isInstanceOfSatisfying(WorkOrderListValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("page"));
  }

  @Test
  @DisplayName("LIST_SVC-010 P0 a size outside 1..200 is rejected")
  void listBadSize() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);

    assertThatThrownBy(() -> service.list(user, null, null, null, null, null, null, 0, 0))
        .isInstanceOfSatisfying(WorkOrderListValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("size"));
    assertThatThrownBy(() -> service.list(user, null, null, null, null, null, null, 0, 201))
        .isInstanceOfSatisfying(WorkOrderListValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKey("size"));
  }

  @Test
  @DisplayName("LIST_SVC-011 P0 an empty result returns items:[] and total:0")
  void listEmpty() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of());
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0L);

    var result = service.list(user, null, null, null, null, null, null, 0, 20);

    assertThat(result.items()).isEmpty();
    assertThat(result.total()).isZero();
  }

  @Test
  @DisplayName("LIST_SVC-012 P0 description is trimmed; blank becomes null")
  void listTrimsDescription() {
    var user = scopedUser(ApplicationRole.TECHNICIAN);
    when(scopes.derive(user)).thenReturn(new OperationalScope(Set.of(plantId), Set.of(), Set.of()));
    var workOrder = new WorkOrderEntity("WO-260800001", "INTERNAL", null, WorkOrderStatus.OPEN, UUID.randomUUID(),
        machineId, "  messy description  ", 0, null, technicianId, UUID.randomUUID(), NOW, NOW);
    var row = new WorkOrderListRow(workOrder, null, "M-001", "Machine", "P01");
    when(workOrders.findScopedPage(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(List.of(row));
    when(workOrders.countScoped(anyBoolean(), any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1L);
    when(users.findAllById(Set.of(technicianId))).thenReturn(List.of(techUser(technicianId)));

    var result = service.list(user, null, null, null, null, null, null, 0, 20);

    assertThat(result.items().getFirst().description()).isEqualTo("messy description");
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private WorkOrderListRow listRow(UUID assignedTechnician, WorkOrderStatus status) {
    var workOrder = new WorkOrderEntity("WO-260800001", "INTERNAL", null, status, UUID.randomUUID(),
        machineId, "desc", 0, null, assignedTechnician, UUID.randomUUID(), NOW, NOW);
    var category = new com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity(
        UUID.randomUUID(), "01", "Breakdown", null, NOW, NOW);
    return new WorkOrderListRow(workOrder, category, "M-001", "Machine", "P01");
  }

  private AuthenticatedUser scopedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static AuthUserEntity techUser(UUID id) {
    var user = new AuthUserEntity(id, "tech@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, NOW, NOW);
    user.updateMasterFields("Tech User", "123456", null, null, null, NOW);
    return user;
  }
}
