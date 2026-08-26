package com.syncro.maintenance.api;

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
import com.syncro.maintenance.application.WorkOrderRatingService;
import com.syncro.maintenance.application.WorkOrderRatingService.CreateDimensionCommand;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionInUseException;
import com.syncro.maintenance.application.WorkOrderRatingService.DimensionNotFoundException;
import com.syncro.maintenance.application.WorkOrderRatingService.DuplicateDimensionCodeException;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingDimensionView;
import com.syncro.maintenance.application.WorkOrderRatingService.RatingForbiddenException;
import com.syncro.maintenance.application.WorkOrderRatingService.UpdateDimensionCommand;
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

@WebMvcTest(RatingDimensionController.class)
@Import({SecurityConfig.class, RatingDimensionExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class RatingDimensionControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private WorkOrderRatingService ratings;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("10.8-API-101 P0 list dimensions returns 200 for any authenticated user")
  void listReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(ratings.listDimensions()).thenReturn(List.of(
        new RatingDimensionView(UUID.randomUUID(), "SPEED", "Speed", 1)));

    mockMvc.perform(get("/api/v1/rating-dimensions").with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].code").value("SPEED"))
        .andExpect(jsonPath("$[0].label").value("Speed"));
  }

  @Test
  @DisplayName("10.8-API-102 P0 create dimension as SUPER_ADMIN returns 201")
  void createReturnsCreated() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(ratings.createDimension(eq(user), any(CreateDimensionCommand.class)))
        .thenReturn(new RatingDimensionView(UUID.randomUUID(), "SAFETY", "Safety", 4));

    mockMvc.perform(post("/api/v1/rating-dimensions")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"SAFETY\",\"label\":\"Safety\",\"sortOrder\":4}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code").value("SAFETY"))
        .andExpect(jsonPath("$.label").value("Safety"));
  }

  @Test
  @DisplayName("10.8-API-103 P0 create dimension as non-SUPER_ADMIN maps to 403 FORBIDDEN")
  void createForbidden() throws Exception {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    doThrow(new RatingForbiddenException()).when(ratings)
        .createDimension(eq(user), any(CreateDimensionCommand.class));

    mockMvc.perform(post("/api/v1/rating-dimensions")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"SAFETY\",\"label\":\"Safety\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("10.8-API-104 P0 create dimension with a duplicate code maps to 409 RATING_DIMENSION_CODE_EXISTS")
  void createDuplicateCode() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new DuplicateDimensionCodeException()).when(ratings)
        .createDimension(eq(user), any(CreateDimensionCommand.class));

    mockMvc.perform(post("/api/v1/rating-dimensions")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"SPEED\",\"label\":\"Speed\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RATING_DIMENSION_CODE_EXISTS"));
  }

  @Test
  @DisplayName("10.8-API-105 P0 create dimension with an invalid code maps to 400 VALIDATION_ERROR")
  void createInvalidCode() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(post("/api/v1/rating-dimensions")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"code\":\"bad code!\",\"label\":\"Bad\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.code").exists());
  }

  @Test
  @DisplayName("10.8-API-106 P0 update dimension as SUPER_ADMIN returns 200")
  void updateReturnsOk() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(ratings.updateDimension(eq(user), eq("SPEED"), any(UpdateDimensionCommand.class)))
        .thenReturn(new RatingDimensionView(UUID.randomUUID(), "SPEED", "Velocity", 2));

    mockMvc.perform(put("/api/v1/rating-dimensions/{code}", "SPEED")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"Velocity\",\"sortOrder\":2}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.label").value("Velocity"));
  }

  @Test
  @DisplayName("10.8-API-107 P0 update an unknown dimension maps to 404 RATING_DIMENSION_NOT_FOUND")
  void updateNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new DimensionNotFoundException()).when(ratings)
        .updateDimension(eq(user), eq("NOPE"), any(UpdateDimensionCommand.class));

    mockMvc.perform(put("/api/v1/rating-dimensions/{code}", "NOPE")
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"label\":\"Nope\",\"sortOrder\":1}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("RATING_DIMENSION_NOT_FOUND"));
  }

  @Test
  @DisplayName("10.8-API-108 P0 delete dimension as SUPER_ADMIN returns 204")
  void deleteReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(delete("/api/v1/rating-dimensions/{code}", "SPEED")
            .with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("10.8-API-109 P0 delete an in-use dimension maps to 400 RATING_DIMENSION_IN_USE")
  void deleteInUse() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new DimensionInUseException()).when(ratings).deleteDimension(eq(user), eq("SPEED"));

    mockMvc.perform(delete("/api/v1/rating-dimensions/{code}", "SPEED")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("RATING_DIMENSION_IN_USE"));
  }

  @Test
  @DisplayName("10.8-API-110 P0 delete dimension as a non-SUPER_ADMIN maps to 403 FORBIDDEN")
  void deleteForbidden() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new RatingForbiddenException()).when(ratings).deleteDimension(eq(user), eq("SPEED"));

    mockMvc.perform(delete("/api/v1/rating-dimensions/{code}", "SPEED")
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
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
