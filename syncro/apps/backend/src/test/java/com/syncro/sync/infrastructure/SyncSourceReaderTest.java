package com.syncro.sync.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.syncro.AbstractPostgresIntegrationTest;
import java.sql.Timestamp;
import java.time.Instant;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Story 13-1 integration test for {@link SyncSourceReader}: the external query
 * ({@code WHERE sheet_no > ? ORDER BY sheet_no ASC LIMIT ?}) and the row mapping.
 * Uses a real Postgres with the external {@code sch_ot.mow_mtn_appm} shape — the
 * reader is constructed directly with a JdbcTemplate (the conditional bean is not
 * active in the shared test context).
 */
class SyncSourceReaderTest extends AbstractPostgresIntegrationTest {

  @Autowired
  private DataSource dataSource;

  private JdbcTemplate externalJdbc;
  private SyncSourceReader reader;
  private final Timestamp ts = Timestamp.from(Instant.parse("2026-08-28T00:00:00Z"));

  @BeforeEach
  void setUpExternalSchema() {
    externalJdbc = new JdbcTemplate(dataSource);
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
    reader = new SyncSourceReader(externalJdbc);
  }

  @Test
  @DisplayName("13.1-SR-001 P0 returns rows above the watermark ordered by sheet_no ASC, limited to batch size")
  void readsAboveWatermarkOrderedAndLimited() {
    externalJdbc.update("DELETE FROM sch_ot.mow_mtn_appm");
    externalJdbc.update("""
        INSERT INTO sch_ot.mow_mtn_appm (sheet_no, machine_code, category_code, status, description, created_at, updated_at)
        VALUES ('EXT-00001', 'MC-001', '01', 'OPEN', 'desc 1', ?, ?),
               ('EXT-00002', 'MC-002', '02', 'DONE', 'desc 2', ?, ?),
               ('EXT-00003', 'MC-003', '01', 'CLOSED', 'desc 3', ?, ?)
        """, ts, ts, ts, ts, ts, ts);

    var rows = reader.readBatch("EXT-00001", 100);

    assertThat(rows).hasSize(2);
    assertThat(rows.get(0).sheetNo()).isEqualTo("EXT-00002");
    assertThat(rows.get(1).sheetNo()).isEqualTo("EXT-00003");
    assertThat(rows.get(0).machineCode()).isEqualTo("MC-002");
    assertThat(rows.get(0).status()).isEqualTo("DONE");
    assertThat(rows.get(0).description()).isEqualTo("desc 2");
    assertThat(rows.get(0).createdAt()).isEqualTo(ts.toInstant());
    assertThat(rows.get(0).updatedAt()).isEqualTo(ts.toInstant());
  }

  @Test
  @DisplayName("13.1-SR-002 P0 empty watermark reads from the beginning; batch limit honored")
  void readsFromStartWithLimit() {
    externalJdbc.update("DELETE FROM sch_ot.mow_mtn_appm");
    for (int i = 1; i <= 10; i++) {
      externalJdbc.update("""
          INSERT INTO sch_ot.mow_mtn_appm (sheet_no, machine_code, category_code, status, created_at, updated_at)
          VALUES (?, 'MC-001', '01', 'OPEN', ?, ?)
          """, "EXT-%05d".formatted(i), ts, ts);
    }

    var firstBatch = reader.readBatch("", 3);
    assertThat(firstBatch).hasSize(3);
    assertThat(firstBatch.get(0).sheetNo()).isEqualTo("EXT-00001");
    assertThat(firstBatch.get(2).sheetNo()).isEqualTo("EXT-00003");

    var secondBatch = reader.readBatch(firstBatch.get(2).sheetNo(), 100);
    assertThat(secondBatch).hasSize(7);
    assertThat(secondBatch.get(0).sheetNo()).isEqualTo("EXT-00004");
  }

  @Test
  @DisplayName("13.1-SR-003 P0 maps nullable parent_sheet_no and null timestamps")
  void mapsNullables() {
    externalJdbc.update("DELETE FROM sch_ot.mow_mtn_appm");
    externalJdbc.update("""
        INSERT INTO sch_ot.mow_mtn_appm (sheet_no, machine_code, category_code, status, description, parent_sheet_no)
        VALUES ('EXT-00020', 'MC-001', '01', 'OPEN', 'no timestamps', 'EXT-00001')
        """);

    var rows = reader.readBatch("", 100);

    assertThat(rows).hasSize(1);
    assertThat(rows.get(0).parentSheetNo()).isEqualTo("EXT-00001");
    assertThat(rows.get(0).createdAt()).isNull();
    assertThat(rows.get(0).updatedAt()).isNull();
  }

  @Test
  @DisplayName("13.1-SR-004 P0 unknown watermark matches no rows")
  void unknownWatermarkMatchesNothing() {
    externalJdbc.update("DELETE FROM sch_ot.mow_mtn_appm");
    var rows = reader.readBatch("EXT-99999", 100);
    assertThat(rows).isEmpty();
  }

  @Test
  @DisplayName("13.1-SR-005 P0 returns an empty list for an empty table")
  void emptyTableReturnsEmpty() {
    externalJdbc.update("DELETE FROM sch_ot.mow_mtn_appm");
    assertThat(reader.readBatch("", 100)).isEmpty();
  }
}