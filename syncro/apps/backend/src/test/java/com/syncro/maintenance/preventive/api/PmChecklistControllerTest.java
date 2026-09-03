package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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
import com.syncro.maintenance.preventive.application.PmChecklistService;
import com.syncro.maintenance.preventive.application.PmChecklistService.CategoryView;
import com.syncro.maintenance.preventive.application.PmChecklistService.ItemView;
import com.syncro.maintenance.preventive.application.PmChecksheetService;
import com.syncro.maintenance.preventive.domain.PmItemInputType;
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
 * Story 19-2 MockMvc contract test: DTO shape, status codes, and every new
 * exception→HTTP mapping for the pm-checklist-categories / pm-checklist-items
 * surface (PmChecksheetControllerTest error-path pattern).
 */
@WebMvcTest({PmChecklistCategoryController.class, PmChecklistItemController.class})
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PmChecklistControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PmChecklistService checklist;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID CS_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID CAT_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
  private static final UUID ITEM_ID = UUID.fromString("ffffffff-eeee-dddd-cccc-bbbbbbbbbbbb");
  private static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

  // -------------------------------------------------------------------------
  // Categories
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-API-001 P0 create category returns 201 with view shape")
  void createCategory() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createCategory(eq(user), any())).thenReturn(categoryView());

    mockMvc.perform(post("/api/v1/pm-checklist-categories")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"name\":\"Lubrication\",\"sortOrder\":1}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(CAT_ID.toString()))
        .andExpect(jsonPath("$.checksheetId").value(CS_ID.toString()))
        .andExpect(jsonPath("$.name").value("Lubrication"))
        .andExpect(jsonPath("$.sortOrder").value(1));
  }

  @Test
  @DisplayName("19.2-API-002 P0 create category missing checksheetId → 400 VALIDATION_ERROR")
  void createCategoryMissingChecksheetId() throws Exception {
    mockMvc.perform(post("/api/v1/pm-checklist-categories")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Lubrication\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.checksheetId").exists());
  }

  @Test
  @DisplayName("19.2-API-003 P0 list categories returns 200")
  void listCategories() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checklist.listCategories(eq(user), eq(CS_ID))).thenReturn(List.of(categoryView()));

    mockMvc.perform(get("/api/v1/pm-checklist-categories")
            .param("checksheetId", CS_ID.toString())
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Lubrication"));
  }

  @Test
  @DisplayName("19.2-API-004 P0 update category returns 200")
  void updateCategory() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checklist.updateCategory(eq(user), eq(CAT_ID), any())).thenReturn(categoryView());

    mockMvc.perform(put("/api/v1/pm-checklist-categories/{id}", CAT_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"Lubrication\",\"sortOrder\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Lubrication"));
  }

  @Test
  @DisplayName("19.2-API-005 P0 delete category returns 204")
  void deleteCategory() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    mockMvc.perform(delete("/api/v1/pm-checklist-categories/{id}", CAT_ID)
            .with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("19.2-API-006 P0 unknown category → 404 PM_CHECKLIST_CATEGORY_NOT_FOUND")
  void updateCategoryNotFound() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checklist.updateCategory(eq(user), eq(CAT_ID), any()))
        .thenThrow(new PmChecklistService.PmChecklistCategoryNotFoundException());

    mockMvc.perform(put("/api/v1/pm-checklist-categories/{id}", CAT_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"X\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKLIST_CATEGORY_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.2-API-007 P0 unknown checksheet on create → 404 PM_CHECKSHEET_NOT_FOUND")
  void createCategoryChecksheetNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createCategory(eq(user), any()))
        .thenThrow(new PmChecksheetService.PmChecksheetNotFoundException());

    mockMvc.perform(post("/api/v1/pm-checklist-categories")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"name\":\"X\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKSHEET_NOT_FOUND"));
  }

  // -------------------------------------------------------------------------
  // Items
  // -------------------------------------------------------------------------

  @Test
  @DisplayName("19.2-API-008 P0 create MEASUREMENT item returns 201 with bounds as decimals")
  void createItem() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createItem(eq(user), any())).thenReturn(itemView());

    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"categoryId\":\"" + CAT_ID
                + "\",\"parameterText\":\"Bearing temperature\",\"inputType\":\"MEASUREMENT\","
                + "\"unit\":\"C\",\"lsl\":10,\"nominal\":55,\"usl\":100,\"isCriticalFlag\":true}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(ITEM_ID.toString()))
        .andExpect(jsonPath("$.sequence").value(1))
        .andExpect(jsonPath("$.inputType").value("MEASUREMENT"))
        .andExpect(jsonPath("$.lsl").value(10))
        .andExpect(jsonPath("$.usl").value(100))
        .andExpect(jsonPath("$.isCriticalFlag").value(true));
  }

  @Test
  @DisplayName("19.2-API-009 P0 create item missing parameterText → 400 VALIDATION_ERROR")
  void createItemMissingParameterText() throws Exception {
    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"inputType\":\"OK_NG\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.parameterText").exists());
  }

  @Test
  @DisplayName("19.2-API-010 P0 unknown inputType enum → 400 VALIDATION_ERROR on inputType")
  void createItemUnknownInputType() throws Exception {
    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(ApplicationRole.STAFF_MAINTENANCE))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"parameterText\":\"X\","
                + "\"inputType\":\"BOGUS\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.inputType").exists());
  }

  @Test
  @DisplayName("19.2-API-011 P0 lsl > usl → 400 VALIDATION_ERROR with fieldErrors")
  void createItemInvertedBounds() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createItem(eq(user), any())).thenThrow(
        new PmChecklistService.ChecklistValidationException(
            Map.of("lsl", "lsl must be less than or equal to usl.")));

    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"parameterText\":\"X\","
                + "\"inputType\":\"MEASUREMENT\",\"lsl\":10,\"usl\":5}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.lsl").exists());
  }

  @Test
  @DisplayName("19.2-API-012 P0 foreign category → 400 VALIDATION_ERROR with fieldErrors")
  void createItemForeignCategory() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createItem(eq(user), any())).thenThrow(
        new PmChecklistService.ChecklistValidationException(
            Map.of("categoryId", "Category belongs to a different checksheet.")));

    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"categoryId\":\"" + CAT_ID
                + "\",\"parameterText\":\"X\",\"inputType\":\"OK_NG\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.categoryId").exists());
  }

  @Test
  @DisplayName("19.2-API-013 P0 unknown calibration instrument → 404 CALIBRATION_INSTRUMENT_NOT_FOUND")
  void createItemCalibrationNotFound() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createItem(eq(user), any()))
        .thenThrow(new PmChecklistService.CalibrationInstrumentNotFoundException());

    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"parameterText\":\"X\","
                + "\"inputType\":\"OK_NG\",\"calibrationInstrumentId\":\"" + CAT_ID + "\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CALIBRATION_INSTRUMENT_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.2-API-014 P0 mutation on approved revision → 409 INVALID_CHECKSHEET_TRANSITION")
  void createItemApprovedChecksheet() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklist.createItem(eq(user), any()))
        .thenThrow(new PmChecksheetService.InvalidChecksheetTransitionException());

    mockMvc.perform(post("/api/v1/pm-checklist-items")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"parameterText\":\"X\","
                + "\"inputType\":\"OK_NG\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_CHECKSHEET_TRANSITION"));
  }

  @Test
  @DisplayName("19.2-API-015 P0 list items with categoryId filter returns 200")
  void listItems() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checklist.listItems(eq(user), eq(CS_ID), eq(CAT_ID))).thenReturn(List.of(itemView()));

    mockMvc.perform(get("/api/v1/pm-checklist-items")
            .param("checksheetId", CS_ID.toString())
            .param("categoryId", CAT_ID.toString())
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].parameterText").value("Bearing temperature"));
  }

  @Test
  @DisplayName("19.2-API-016 P0 get item returns 200")
  void getItem() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checklist.getItem(eq(user), eq(ITEM_ID))).thenReturn(itemView());

    mockMvc.perform(get("/api/v1/pm-checklist-items/{id}", ITEM_ID)
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(ITEM_ID.toString()));
  }

  @Test
  @DisplayName("19.2-API-017 P0 update item returns 200")
  void updateItem() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checklist.updateItem(eq(user), eq(ITEM_ID), any())).thenReturn(itemView());

    mockMvc.perform(put("/api/v1/pm-checklist-items/{id}", ITEM_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"parameterText\":\"Bearing temperature\",\"inputType\":\"MEASUREMENT\","
                + "\"lsl\":10,\"usl\":100}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.inputType").value("MEASUREMENT"));
  }

  @Test
  @DisplayName("19.2-API-018 P0 delete item returns 204")
  void deleteItem() throws Exception {
    mockMvc.perform(delete("/api/v1/pm-checklist-items/{id}", ITEM_ID)
            .with(auth(ApplicationRole.SECTION_LEADER)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("19.2-API-019 P0 unknown item → 404 PM_CHECKLIST_ITEM_NOT_FOUND")
  void getItemNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(checklist.getItem(eq(user), eq(ITEM_ID)))
        .thenThrow(new PmChecklistService.PmChecklistItemNotFoundException());

    mockMvc.perform(get("/api/v1/pm-checklist-items/{id}", ITEM_ID)
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("PM_CHECKLIST_ITEM_NOT_FOUND"));
  }

  @Test
  @DisplayName("19.2-API-020 P0 forbidden role maps to 403 FORBIDDEN")
  void createCategoryForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(checklist.createCategory(eq(user), any()))
        .thenThrow(new PmChecksheetService.PmChecksheetForbiddenException());

    mockMvc.perform(post("/api/v1/pm-checklist-categories")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"checksheetId\":\"" + CS_ID + "\",\"name\":\"X\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("19.2-API-021 P0 missing checksheetId query param maps to 400 VALIDATION_ERROR")
  void listCategoriesMissingParameter() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checklist-categories")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.checksheetId").exists());
  }

  @Test
  @DisplayName("19.2-API-022 P0 malformed UUID query param maps to 400 INVALID_QUERY_VALUE")
  void listItemsMalformedQueryValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checklist-items")
            .param("checksheetId", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_QUERY_VALUE"));
  }

  @Test
  @DisplayName("19.2-API-023 P0 malformed UUID path value maps to 400 INVALID_PATH_VALUE")
  void getItemMalformedPathValue() throws Exception {
    mockMvc.perform(get("/api/v1/pm-checklist-items/{id}", "not-a-uuid")
            .with(auth(ApplicationRole.AUDITOR)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_PATH_VALUE"));
  }

  @Test
  @DisplayName("19.2-API-024 P0 delete category on approved revision → 409")
  void deleteCategoryApproved() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new PmChecksheetService.InvalidChecksheetTransitionException())
        .when(checklist).deleteCategory(eq(user), eq(CAT_ID));

    mockMvc.perform(delete("/api/v1/pm-checklist-categories/{id}", CAT_ID)
            .with(auth(user)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_CHECKSHEET_TRANSITION"));
  }

  private static CategoryView categoryView() {
    return new CategoryView(CAT_ID, CS_ID, "Lubrication", 1, NOW, NOW);
  }

  private static ItemView itemView() {
    return new ItemView(ITEM_ID, CS_ID, CAT_ID, 1, "Bearing temperature", null,
        PmItemInputType.MEASUREMENT, "C", new BigDecimal("10"), new BigDecimal("55"),
        new BigDecimal("100"), true, null, null, NOW, NOW);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }

  private static RequestPostProcessor auth(ApplicationRole role) {
    return auth(user(role));
  }
}
