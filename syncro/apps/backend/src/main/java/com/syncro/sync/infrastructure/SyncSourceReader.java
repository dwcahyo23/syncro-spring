package com.syncro.sync.infrastructure;

import com.syncro.sync.domain.SyncSourceRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Reads external workorder rows from the reference system's {@code sch_ot.mow_mtn_appm}
 * table via the secondary {@link JdbcTemplate}. Ordered by {@code sheet_no ASC} with a
 * configurable batch limit and watermark offset.
 *
 * <p>Only created when {@code syncro.sync.enabled=true} — the secondary datasource bean
 * {@code syncJdbcTemplate} is conditional on the same property.
 */
@Repository
@ConditionalOnProperty(prefix = "syncro.sync", name = "enabled", havingValue = "true")
public class SyncSourceReader {

  private static final String QUERY = """
      SELECT sheet_no, machine_code, category_code, status, description,
             created_at, updated_at, parent_sheet_no
      FROM sch_ot.mow_mtn_appm
      WHERE sheet_no > ?
      ORDER BY sheet_no ASC
      LIMIT ?
      """;

  private final JdbcTemplate jdbc;

  public SyncSourceReader(JdbcTemplate syncJdbcTemplate) {
    this.jdbc = syncJdbcTemplate;
  }

  /**
   * Returns the next batch of rows strictly above the given watermark.
   *
   * @param lastSheetNo the last processed sheet_no (exclusive), or empty string for the first run
   * @param batchSize   maximum rows to return
   * @return list of external rows, ordered by sheet_no ASC
   */
  public List<SyncSourceRow> readBatch(String lastSheetNo, int batchSize) {
    return jdbc.query(QUERY, new SyncSourceRowMapper(), lastSheetNo, batchSize);
  }

  private static class SyncSourceRowMapper implements RowMapper<SyncSourceRow> {

    @Override
    public SyncSourceRow mapRow(ResultSet rs, int rowNum) throws SQLException {
      return new SyncSourceRow(
          rs.getString("sheet_no"),
          rs.getString("machine_code"),
          rs.getString("category_code"),
          rs.getString("status"),
          rs.getString("description"),
          toInstant(rs.getTimestamp("created_at")),
          toInstant(rs.getTimestamp("updated_at")),
          rs.getString("parent_sheet_no")
      );
    }

    /**
     * Converts a raw DB timestamp to UTC by interpreting the stored value as
     * Asia/Jakarta local time (FR-154). The external {@code sch_ot.mow_mtn_appm}
     * schema uses {@code TIMESTAMP} (no timezone) columns storing Asia/Jakarta
     * local time, but the JDBC driver returns them as {@code java.sql.Timestamp}
     * which {@code toInstant()} would interpret as UTC. This method corrects the
     * interpretation.
     */
    private static Instant toInstant(java.sql.Timestamp timestamp) {
      if (timestamp == null) {
        return null;
      }
      return timestamp.toLocalDateTime().atZone(ZoneId.of("Asia/Jakarta")).toInstant();
    }
  }
}