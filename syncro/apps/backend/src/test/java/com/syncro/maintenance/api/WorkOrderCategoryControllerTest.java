package com.syncro.maintenance.api;

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
import com.syncro.maintenance.application.WorkOrderCategoryService;
import com.syncro.maintenance.application.WorkOrderCategoryService.CreateWorkOrderCategoryCommand;
import com.syncro.maintenance.application.WorkOrderCategoryService.DuplicateWorkOrderCategoryCodeException;
import com.syncro.maintenance.application.WorkOrderCategoryService.UpdateWorkOrderCategoryCommand;
import com.syncro.maintenance.application.WorkOrderCategoryService.WorkOrderCategoryMutationForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrderCategory;
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

@WebMvcTest(WorkOrderCategoryController.class)
@Import({SecurityConfig.class, WorkOrderCategoryExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class WorkOrderCategoryControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WorkOrderCategoryService categories;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("10.1-API-001 P0 list categories returns 200 for any authenticated user")
  void listReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(categories.list()).thenReturn(List.of(new WorkOrderCategory("01", "Breakdown")));

    mockMvc.perform(get("/api/v1/work-order-categories").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("01"))
        .andExpect(jsonPath("$[0].label").value("Breakdown"));
  }

  @Test
  @DisplayName("10.1-API-002 P0 create category as SECTION_LEADER returns 201")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.create(eq(user), any(CreateWorkOrderCategoryCommand.class)))
        .thenReturn(new WorkOrderCategory("01", "Breakdown"));

    mockMvc.perform(post("/api/v1/work-order-categories")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"01\",\"label\":\"Breakdown\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("01"))
        .andExpect(jsonPath("$.label").value("Breakdown"));
  }

  @Test
  @DisplayName("10.1-API-003 P0 create category as TECHNICIAN returns 403 FORBIDDEN")
  void createAsTechnicianForbidden() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    doThrow(new WorkOrderCategoryMutationForbiddenException()).when(categories).create(eq(user), any());

    mockMvc.perform(post("/api/v1/work-order-categories")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"01\",\"label\":\"Breakdown\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("10.1-API-004 P0 update category with a duplicate code returns 409 DUPLICATE_CATEGORY_CODE")
  void updateDuplicateMapsToConflict() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    doThrow(new DuplicateWorkOrderCategoryCodeException())
        .when(categories).update(eq(user), eq("01"), any(UpdateWorkOrderCategoryCommand.class));

    mockMvc.perform(put("/api/v1/work-order-categories/{code}", "01")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"02\",\"label\":\"Preventive\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("DUPLICATE_CATEGORY_CODE"));
  }

  @Test
  @DisplayName("10.1-API-005 P1 update category as MAINTENANCE_LEADER returns 200")
  void updateReturnsOk() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    when(categories.update(eq(user), eq("01"), any(UpdateWorkOrderCategoryCommand.class)))
        .thenReturn(new WorkOrderCategory("02", "Preventive"));

    mockMvc.perform(put("/api/v1/work-order-categories/{code}", "01")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"02\",\"label\":\"Preventive\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("02"))
        .andExpect(jsonPath("$.label").value("Preventive"));
  }

  @Test
  @DisplayName("10.1-API-006 P1 invalid category request returns VALIDATION_ERROR")
  void invalidRequestReturnsValidationError() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);

    mockMvc.perform(post("/api/v1/work-order-categories")
        .with(auth(user))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"code\":\"\",\"label\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
  }

  @Test
  @DisplayName("10.1-API-007 P1 unauthenticated category requests are rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(get("/api/v1/work-order-categories"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
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
