package com.syncro.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.sync.infrastructure.SyncSourceReader;
import com.syncro.sync.infrastructure.SyncWatermarkRepository;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * Story 13-1 full-path integration test: boots the shared Spring context with
 * {@code syncro.sync.enabled=true} so the conditional sync beans are created, then
 * exercises the reader contract against a real Postgres {@code sch_ot} schema.
 *
 * <p>This is the configuration-contract test the unit tests cannot reach: the
 * {@code @ConditionalOnProperty} bean creation and the secondary JdbcTemplate wiring are
 * exercised with a real database. The external table is created in a dedicated
 * {@code sch_ot} schema in the same container (a genuine secondary datasource is not
 * available in CI), which pins the reader's SQL and mapping contract. The
 * {@code syncro.sync.datasource.url} reuses this container's JDBC URL so the secondary
 * datasource connects to the same Postgres; the external schema is isolated.
 */
@TestPropertySource(properties = {
    "syncro.sync.enabled=true",
    "syncro.sync.datasource.username=test",
    "syncro.sync.datasource.password=test"
})
class SyncPipelineEnabledTest extends AbstractPostgresIntegrationTest {

  @Container
  static final PostgreSQLContainer syncPostgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    syncPostgres.withReuse(true);
  }

  @DynamicPropertySource
  static void syncProperties(DynamicPropertyRegistry registry) {
    registry.add("syncro.sync.datasource.url", syncPostgres::getJdbcUrl);
  }

  @Autowired
  private DataSource dataSource;
  @Autowired
  private SyncSourceReader reader;
  @Autowired
  private SyncWatermarkRepository watermarks;

  private JdbcTemplate externalJdbc;

  @BeforeEach
  void setUpExternalSchema() {
    // The external schema lives in the SECONDARY datasource (the one SyncSourceReader
    // queries) — not the primary app DB. Build the template from the sync datasource URL.
    var ds = org.springframework.boot.jdbc.DataSourceBuilder.create()
        .url(syncPostgres.getJdbcUrl())
        .username(syncPostgres.getUsername())
        .password(syncPostgres.getPassword())
        .driverClassName("org.postgresql.Driver")
        .build();
    externalJdbc = new JdbcTemplate(ds);
    externalJdbc.execute("CREATE SCHEMA IF NOT EXISTS sch_ot");
    externalJdbc.execute("""
        CREATE TABLE IF NOT EXISTS sch_ot.mow_mtn_appm (
          sheet_no VARCHAR(50) PRIMARY KEY,
          machine_code VARCHAR(64),
          category_code VARCHAR(16),
          status VARCHAR(20),
          description TEXT,
          created_at TIMESTAMPTZ,
          updated_at TIMESTAMPTZ,
          parent_sheet_no VARCHAR(50)
        )
        """);
  }

  @Test
  @DisplayName("13.1-E2E-001 P0 sync beans are created when enabled=true and the reader contract works")
  void syncBeansCreatedAndReaderWorks() {
    // The conditional beans exist when enabled=true (SyncSourceReader autowired above).
    assertThat(reader).isNotNull();

    externalJdbc.update("""
        INSERT INTO sch_ot.mow_mtn_appm (sheet_no, machine_code, category_code, status, description, created_at, updated_at)
        VALUES ('EXT-00001', 'GM1', '01', 'OPEN', 'sync pipeline e2e 1', ?, ?),
               ('EXT-00002', 'GM1', '01', 'DONE', 'sync pipeline e2e 2', ?, ?)
        """, ts(0), ts(0), ts(0), ts(0));

    var batch = reader.readBatch("", 100);
    assertThat(batch).hasSize(2);
    assertThat(batch.get(0).sheetNo()).isEqualTo("EXT-00001");
    assertThat(batch.get(1).status()).isEqualTo("DONE");
  }

  @Test
  @DisplayName("13.1-E2E-002 P0 watermark table exists and starts empty")
  void watermarkExists() {
    assertThat(watermarks.findLastSheetNo()).isEmpty();
  }

  private Timestamp ts(int hoursAgo) {
    return Timestamp.from(Instant.parse("2026-08-28T00:00:00Z").minusSeconds(hoursAgo * 3600L));
  }
}
