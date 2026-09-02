package com.syncro.inventory.api;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService;
import com.syncro.inventory.application.InventoryReservationService.CreateReservationCommand;
import com.syncro.inventory.application.InventoryReservationService.InsufficientStockException;
import com.syncro.inventory.application.InventoryReservationService.InvalidReservationTransitionException;
import com.syncro.inventory.application.InventoryReservationService.InventoryReservationNotFoundException;
import com.syncro.inventory.application.InventoryReservationService.ReservationForbiddenException;
import com.syncro.inventory.application.InventoryReservationService.ReservationValidationException;
import com.syncro.inventory.application.InventoryReservationService.ReservationView;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.domain.InventoryReservationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Story 18-5: the inventory-reservations API contract — DTO shape (camelCase, UUID
 * opaque), status codes and machine-readable error codes per the spec matrix. The
 * service is mocked; lifecycle/atomicity behavior is proven in
 * {@code InventoryReservationServiceIntegrationTest}.
 */
@WebMvcTest(InventoryReservationController.class)
@Import({SecurityConfig.class, InventoryReservationExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class InventoryReservationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryReservationService service;
  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID RESERVATION_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SPAREPART_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final UUID LOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  private static final UUID REQUESTER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  @DisplayName("18.5-API-001 P0 create returns 201 with the camelCase view")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenReturn(reservationView(InventoryReservationStatus.ACTIVE));

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()))
        .andExpect(jsonPath("$.sparepartId").value(SPAREPART_ID.toString()))
        .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
        .andExpect(jsonPath("$.quantity").value(4))
        .andExpect(jsonPath("$.remainingQuantity").value(4))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.referenceType").value("WORK_ORDER"))
        .andExpect(jsonPath("$.referenceId").value("WO-001"))
        .andExpect(jsonPath("$.requestedBy").value(REQUESTER_ID.toString()))
        .andExpect(jsonPath("$.consumedBy").value(nullValue()))
        .andExpect(jsonPath("$.cancelledBy").value(nullValue()));
  }

  @Test
  @DisplayName("18.5-API-002 P0 missing body field maps to 400 VALIDATION_ERROR")
  void createMissingFieldRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sparepartId\":\"" + SPAREPART_ID + "\",\"locationId\":\""
                + LOCATION_ID + "\",\"quantity\":4,\"referenceType\":\"WORK_ORDER\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.referenceId").exists());
  }

  @Test
  @DisplayName("18.5-API-003 P0 quantity <= 0 maps to 400 VALIDATION_ERROR")
  void createNonPositiveQuantityRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sparepartId\":\"" + SPAREPART_ID + "\",\"locationId\":\""
                + LOCATION_ID + "\",\"quantity\":0,\"referenceType\":\"WORK_ORDER\","
                + "\"referenceId\":\"WO-003\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.quantity").exists());
  }

  @Test
  @DisplayName("18.5-API-004 P0 business validation error maps to 400 VALIDATION_ERROR")
  void createBusinessValidationRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenThrow(new ReservationValidationException(Map.of("referenceType",
            "Reference type must not be blank.")));

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.referenceType").exists());
  }

  @Test
  @DisplayName("18.5-API-005 P0 unknown sparepart maps to 404 SPAREPART_NOT_FOUND")
  void createUnknownSparepart() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenThrow(new SparepartNotFoundException());

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.5-API-006 P0 unknown/inactive location maps to 404 INVENTORY_LOCATION_NOT_FOUND")
  void createUnknownLocation() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenThrow(new InventoryLocationNotFoundException());

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_LOCATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.5-API-007 P0 wrong role maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenThrow(new ReservationForbiddenException());

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("18.5-API-008 P0 insufficient stock maps to 409 INSUFFICIENT_STOCK")
  void createInsufficientStock() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateReservationCommand.class)))
        .thenThrow(new InsufficientStockException());

    mockMvc.perform(post("/api/v1/inventory-reservations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
  }

  @Test
  @DisplayName("18.5-API-009 P0 consume returns 200 with the consumed view")
  void consumeReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.consume(eq(user), eq(RESERVATION_ID), eq(new BigDecimal("2"))))
        .thenReturn(reservationView(InventoryReservationStatus.ACTIVE));

    mockMvc.perform(post("/api/v1/inventory-reservations/{id}/consume", RESERVATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
  }

  @Test
  @DisplayName("18.5-API-010 P0 double transition maps to 409 INVALID_RESERVATION_TRANSITION")
  void consumeInvalidTransition() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.consume(eq(user), eq(RESERVATION_ID), eq(new BigDecimal("2"))))
        .thenThrow(new InvalidReservationTransitionException());

    mockMvc.perform(post("/api/v1/inventory-reservations/{id}/consume", RESERVATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"quantity\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_TRANSITION"));
  }

  @Test
  @DisplayName("18.5-API-011 P0 cancel returns 200 with the CANCELLED view")
  void cancelReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.cancel(eq(user), eq(RESERVATION_ID)))
        .thenReturn(reservationView(InventoryReservationStatus.CANCELLED));

    mockMvc.perform(post("/api/v1/inventory-reservations/{id}/cancel", RESERVATION_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(RESERVATION_ID.toString()))
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  @DisplayName("18.5-API-012 P0 unknown reservation maps to 404 INVENTORY_RESERVATION_NOT_FOUND")
  void getNotFound() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.get(eq(user), eq(RESERVATION_ID)))
        .thenThrow(new InventoryReservationNotFoundException());

    mockMvc.perform(get("/api/v1/inventory-reservations/{id}", RESERVATION_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_RESERVATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.5-API-013 P0 list passes filters and returns the items envelope")
  void listReturnsRows() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.list(eq(user), eq(SPAREPART_ID), eq(LOCATION_ID),
        eq(InventoryReservationStatus.ACTIVE), eq("WORK_ORDER"), eq("WO-013")))
        .thenReturn(List.of(reservationView(InventoryReservationStatus.ACTIVE)));

    mockMvc.perform(get("/api/v1/inventory-reservations")
            .with(auth(user))
            .param("sparepartId", SPAREPART_ID.toString())
            .param("locationId", LOCATION_ID.toString())
            .param("status", "ACTIVE")
            .param("referenceType", "WORK_ORDER")
            .param("referenceId", "WO-013"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(RESERVATION_ID.toString()))
        .andExpect(jsonPath("$.items[0].status").value("ACTIVE"));
  }

  @Test
  @DisplayName("18.5-API-014 P0 invalid status filter maps to 400 INVALID_QUERY_VALUE")
  void listInvalidStatusFilter() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(get("/api/v1/inventory-reservations")
            .with(auth(user))
            .param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("18.5-API-015 P1 non-UUID reservation path maps to 400 INVALID_PATH_VALUE")
  void invalidPathValue() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(get("/api/v1/inventory-reservations/{id}", "not-a-uuid")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("18.5-API-016 P0 unauthenticated requests are rejected with 401")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/inventory-reservations"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  private static String createBody() {
    return "{\"sparepartId\":\"" + SPAREPART_ID + "\",\"locationId\":\"" + LOCATION_ID
        + "\",\"quantity\":4,\"referenceType\":\"WORK_ORDER\",\"referenceId\":\"WO-001\"}";
  }

  private static ReservationView reservationView(InventoryReservationStatus status) {
    return new ReservationView(RESERVATION_ID, SPAREPART_ID, LOCATION_ID, new BigDecimal("4"),
        new BigDecimal("4"), status, "WORK_ORDER", "WO-001", REQUESTER_ID,
        null, null, null, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}