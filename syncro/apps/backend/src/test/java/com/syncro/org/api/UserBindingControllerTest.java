package com.syncro.org.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
import com.syncro.org.application.UserBindingService;
import com.syncro.org.application.UserBindingService.UserBindingMutationForbiddenException;
import com.syncro.org.application.UserBindingService.UserNotFoundException;
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

@WebMvcTest(UserBindingController.class)
@Import({SecurityConfig.class, UserBindingExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class UserBindingControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserBindingService bindings;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("16-3-API-001 P0 unauthenticated cannot read bindings")
  void getBindingsRequiresAuth() throws Exception {
    mockMvc.perform(get("/api/v1/user-bindings/{id}", UUID.randomUUID()))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("16-3-API-002 P1 forbidden for TECHNICIAN")
  void mutateRejectedForTechnician() throws Exception {
    doThrow(new UserBindingMutationForbiddenException()).when(bindings).setJob(any(), any(), any());

    mockMvc.perform(put("/api/v1/user-bindings/{id}/job", UUID.randomUUID())
        .with(auth(user(ApplicationRole.TECHNICIAN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"jobTitleId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("16-3-API-003 P1 unknown user returns 404")
  void unknownUserReturns404() throws Exception {
    doThrow(new UserNotFoundException()).when(bindings).setJob(any(), any(), any());

    mockMvc.perform(put("/api/v1/user-bindings/{id}/job", UUID.randomUUID())
        .with(auth(user(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"jobTitleId\":\"" + UUID.randomUUID() + "\"}"))
        .andExpect(status().isNotFound());
  }

  @Test
  @DisplayName("16-3-API-004 P1 add role returns 200")
  void addRoleWorks() throws Exception {
    when(bindings.addRole(any(), any(), any(), anyBoolean())).thenReturn(
        new UserBindingService.UserBindingsView(null, List.of()));

    mockMvc.perform(post("/api/v1/user-bindings/{id}/roles", UUID.randomUUID())
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"systemRoleId\":\"" + UUID.randomUUID() + "\",\"isOverride\":true}"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("16-3-API-005 P1 remove role returns 204")
  void removeRoleReturns204() throws Exception {
    mockMvc.perform(delete("/api/v1/user-bindings/{id}/roles/{bindingId}", UUID.randomUUID(), UUID.randomUUID())
        .with(auth(user(ApplicationRole.MANAGER_MAINTENANCE))))
        .andExpect(status().isNoContent());
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}