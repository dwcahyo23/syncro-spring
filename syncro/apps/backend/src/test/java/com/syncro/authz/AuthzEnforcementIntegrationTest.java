package com.syncro.authz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.authz.infrastructure.OpaClient;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * End-to-end OPA enforcement (FR-160/FR-164): with {@code enforced-paths} populated and a
 * mocked {@link OpaClient}, requests to an enforced endpoint are decided before business
 * logic, every decision is persisted to {@code authz_decisions}, and a degraded (throwing)
 * OPA denies paths outside the allowlist.
 *
 * <p>Self-contained context (own {@code @SpringBootTest} + container): it opts into
 * enforcement via {@code SYNCRO_AUTHZ_ENFORCED_PATHS} while the shared test-context default
 * ships empty (enforced-paths stays empty for the rest of the suite).
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(properties = {
    "server.port=0",
    "spring.lifecycle.timeout-per-shutdown-phase=5s",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "GARAGE_HOST=localhost",
    "GARAGE_S3_PORT=3900",
    "GARAGE_ACCESS_KEY=test",
    "GARAGE_SECRET_KEY=test",
    "GARAGE_BUCKET=test",
    "GARAGE_REGION=garage",
    "OPA_HOST=localhost",
    "OPA_PORT=18181",
    "SYNCRO_OPA_URL=http://localhost:18181",
    "SYNCRO_AUTHZ_ENFORCED_PATHS=/api/v1/teams/**",
    "syncro.authz.enforced-paths=/api/v1/teams/**",
    "SYNCRO_AUTHZ_DEGRADED_ALLOWLIST=/api/v1/health,/actuator/**",
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password"
})
class AuthzEnforcementIntegrationTest {

  private static final String DECISION_ID_ALLOW = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
  private static final String DECISION_ID_DENY = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private JwtTokenService jwtTokenService;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @MockitoBean
  private OpaClient opaClient;

  @Test
  @DisplayName("9.5-ENF-001 P0 MANAGER_MAINTENANCE POST is allowed by OPA and the decision is persisted")
  void managerMutationAllowedAndPersisted() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200,
            "{\"decision_id\":\"" + DECISION_ID_ALLOW + "\",\"result\":true,\"revision\":\"sha256:policy\"}",
            DECISION_ID_ALLOW, true, "sha256:policy"));

    mockMvc.perform(post("/api/v1/teams")
            .header("Authorization", "Bearer " + jwtTokenService.createToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Night Shift IT", "expiresAt": "2030-01-01T00:00:00Z"}
                """))
        .andExpect(status().isCreated());

    var row = latestDecision(user.getId());
    assertThat(row).isNotNull();
    assertThat(row[0]).isEqualTo(DECISION_ID_ALLOW);
    assertThat(row[1]).isEqualTo("sha256:policy");
    assertThat(row[2]).isEqualTo("true");
    assertThat(row[3]).isEqualTo("false");
    assertThat(row[4]).isEqualTo(user.getId().toString());
    assertThat(row[5]).isEqualTo("POST /api/v1/teams");
    assertThat(row[6]).isEqualTo("endpoint");
  }

  @Test
  @DisplayName("9.5-ENF-002 P0 STAFF_MAINTENANCE POST is denied by OPA before business logic and persisted")
  void staffMutationDeniedBeforeBusinessLogic() throws Exception {
    var user = persistedUser(ApplicationRole.STAFF_MAINTENANCE);
    when(opaClient.post(eq("allow"), any()))
        .thenReturn(new OpaClient.Result(true, 200,
            "{\"decision_id\":\"" + DECISION_ID_DENY + "\",\"result\":false}",
            DECISION_ID_DENY, false));

    mockMvc.perform(post("/api/v1/teams")
            .header("Authorization", "Bearer " + jwtTokenService.createToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Blocked Team", "expiresAt": "2030-01-01T00:00:00Z"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(teamCount("Blocked Team")).isZero();
    var row = latestDecision(user.getId());
    assertThat(row).isNotNull();
    assertThat(row[0]).isEqualTo(DECISION_ID_DENY);
    assertThat(row[2]).isEqualTo("false");
    assertThat(row[5]).isEqualTo("POST /api/v1/teams");
  }

  @Test
  @DisplayName("9.5-ENF-003 P1 degraded OPA (throws) denies a non-allowlisted enforced path")
  void degradedOpaDeniesNonAllowlistedPath() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGER_MAINTENANCE);
    when(opaClient.post(eq("allow"), any())).thenThrow(new RuntimeException("sidecar down"));

    mockMvc.perform(post("/api/v1/teams")
            .header("Authorization", "Bearer " + jwtTokenService.createToken(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Degraded Team", "expiresAt": "2030-01-01T00:00:00Z"}
                """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    assertThat(teamCount("Degraded Team")).isZero();
    var row = latestDecision(user.getId());
    assertThat(row).isNotNull();
    assertThat(row[2]).isEqualTo("false");
  }

  private AuthUserEntity persistedUser(ApplicationRole role) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var login = role.name().toLowerCase(java.util.Locale.ROOT) + "-" + UUID.randomUUID() + "@syncro.dev";
    return users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), login,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
  }

  private long teamCount(String name) {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM teams WHERE name = ?", Long.class, name);
  }

  /** Returns [decision_id, policy_revision, allowed, degraded, subject_user_id, action, resource_type]. */
  private String[] latestDecision(UUID userId) {
    return jdbcTemplate.query(
        "SELECT decision_id::text, policy_revision, allowed::text, degraded::text, "
            + "subject_user_id::text, action, resource_type "
            + "FROM authz_decisions WHERE subject_user_id = ?::uuid AND action = 'POST /api/v1/teams' "
            + "ORDER BY decided_at DESC LIMIT 1",
        (rs, rowNum) -> new String[] {
            rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7)
        }, userId.toString()).stream().findFirst().orElse(null);
  }
}
