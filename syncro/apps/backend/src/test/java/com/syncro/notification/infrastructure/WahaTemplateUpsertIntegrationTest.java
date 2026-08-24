package com.syncro.notification.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.TimeConfig;
import com.syncro.notification.application.WahaTemplateService;
import com.syncro.notification.domain.WahaTemplate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Integration tests for the atomic {@code WahaTemplateRepository#upsert} path (DW-77).
 *
 * <p>Uses {@code @DataJpaTest} (JPA slice) so Flyway applies V21/V22 which seed the default
 * {@code alert_notification} template — the upserts therefore exercise the ON CONFLICT update
 * branch. Two sequential upserts of the same key must leave exactly one row holding the later
 * body, proving the write is atomic at the DB instead of the old read-then-write race.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({WahaTemplateService.class, TimeConfig.class})
@Testcontainers
class WahaTemplateUpsertIntegrationTest {

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
  private WahaTemplateService service;

  @Autowired
  private JdbcTemplate jdbc;

  private static final AuthenticatedUser ACTOR =
      new AuthenticatedUser("user-1", "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

  @Test
  @DisplayName("DW-77: two upserts of the same template key leave one row with the later body")
  void twoUpsertsLeaveSingleRowWithLatestBody() {
    String bodyA = "PERINGATAN SPAREPART - {machineCode} - v1";
    String bodyB = "PERINGATAN SPAREPART - {machineCode} - v2";

    WahaTemplate first = service.upsertTemplate(bodyA, ACTOR);
    WahaTemplate second = service.upsertTemplate(bodyB, ACTOR);

    List<String> bodies = jdbc.query(
        "SELECT body FROM waha_templates WHERE template_key = ?",
        (rs, rowNum) -> rs.getString("body"),
        WahaTemplate.DEFAULT_KEY);

    assertThat(bodies).containsExactly(bodyB);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM waha_templates WHERE template_key = ?",
        Long.class, WahaTemplate.DEFAULT_KEY)).isEqualTo(1L);
    assertThat(first.body()).isEqualTo(bodyA);
    assertThat(second.body()).isEqualTo(bodyB);
  }
}
