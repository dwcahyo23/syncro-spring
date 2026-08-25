package com.syncro.machine.api;

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
import com.syncro.auth.application.PlantScopeService.PlantAccessDeniedException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.machine.application.MachineResponsibilityService;
import java.time.Instant;
import java.time.LocalDate;
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

@WebMvcTest(MachineResponsibilityController.class)
@Import({SecurityConfig.class, MachineExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class, TestJsonConfig.class})
class MachineResponsibilityControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineResponsibilityService responsibilityService;

    @MockitoBean
    private JwtTokenService jwtTokenService;

    private static AuthenticatedUser user(ApplicationRole role) {
        return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
    }

    private static RequestPostProcessor auth(AuthenticatedUser user) {
        return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
            user,
            null,
            List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
    }

    @Test
    void shouldAssignResponsibility() throws Exception {
        String requestJson = """
            {
                "machineId": "00000000-0000-0000-0000-000000000001",
                "userId": "00000000-0000-0000-0000-000000000002",
                "level": "TECHNICIAN"
            }
            """;
            
        var response = new com.syncro.machine.api.MachineResponsibilityDtos.MachineResponsibilityResponse(
            UUID.randomUUID(), UUID.fromString("00000000-0000-0000-0000-000000000001"), UUID.fromString("00000000-0000-0000-0000-000000000002"),
            "tech", com.syncro.machine.domain.ResponsibilityLevel.TECHNICIAN, Instant.now(), Instant.now()
        );
        when(responsibilityService.assign(any(), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/machine-responsibilities")
                .with(auth(user(ApplicationRole.SUPER_ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.level").value("TECHNICIAN"));
    }

    @Test
    void shouldReturn400ForDuplicateAssignment() throws Exception {
        String requestJson = """
            {
                "machineId": "00000000-0000-0000-0000-000000000001",
                "userId": "00000000-0000-0000-0000-000000000002",
                "level": "TECHNICIAN"
            }
            """;
        doThrow(new MachineResponsibilityService.DuplicateResponsibilityException())
            .when(responsibilityService).assign(any(), any());

        mockMvc.perform(post("/api/v1/machine-responsibilities")
                .with(auth(user(ApplicationRole.SUPER_ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESPONSIBILITY"))
                .andExpect(jsonPath("$.message").value("User is already assigned to this machine."));
    }

    @Test
    void shouldReturn403ForViewer() throws Exception {
        String validJson = """
            {
                "machineId": "00000000-0000-0000-0000-000000000001",
                "userId": "00000000-0000-0000-0000-000000000002",
                "level": "TECHNICIAN"
            }
            """;
        
        when(responsibilityService.assign(any(), any()))
            .thenThrow(new com.syncro.machine.application.MachineService.MachineMutationForbiddenException());

        mockMvc.perform(post("/api/v1/machine-responsibilities")
                .with(auth(user(ApplicationRole.AUDITOR)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(validJson)) 
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldEnforceEnumValues() throws Exception {
        String requestJson = """
            {
                "machineId": "00000000-0000-0000-0000-000000000001",
                "userId": "00000000-0000-0000-0000-000000000002",
                "level": "INVALID_LEVEL"
            }
            """;

        mockMvc.perform(post("/api/v1/machine-responsibilities")
                .with(auth(user(ApplicationRole.SUPER_ADMIN)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors").exists());
    }
}
