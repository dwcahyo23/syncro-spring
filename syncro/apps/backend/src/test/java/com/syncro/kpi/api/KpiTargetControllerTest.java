package com.syncro.kpi.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.kpi.application.KpiQueryService;
import com.syncro.kpi.application.KpiTargetService;
import com.syncro.kpi.domain.KpiType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Story 20-2 review: pins the serialized verdict contract — the hand-written frontend
 * interfaces read {@code targetValue}/{@code targetStatus} from this JSON, and no other
 * test crosses the serialization boundary (unit tests use record accessors; the frontend
 * mocks the hook). Field-name drift must fail here (SparepartAlertControllerTest pattern).
 */
@WebMvcTest(KpiTargetController.class)
@Import({SecurityConfig.class, KpiExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class KpiTargetControllerTest {

  private static final LocalDate AUG = LocalDate.of(2026, 8, 1);

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private KpiTargetService targetService;

  @MockitoBean
  private KpiQueryService queryService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    var principal = new UsernamePasswordAuthenticationToken(user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name())));
    return SecurityMockMvcRequestPostProcessors.authentication(principal);
  }

  @Test
  @DisplayName("20.2-API-001 P0: materialized MTBF JSON carries targetValue/targetStatus contract fields")
  void materializedVerdictJsonContract() throws Exception {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    when(queryService.materialized(any(), eq(KpiType.MTBF), eq(AUG), eq(plantId)))
        .thenReturn(new KpiQueryService.MaterializedResponse("mtbf", AUG,
            KpiQueryService.STATUS_AVAILABLE,
            List.of(new KpiQueryService.MtbfRow(plantId, machineId, new BigDecimal("10.42"),
                new BigDecimal("8.00"), KpiQueryService.VERDICT_ON_TARGET)),
            List.of(), List.of(), List.of(), List.of(), List.of(), null, null));

    mockMvc.perform(get("/api/v1/kpi/materialized/mtbf")
            .param("month", AUG.toString())
            .param("plantId", plantId.toString())
            .with(auth(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.type").value("mtbf"))
        .andExpect(jsonPath("$.status").value("AVAILABLE"))
        .andExpect(jsonPath("$.mtbfRows[0].plantId").value(plantId.toString()))
        .andExpect(jsonPath("$.mtbfRows[0].mtbfDays").value(10.42))
        .andExpect(jsonPath("$.mtbfRows[0].targetValue").value(8.00))
        .andExpect(jsonPath("$.mtbfRows[0].targetStatus").value("ON_TARGET"));
  }

  @Test
  @DisplayName("20.2-API-002 P1: unknown type path segment maps to the stable 404 envelope")
  void unknownTypeStableEnvelope() throws Exception {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(get("/api/v1/kpi/materialized/nonsense")
            .param("month", AUG.toString())
            .with(auth(admin)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("KPI_TYPE_NOT_FOUND"))
        .andExpect(jsonPath("$.message").isString())
        .andExpect(jsonPath("$.timestamp").value(org.hamcrest.Matchers.matchesPattern(
            "\\d{4}-\\d{2}-\\d{2}T.*")))
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  @DisplayName("20.2-API-003 P1: out-of-range target body maps to VALIDATION_ERROR with fieldErrors")
  void targetValidationEnvelope() throws Exception {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .put("/api/v1/kpi/targets")
            .contentType("application/json")
            .content("""
                {"plantId":"%s","month":"2026-08-01","monthlyBreakdownTarget":5,
                 "mtbfTargetDays":null,"mttrTargetMinutes":null,
                 "oeeQualityPercent":150,"oeePerformancePercent":null}"""
                .formatted(UUID.randomUUID()))
            .with(auth(admin)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.oeeQualityPercent").isNotEmpty());
  }

  @Test
  @DisplayName("20.2-API-004 P1: refresh-log timestamp serializes as ISO instant")
  void refreshViewSerializes() throws Exception {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.test",
        ApplicationRole.SUPER_ADMIN);
    var plantId = UUID.randomUUID();
    when(queryService.materialized(any(), eq(KpiType.BREAKDOWN), eq(AUG), eq(plantId)))
        .thenReturn(new KpiQueryService.MaterializedResponse("breakdown", AUG,
            KpiQueryService.STATUS_AVAILABLE,
            List.of(), List.of(), List.of(), List.of(), List.of(),
            List.of(new KpiQueryService.BreakdownRow(plantId, 5, BigDecimal.valueOf(3),
                KpiQueryService.VERDICT_ABOVE_TARGET)),
            null, new KpiQueryService.RefreshView("breakdown:2026-08",
                com.syncro.kpi.domain.KpiAggregateRefreshStatus.SUCCESS,
                Instant.parse("2026-09-01T03:00:00Z"), "traceId=t rows=1")));

    mockMvc.perform(get("/api/v1/kpi/materialized/breakdown")
            .param("month", AUG.toString())
            .param("plantId", plantId.toString())
            .with(auth(admin)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.breakdownRows[0].targetStatus").value("ABOVE_TARGET"))
        .andExpect(jsonPath("$.refresh.status").value("SUCCESS"))
        .andExpect(jsonPath("$.refresh.refreshedAt").value("2026-09-01T03:00:00Z"));
  }
}