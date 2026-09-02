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
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryTransferService;
import com.syncro.inventory.application.InventoryTransferService.CreateTransferCommand;
import com.syncro.inventory.application.InventoryTransferService.InsufficientStockException;
import com.syncro.inventory.application.InventoryTransferService.InvalidTransferTransitionException;
import com.syncro.inventory.application.InventoryTransferService.InventoryTransferNotFoundException;
import com.syncro.inventory.application.InventoryTransferService.TransferForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferSelfReviewForbiddenException;
import com.syncro.inventory.application.InventoryTransferService.TransferValidationException;
import com.syncro.inventory.application.InventoryTransferService.TransferView;
import com.syncro.inventory.domain.InventoryTransferStatus;
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
 * Story 18-4: the inventory-transfers API contract — DTO shape (camelCase, UUID
 * opaque), status codes and machine-readable error codes per the spec matrix. The
 * service is mocked; lifecycle/SoD/atomicity behavior is proven in
 * {@code InventoryTransferServiceIntegrationTest}.
 */
@WebMvcTest(InventoryTransferController.class)
@Import({SecurityConfig.class, InventoryTransferExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class InventoryTransferControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryTransferService service;
  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID TRANSFER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SPAREPART_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final UUID SOURCE_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  private static final UUID DEST_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
  private static final UUID REQUESTER_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  @DisplayName("18.4-API-001 P0 create returns 201 with the camelCase view")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.create(eq(user), any(CreateTransferCommand.class)))
        .thenReturn(transferView(InventoryTransferStatus.PENDING_APPROVAL));

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.sparepartId").value(SPAREPART_ID.toString()))
        .andExpect(jsonPath("$.sourceLocationId").value(SOURCE_ID.toString()))
        .andExpect(jsonPath("$.destinationLocationId").value(DEST_ID.toString()))
        .andExpect(jsonPath("$.quantity").value(5))
        .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
        .andExpect(jsonPath("$.requestedBy").value(REQUESTER_ID.toString()))
        .andExpect(jsonPath("$.reviewedBy").value(nullValue()))
        .andExpect(jsonPath("$.rejectionReason").value(nullValue()));
  }

  @Test
  @DisplayName("18.4-API-002 P0 missing body field maps to 400 VALIDATION_ERROR")
  void createMissingFieldRejected() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sparepartId\":\"" + SPAREPART_ID + "\",\"sourceLocationId\":\""
                + SOURCE_ID + "\",\"quantity\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.destinationLocationId").exists());
  }

  @Test
  @DisplayName("18.4-API-003 P0 quantity <= 0 maps to 400 VALIDATION_ERROR")
  void createNonPositiveQuantityRejected() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sparepartId\":\"" + SPAREPART_ID + "\",\"sourceLocationId\":\""
                + SOURCE_ID + "\",\"destinationLocationId\":\"" + DEST_ID + "\",\"quantity\":0}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.quantity").exists());
  }

  @Test
  @DisplayName("18.4-API-004 P0 cross-plant/same-location/qty business validation maps to 400")
  void createBusinessValidationRejected() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.create(eq(user), any(CreateTransferCommand.class)))
        .thenThrow(new TransferValidationException(Map.of("destinationLocationId",
            "Source and destination locations must belong to the same plant.")));

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.destinationLocationId").exists());
  }

  @Test
  @DisplayName("18.4-API-005 P0 unknown sparepart maps to 404 SPAREPART_NOT_FOUND")
  void createUnknownSparepart() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.create(eq(user), any(CreateTransferCommand.class)))
        .thenThrow(new SparepartNotFoundException());

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.4-API-006 P0 unknown/inactive location maps to 404 INVENTORY_LOCATION_NOT_FOUND")
  void createUnknownLocation() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.create(eq(user), any(CreateTransferCommand.class)))
        .thenThrow(new InventoryLocationNotFoundException());

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_LOCATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.4-API-007 P0 wrong role maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(service.create(eq(user), any(CreateTransferCommand.class)))
        .thenThrow(new TransferForbiddenException());

    mockMvc.perform(post("/api/v1/inventory-transfers")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createBody()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("18.4-API-008 P0 approve returns 200 with the APPROVED view")
  void approveReturnsView() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(service.approve(eq(user), eq(TRANSFER_ID)))
        .thenReturn(transferView(InventoryTransferStatus.APPROVED));

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/approve", TRANSFER_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.status").value("APPROVED"));
  }

  @Test
  @DisplayName("18.4-API-009 P0 self-review maps to 403 TRANSFER_SELF_REVIEW_FORBIDDEN")
  void approveSelfReviewForbidden() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(service.approve(eq(user), eq(TRANSFER_ID)))
        .thenThrow(new TransferSelfReviewForbiddenException());

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/approve", TRANSFER_ID)
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("TRANSFER_SELF_REVIEW_FORBIDDEN"));
  }

  @Test
  @DisplayName("18.4-API-010 P0 double review maps to 409 INVALID_TRANSFER_TRANSITION")
  void approveInvalidTransition() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.approve(eq(user), eq(TRANSFER_ID)))
        .thenThrow(new InvalidTransferTransitionException());

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/approve", TRANSFER_ID)
            .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSFER_TRANSITION"));
  }

  @Test
  @DisplayName("18.4-API-011 P0 insufficient source stock maps to 409 INSUFFICIENT_STOCK")
  void approveInsufficientStock() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.approve(eq(user), eq(TRANSFER_ID)))
        .thenThrow(new InsufficientStockException());

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/approve", TRANSFER_ID)
            .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
  }

  @Test
  @DisplayName("18.4-API-012 P0 reject returns 200 with the REJECTED view")
  void rejectReturnsView() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(service.reject(eq(user), eq(TRANSFER_ID), eq("Damaged in transit")))
        .thenReturn(transferView(InventoryTransferStatus.REJECTED));

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/reject", TRANSFER_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rejectionReason\":\"Damaged in transit\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REJECTED"))
        .andExpect(jsonPath("$.rejectionReason").value("Damaged in transit"));
  }

  @Test
  @DisplayName("18.4-API-013 P0 blank rejection reason maps to 400 VALIDATION_ERROR")
  void rejectBlankReason() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-transfers/{id}/reject", TRANSFER_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"rejectionReason\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.rejectionReason").exists());
  }

  @Test
  @DisplayName("18.4-API-014 P0 unknown transfer maps to 404 INVENTORY_TRANSFER_NOT_FOUND")
  void getNotFound() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.get(eq(user), eq(TRANSFER_ID)))
        .thenThrow(new InventoryTransferNotFoundException());

    mockMvc.perform(get("/api/v1/inventory-transfers/{id}", TRANSFER_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_TRANSFER_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.4-API-015 P0 list passes sparepartId/status filters and returns the items envelope")
  void listReturnsRows() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.list(eq(user), eq(SPAREPART_ID), eq(InventoryTransferStatus.PENDING_APPROVAL)))
        .thenReturn(List.of(transferView(InventoryTransferStatus.PENDING_APPROVAL)));

    mockMvc.perform(get("/api/v1/inventory-transfers")
            .with(auth(user))
            .param("sparepartId", SPAREPART_ID.toString())
            .param("status", "PENDING_APPROVAL"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].id").value(TRANSFER_ID.toString()))
        .andExpect(jsonPath("$.items[0].status").value("PENDING_APPROVAL"));
  }

  @Test
  @DisplayName("18.4-API-016 P0 invalid status filter maps to 400 INVALID_QUERY_VALUE")
  void listInvalidStatusFilter() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);

    mockMvc.perform(get("/api/v1/inventory-transfers")
            .with(auth(user))
            .param("status", "NOT_A_STATUS"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("18.4-API-017 P1 non-UUID transfer path maps to 400 INVALID_PATH_VALUE")
  void invalidPathValue() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);

    mockMvc.perform(get("/api/v1/inventory-transfers/{id}", "not-a-uuid")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("18.4-API-018 P0 unauthenticated requests are rejected with 401")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/inventory-transfers"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  private static String createBody() {
    return "{\"sparepartId\":\"" + SPAREPART_ID + "\",\"sourceLocationId\":\"" + SOURCE_ID
        + "\",\"destinationLocationId\":\"" + DEST_ID + "\",\"quantity\":5}";
  }

  private static TransferView transferView(InventoryTransferStatus status) {
    return new TransferView(TRANSFER_ID, SPAREPART_ID, SOURCE_ID, DEST_ID, new BigDecimal("5"),
        status, REQUESTER_ID,
        status == InventoryTransferStatus.PENDING_APPROVAL ? null : REQUESTER_ID,
        status == InventoryTransferStatus.REJECTED ? "Damaged in transit" : null,
        status == InventoryTransferStatus.PENDING_APPROVAL ? null : NOW, NOW, NOW);
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
