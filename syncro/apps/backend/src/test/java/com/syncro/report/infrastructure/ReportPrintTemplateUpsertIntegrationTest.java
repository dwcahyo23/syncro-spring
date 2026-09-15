package com.syncro.report.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.config.TimeConfig;
import com.syncro.report.application.ReportPrintTemplateService;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateForbiddenException;
import com.syncro.report.application.ReportPrintTemplateService.ReportPrintTemplateTooLargeException;
import com.syncro.report.domain.ReportPrintTemplate;
import com.syncro.report.domain.ReportPrintTemplateType;
import java.util.List;
import java.util.UUID;
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
 * Integration tests for {@code ReportPrintTemplateRepository#upsert} on a real
 * PostgreSQL (same DW-77 ON CONFLICT recipe as waha_templates). Proves the
 * frozen-matrix rows: atomic upsert (two writes of the same type leave ONE row,
 * never a duplicate), per-type independence (WORK_ORDER vs PREVENTIVE never
 * clobber each other), the 200k body guard, the server-side role gate, and the
 * updated_by audit trail (FK to auth_users, so a real actor row is seeded).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ReportPrintTemplateService.class, TimeConfig.class})
@Testcontainers
class ReportPrintTemplateUpsertIntegrationTest {

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

  private static final UUID ACTOR_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Autowired
  private ReportPrintTemplateService service;

  @Autowired
  private JdbcTemplate jdbc;

  private void seedActor() {
    jdbc.update("""
        INSERT INTO auth_users (id, login_identifier, password_hash, application_role)
        VALUES (?, 'report-template-actor@syncro.dev', 'not-a-real-hash', 'SUPER_ADMIN')
        ON CONFLICT (id) DO NOTHING
        """, ACTOR_ID);
  }

  private static AuthenticatedUser actor(ApplicationRole role) {
    return new AuthenticatedUser(ACTOR_ID.toString(), "report-template-actor@syncro.dev", role);
  }

  @Test
  @DisplayName("Upsert matrix: two writes of the same type leave one row with the latest body")
  void twoUpsertsOfSameTypeLeaveSingleRowWithLatestBody() {
    seedActor();
    String first = "<p>Layout v1 {{nomor}}</p>";
    String second = "<p>Layout v2 {{nomor}}</p>";

    ReportPrintTemplate savedFirst =
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, first, actor(ApplicationRole.SUPER_ADMIN));
    ReportPrintTemplate savedSecond =
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, second, actor(ApplicationRole.SUPER_ADMIN));

    List<String> bodies = jdbc.query(
        "SELECT body_html FROM report_print_templates WHERE report_type = ?",
        (rs, rowNum) -> rs.getString("body_html"),
        "WORK_ORDER");

    assertThat(bodies).containsExactly(second);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM report_print_templates WHERE report_type = ?",
        Long.class, "WORK_ORDER")).isEqualTo(1L);
    // The row keeps its original id through the ON CONFLICT update (no delete+re-insert).
    assertThat(savedSecond.id()).isEqualTo(savedFirst.id());
  }

  @Test
  @DisplayName("Independence matrix: WORK_ORDER and PREVENTIVE are separate rows that never clobber each other")
  void typesAreIndependentRows() {
    seedActor();
    service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "<p>WO</p>", actor(ApplicationRole.SUPER_ADMIN));
    service.upsertTemplate(ReportPrintTemplateType.PREVENTIVE, "<p>PM</p>", actor(ApplicationRole.SUPER_ADMIN));
    service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "<p>WO v2</p>", actor(ApplicationRole.SUPER_ADMIN));

    assertThat(service.findTemplate(ReportPrintTemplateType.WORK_ORDER))
        .get().extracting(ReportPrintTemplate::bodyHtml).isEqualTo("<p>WO v2</p>");
    assertThat(service.findTemplate(ReportPrintTemplateType.PREVENTIVE))
        .get().extracting(ReportPrintTemplate::bodyHtml).isEqualTo("<p>PM</p>");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_print_templates", Long.class)).isEqualTo(2L);
  }

  @Test
  @DisplayName("Audit: updated_by records the authenticated actor")
  void upsertRecordsUpdatedBy() {
    seedActor();
    ReportPrintTemplate saved =
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "<p>x</p>", actor(ApplicationRole.SUPER_ADMIN));
    assertThat(saved.updatedBy()).isEqualTo(ACTOR_ID);
    assertThat(jdbc.queryForObject(
        "SELECT updated_by FROM report_print_templates WHERE report_type = ?",
        Object.class, "WORK_ORDER").toString()).isEqualTo(ACTOR_ID.toString());
  }

  @Test
  @DisplayName("Size guard matrix: >200k chars is rejected at the service boundary")
  void oversizedBodyRejected() {
    seedActor();
    String huge = "<p>" + "a".repeat(ReportPrintTemplateService.MAX_BODY_HTML_LENGTH) + "</p>";
    assertThat(huge.length()).isGreaterThan(ReportPrintTemplateService.MAX_BODY_HTML_LENGTH);

    assertThatThrownBy(() ->
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, huge, actor(ApplicationRole.SUPER_ADMIN)))
        .isInstanceOf(ReportPrintTemplateTooLargeException.class);
  }

  @Test
  @DisplayName("Role matrix: non-admin actor is refused server-side (403 at the API)")
  void nonAdminCannotUpsert() {
    seedActor();
    assertThatThrownBy(() ->
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "<p>x</p>",
            actor(ApplicationRole.STAFF_MAINTENANCE)))
        .isInstanceOf(ReportPrintTemplateForbiddenException.class);
    assertThat(jdbc.queryForObject("SELECT count(*) FROM report_print_templates", Long.class)).isZero();
  }

  @Test
  @DisplayName("GET matrix: unknown type reads as absent (client falls back silently)")
  void findTemplateEmptyWhenMissing() {
    seedActor();
    assertThat(service.findTemplate(ReportPrintTemplateType.PREVENTIVE)).isEmpty();
  }

  @Test
  @DisplayName("Clear matrix: empty body round-trips — save '' returns 200 and GET reads back empty string")
  void emptyBodyRoundTrips() {
    seedActor();
    service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "<p>layout</p>", actor(ApplicationRole.SUPER_ADMIN));

    ReportPrintTemplate cleared =
        service.upsertTemplate(ReportPrintTemplateType.WORK_ORDER, "", actor(ApplicationRole.SUPER_ADMIN));
    assertThat(cleared.bodyHtml()).isEmpty();
    assertThat(service.findTemplate(ReportPrintTemplateType.WORK_ORDER))
        .get().extracting(ReportPrintTemplate::bodyHtml).isEqualTo("");
    // Still exactly one row — the clear is an update, not a delete+re-insert.
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM report_print_templates WHERE report_type = ?",
        Long.class, "WORK_ORDER")).isEqualTo(1L);
  }
}
