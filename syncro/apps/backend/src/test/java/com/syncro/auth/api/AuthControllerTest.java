package com.syncro.auth.api;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.api.AuthDtos.AuthUserView;
import com.syncro.auth.api.AuthDtos.LoginResponse;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.AuthService.BadCredentialsException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class AuthControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuthService authService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  void loginReturnsTokenAndUserWithoutSecrets() throws Exception {
    when(authService.login("admin@syncro.dev", "syncro-admin-dev"))
        .thenReturn(new LoginResponse(
            "Bearer",
            "token-value",
            1800,
            new AuthUserView("user-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN)));

    mockMvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"loginIdentifier\":\"admin@syncro.dev\",\"password\":\"syncro-admin-dev\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.accessToken").value("token-value"))
        .andExpect(jsonPath("$.user.applicationRole").value("SUPER_ADMIN"))
        .andExpect(jsonPath("$.passwordHash").doesNotExist());
  }

  @Test
  void loginFailureReturnsGenericSafeError() throws Exception {
    when(authService.login(anyString(), anyString())).thenThrow(new BadCredentialsException());

    mockMvc.perform(post("/api/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"loginIdentifier\":\"admin@syncro.dev\",\"password\":\"wrong\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"))
        .andExpect(jsonPath("$.message").value("Invalid login credentials."))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void currentUserRequiresAuthentication() throws Exception {
    mockMvc.perform(get("/api/v1/auth/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void currentUserReturnsAuthenticatedPrincipal() throws Exception {
    var user = new AuthenticatedUser("user-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
    when(authService.currentUser(user)).thenReturn(new AuthUserView("user-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN));

    mockMvc.perform(get("/api/v1/auth/me")
        .with(SecurityMockMvcRequestPostProcessors.authentication(
            new UsernamePasswordAuthenticationToken(user, null, user.applicationRole() == null ? null : java.util.List.of()))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.loginIdentifier").value("admin@syncro.dev"))
        .andExpect(jsonPath("$.applicationRole").value("SUPER_ADMIN"));
  }
}
