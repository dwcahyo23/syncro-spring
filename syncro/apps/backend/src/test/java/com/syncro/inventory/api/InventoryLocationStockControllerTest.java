package com.syncro.inventory.api;

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
import com.syncro.inventory.application.InventoryStockService;
import com.syncro.inventory.application.InventoryStockService.InventoryStockForbiddenException;
import com.syncro.inventory.application.InventoryStockService.LocationUpsertCommand;
import com.syncro.inventory.application.InventoryStockService.NegativeStockRejectedException;
import com.syncro.inventory.application.InventoryStockService.SparepartNotFoundException;
import com.syncro.inventory.application.InventoryStockService.StockBalanceNotFoundException;
import com.syncro.inventory.domain.InventoryStockBalance;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Story 18-3: the location-scoped stock-balances API contract — status codes,
 * machine-readable error codes and the additive view fields (locationCode,
 * sparepartCode, sparepartName) on the shared SparepartStockView shape.
 */
@WebMvcTest(InventoryLocationStockController.class)
@Import({SecurityConfig.class, InventoryStockExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class InventoryLocationStockControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryStockService service;
  @MockitoBean
  private SparepartRepository spareparts;
  @MockitoBean
  private InventoryLocationRepository locations;
  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID PLANT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final UUID SPAREPART_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");
  private static final UUID LOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @BeforeEach
  void stubViewAssembly() {
    var sparepart = new SparepartEntity(SPAREPART_ID, "SP-0001", "Part", null, null, null, null,
        null, NOW, NOW);
    sparepart.updateProcurement("MC-0001", null, NOW);
    when(spareparts.findById(SPAREPART_ID)).thenReturn(Optional.of(sparepart));
    when(locations.findById(LOCATION_ID)).thenReturn(Optional.of(new InventoryLocationEntity(
        LOCATION_ID, PLANT_ID, "WS-B", "Workshop B", null, true, NOW, NOW)));
  }

  @Test
  @DisplayName("18.3-API-001 P0 list returns rows with the additive location/sparepart fields")
  void listReturnsRows() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.listAtLocation(eq(user), eq(LOCATION_ID))).thenReturn(List.of(balance()));

    mockMvc.perform(get("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].materialCode").value("MC-0001"))
        .andExpect(jsonPath("$.items[0].locationCode").value("WS-B"))
        .andExpect(jsonPath("$.items[0].sparepartCode").value("SP-0001"))
        .andExpect(jsonPath("$.items[0].sparepartName").value("Part"))
        .andExpect(jsonPath("$.items[0].plantId").value(PLANT_ID.toString()));
  }

  @Test
  @DisplayName("18.3-API-002 P0 create returns 201 with the created view")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.upsertAtLocation(eq(user), eq(LOCATION_ID), any(LocationUpsertCommand.class)))
        .thenReturn(balance());

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\",\"available\":10,\"reserved\":0,"
                + "\"consumed\":0,\"minimumStock\":5}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
        .andExpect(jsonPath("$.locationCode").value("WS-B"));
  }

  @Test
  @DisplayName("18.3-API-003 P0 blank material code maps to 400 VALIDATION_ERROR")
  void createBlankMaterialCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"\",\"available\":10,\"reserved\":0,\"consumed\":0,"
                + "\"minimumStock\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.materialCode").exists());
  }

  @Test
  @DisplayName("18.3-API-004 P0 wrong role maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new InventoryStockForbiddenException())
        .when(service).upsertAtLocation(eq(user), eq(LOCATION_ID), any(LocationUpsertCommand.class));

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\",\"available\":10,\"reserved\":0,"
                + "\"consumed\":0,\"minimumStock\":5}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("18.3-API-005 P0 unknown/foreign location maps to 404 INVENTORY_LOCATION_NOT_FOUND")
  void locationNotFound() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.listAtLocation(eq(user), eq(LOCATION_ID)))
        .thenThrow(new InventoryLocationNotFoundException());

    mockMvc.perform(get("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_LOCATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.3-API-010 P0 adjust happy-path returns 200 with the full view")
  void adjustReturnsView() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    when(service.adjustAtLocation(eq(user), eq(LOCATION_ID), eq("MC-0001"), any()))
        .thenReturn(balance());

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances/{materialCode}/adjust",
            LOCATION_ID, "MC-0001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delta\":-3}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materialCode").value("MC-0001"))
        .andExpect(jsonPath("$.locationId").value(LOCATION_ID.toString()))
        .andExpect(jsonPath("$.locationCode").value("WS-B"))
        .andExpect(jsonPath("$.sparepartCode").value("SP-0001"))
        .andExpect(jsonPath("$.sparepartName").value("Part"))
        .andExpect(jsonPath("$.plantId").value(PLANT_ID.toString()));
  }

  @Test
  @DisplayName("18.3-API-011 P0 missing required body field maps to 400 VALIDATION_ERROR")
  void createMissingFieldRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\",\"reserved\":0,\"consumed\":0,"
                + "\"minimumStock\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.available").exists());
  }

  @Test
  @DisplayName("18.3-API-006 P0 adjust underflow maps to 409 NEGATIVE_STOCK_REJECTED")
  void adjustNegativeRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    doThrow(new NegativeStockRejectedException())
        .when(service).adjustAtLocation(eq(user), eq(LOCATION_ID), eq("MC-0001"), any());

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances/{materialCode}/adjust",
            LOCATION_ID, "MC-0001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delta\":-5}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REJECTED"));
  }

  @Test
  @DisplayName("18.3-API-007 P0 adjust missing balance maps to 404 STOCK_NOT_FOUND")
  void adjustStockNotFound() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    doThrow(new StockBalanceNotFoundException())
        .when(service).adjustAtLocation(eq(user), eq(LOCATION_ID), eq("MC-0001"), any());

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances/{materialCode}/adjust",
            LOCATION_ID, "MC-0001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"delta\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.3-API-008 P0 unknown material maps to 404 SPAREPART_NOT_FOUND")
  void createUnknownSparepart() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    doThrow(new SparepartNotFoundException())
        .when(service).upsertAtLocation(eq(user), eq(LOCATION_ID), any(LocationUpsertCommand.class));

    mockMvc.perform(post("/api/v1/inventory-locations/{id}/stock-balances", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"UNKNOWN\",\"available\":10,\"reserved\":0,"
                + "\"consumed\":0,\"minimumStock\":5}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.3-API-009 P1 non-UUID location path maps to 400 INVALID_PATH_VALUE")
  void invalidPathValue() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);

    mockMvc.perform(get("/api/v1/inventory-locations/{id}/stock-balances", "not-a-uuid")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  private InventoryStockBalance balance() {
    return new InventoryStockBalance(UUID.randomUUID(), SPAREPART_ID, LOCATION_ID,
        new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5"),
        0L, NOW, NOW);
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
