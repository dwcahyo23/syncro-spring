package com.syncro.sparepart.stock.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.sparepart.stock.application.SparepartStockService;
import com.syncro.sparepart.stock.application.SparepartStockService.AdjustStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.NegativeStockRejectedException;
import com.syncro.sparepart.stock.application.SparepartStockService.SparepartStockForbiddenException;
import com.syncro.sparepart.stock.application.SparepartStockService.SparepartStockNotFoundException;
import com.syncro.sparepart.stock.application.SparepartStockService.SparepartNotFoundException;
import com.syncro.sparepart.stock.application.SparepartStockService.UpdateStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.UpsertStockCommand;
import com.syncro.sparepart.stock.application.SparepartStockService.VersionConflictException;
import com.syncro.sparepart.stock.domain.SparepartStock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

@WebMvcTest({SparepartStockController.class})
@Import({SecurityConfig.class, SparepartStockExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SparepartStockControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartStockService service;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID PLANT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

  @Test
  @DisplayName("12.4-STK-API-001 P0 create stock returns 201 with the created view")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.upsert(eq(user), any(UpsertStockCommand.class))).thenReturn(stockDomain());

    mockMvc.perform(post("/api/v1/sparepart-stock")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\",\"plantId\":\"" + PLANT_ID
                + "\",\"stockOnHand\":10,\"orderPoint\":5,\"orderQty\":20}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.materialCode").value("MC-0001"))
        .andExpect(jsonPath("$.stockOnHand").value(10))
        .andExpect(jsonPath("$.reorderWarning").value(false));
  }

  @Test
  @DisplayName("12.4-STK-API-002 P0 create with blank material code maps to 400 VALIDATION_ERROR")
  void createBlankMaterialCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/sparepart-stock")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"\",\"plantId\":\"" + PLANT_ID
                + "\",\"stockOnHand\":10,\"orderPoint\":5,\"orderQty\":20}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.materialCode").exists());
  }

  @Test
  @DisplayName("12.4-STK-API-003 P0 forbidden stock mutation maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new SparepartStockForbiddenException())
        .when(service).upsert(eq(user), any(UpsertStockCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-stock")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"MC-0001\",\"plantId\":\"" + PLANT_ID
                + "\",\"stockOnHand\":10,\"orderPoint\":5,\"orderQty\":20}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("12.4-STK-API-004 P0 unknown sparepart maps to 404 SPAREPART_NOT_FOUND")
  void createUnknownSparepart() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    doThrow(new SparepartNotFoundException())
        .when(service).upsert(eq(user), any(UpsertStockCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-stock")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materialCode\":\"UNKNOWN-1\",\"plantId\":\"" + PLANT_ID
                + "\",\"stockOnHand\":10,\"orderPoint\":5,\"orderQty\":20}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.4-STK-API-005 P0 update with stale version maps to 409 VERSION_CONFLICT")
  void updateVersionConflict() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    doThrow(new VersionConflictException())
        .when(service).update(eq(user), eq("MC-0001"), any(UpdateStockCommand.class));

    mockMvc.perform(put("/api/v1/sparepart-stock/MC-0001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"version\":3,\"stockOnHand\":8}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
  }

  @Test
  @DisplayName("12.4-STK-API-006 P0 update missing row maps to 404 STOCK_NOT_FOUND")
  void updateNotFound() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    doThrow(new SparepartStockNotFoundException())
        .when(service).update(eq(user), eq("MC-0001"), any(UpdateStockCommand.class));

    mockMvc.perform(put("/api/v1/sparepart-stock/MC-0001")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"version\":0,\"stockOnHand\":8}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("STOCK_NOT_FOUND"));
  }

  @Test
  @DisplayName("12.4-STK-API-007 P0 adjust returning NEGATIVE_STOCK maps to 409 NEGATIVE_STOCK_REJECTED")
  void adjustNegativeRejected() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    doThrow(new NegativeStockRejectedException())
        .when(service).adjust(eq(user), eq("MC-0001"), any(AdjustStockCommand.class));

    mockMvc.perform(post("/api/v1/sparepart-stock/MC-0001/adjust")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"delta\":-5}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("NEGATIVE_STOCK_REJECTED"));
  }

  @Test
  @DisplayName("12.4-STK-API-008 P0 list returns the stock view list")
  void listReturnsRows() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(service.list(eq(user), eq(PLANT_ID))).thenReturn(List.of(stockDomain()));

    mockMvc.perform(get("/api/v1/sparepart-stock")
            .with(auth(user))
            .param("plantId", PLANT_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].materialCode").value("MC-0001"))
        .andExpect(jsonPath("$.items[0].stockOnHand").value(10));
  }

  @Test
  @DisplayName("12.4-STK-API-009 P0 reorder-warnings returns only warning rows with recommendation")
  void reorderWarningsListed() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.reorderWarnings(eq(user), eq(PLANT_ID))).thenReturn(List.of(stockDomain()));

    mockMvc.perform(get("/api/v1/sparepart-stock/reorder-warnings")
            .with(auth(user))
            .param("plantId", PLANT_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].reorderWarning").value(false));
  }

  private static SparepartStock stockDomain() {
    return new SparepartStock("MC-0001", PLANT_ID, new BigDecimal("10"), new BigDecimal("5"),
        new BigDecimal("20"), 0L, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}
