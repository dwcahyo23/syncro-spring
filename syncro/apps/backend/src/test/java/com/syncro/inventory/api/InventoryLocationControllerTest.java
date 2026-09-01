package com.syncro.inventory.api;

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
import com.syncro.inventory.application.InventoryLocationService;
import com.syncro.inventory.application.InventoryLocationService.CreateLocationCommand;
import com.syncro.inventory.application.InventoryLocationService.DuplicateLocationException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationForbiddenException;
import com.syncro.inventory.application.InventoryLocationService.InventoryLocationNotFoundException;
import com.syncro.inventory.application.InventoryLocationService.LocationView;
import com.syncro.inventory.application.InventoryLocationService.PlantNotFoundForLocationException;
import com.syncro.inventory.application.InventoryLocationService.UpdateLocationCommand;
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
 * Story 18-2: inventory-locations API contract — DTO shape (camelCase, isActive),
 * status codes and machine-readable error codes per the spec matrix.
 */
@WebMvcTest(InventoryLocationController.class)
@Import({SecurityConfig.class, InventoryLocationExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class InventoryLocationControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private InventoryLocationService service;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID PLANT_ID = UUID.fromString("88888888-8888-8888-8888-888888888888");
  private static final UUID LOCATION_ID = UUID.fromString("77777777-7777-7777-7777-777777777777");
  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Test
  @DisplayName("18.2-API-001 P0 create returns 201 with the camelCase view")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateLocationCommand.class))).thenReturn(locationView(true));

    mockMvc.perform(post("/api/v1/inventory-locations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"code\":\"WS-01\",\"name\":\"Workshop 01\","
                + "\"description\":\"Forming workshop\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(LOCATION_ID.toString()))
        .andExpect(jsonPath("$.plantId").value(PLANT_ID.toString()))
        .andExpect(jsonPath("$.code").value("WS-01"))
        .andExpect(jsonPath("$.name").value("Workshop 01"))
        .andExpect(jsonPath("$.isActive").value(true));
  }

  @Test
  @DisplayName("18.2-API-002 P0 blank code maps to 400 VALIDATION_ERROR with fieldErrors")
  void createBlankCode() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);

    mockMvc.perform(post("/api/v1/inventory-locations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"code\":\"\",\"name\":\"Workshop\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.code").exists());
  }

  @Test
  @DisplayName("18.2-API-003 P0 duplicate code maps to 409 DUPLICATE_LOCATION")
  void createDuplicate() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateLocationCommand.class)))
        .thenThrow(new DuplicateLocationException());

    mockMvc.perform(post("/api/v1/inventory-locations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"code\":\"WS-01\",\"name\":\"Workshop\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_LOCATION"));
  }

  @Test
  @DisplayName("18.2-API-004 P0 wrong role mutation maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.STOREKEEPER);
    doThrow(new InventoryLocationForbiddenException())
        .when(service).create(eq(user), any(CreateLocationCommand.class));

    mockMvc.perform(post("/api/v1/inventory-locations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"code\":\"WS-01\",\"name\":\"Workshop\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("18.2-API-005 P0 unknown plant on create maps to 404 PLANT_NOT_FOUND")
  void createUnknownPlant() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.create(eq(user), any(CreateLocationCommand.class)))
        .thenThrow(new PlantNotFoundForLocationException());

    mockMvc.perform(post("/api/v1/inventory-locations")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"plantId\":\"" + PLANT_ID + "\",\"code\":\"WS-01\",\"name\":\"Workshop\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PLANT_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.2-API-006 P0 deactivate-only PUT returns 200 with isActive=false")
  void updateDeactivates() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(service.update(eq(user), eq(LOCATION_ID), any(UpdateLocationCommand.class)))
        .thenReturn(locationView(false));

    mockMvc.perform(put("/api/v1/inventory-locations/{id}", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"isActive\":false}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.isActive").value(false))
        .andExpect(jsonPath("$.code").value("WS-01"));
  }

  @Test
  @DisplayName("18.2-API-007 P0 unknown id on PUT maps to 404 INVENTORY_LOCATION_NOT_FOUND")
  void updateNotFound() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new InventoryLocationNotFoundException())
        .when(service).update(eq(user), eq(LOCATION_ID), any(UpdateLocationCommand.class));

    mockMvc.perform(put("/api/v1/inventory-locations/{id}", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Renamed\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("INVENTORY_LOCATION_NOT_FOUND"));
  }

  @Test
  @DisplayName("18.2-API-008 P0 list passes plantId + activeOnly and returns the items envelope")
  void listReturnsRows() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(service.list(eq(user), eq(PLANT_ID), eq(true))).thenReturn(List.of(locationView(true)));

    mockMvc.perform(get("/api/v1/inventory-locations")
            .with(auth(user))
            .param("plantId", PLANT_ID.toString())
            .param("activeOnly", "true"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].code").value("WS-01"))
        .andExpect(jsonPath("$.items[0].isActive").value(true));
  }

  @Test
  @DisplayName("18.2-API-009 P0 list without plantId maps to 400 VALIDATION_ERROR")
  void listRequiresPlantId() throws Exception {
    var user = user(ApplicationRole.AUDITOR);

    mockMvc.perform(get("/api/v1/inventory-locations")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("18.2-API-010 P0 get by id returns the view")
  void getByIdReturnsView() throws Exception {
    var user = user(ApplicationRole.INVENTORY_MAINTENANCE);
    when(service.get(eq(user), eq(LOCATION_ID))).thenReturn(locationView(true));

    mockMvc.perform(get("/api/v1/inventory-locations/{id}", LOCATION_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(LOCATION_ID.toString()));
  }

  @Test
  @DisplayName("18.2-API-011 P0 unauthenticated requests are rejected with 401")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/inventory-locations").param("plantId", PLANT_ID.toString()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("18.2-API-012 P1 list without activeOnly defaults the filter to false")
  void listDefaultsActiveOnlyToFalse() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(service.list(eq(user), eq(PLANT_ID), eq(false))).thenReturn(List.of(locationView(true)));

    mockMvc.perform(get("/api/v1/inventory-locations")
            .with(auth(user))
            .param("plantId", PLANT_ID.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].isActive").value(true));

    org.mockito.Mockito.verify(service).list(user, PLANT_ID, false);
  }

  @Test
  @DisplayName("18.2-API-013 P0 PUT with blank code maps to 400 VALIDATION_ERROR")
  void updateBlankCode() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);

    mockMvc.perform(put("/api/v1/inventory-locations/{id}", LOCATION_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.code").exists());
  }

  private static LocationView locationView(boolean active) {
    return new LocationView(LOCATION_ID, PLANT_ID, "WS-01", "Workshop 01", "Forming workshop",
        active, NOW, NOW);
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
