package com.syncro.compliance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code historical_machine_records} row (blueprint H7, story 15-2). One
 * imported historical incident row from a bulk upload: original text columns, the
 * JSONB {@code tags} array, the immutable {@code raw_payload} for audit, and the
 * import batch coordinates.
 */
@Entity
@Table(name = "historical_machine_records")
public class HistoricalMachineRecordEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "import_batch_id", length = 64)
  private String importBatchId;

  @Column(name = "source_file_name", length = 255)
  private String sourceFileName;

  @Column(name = "source_row_number")
  private Integer sourceRowNumber;

  @Column(name = "happened_at")
  private Instant happenedAt;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(name = "problem_summary", columnDefinition = "text")
  private String problemSummary;

  @Column(name = "root_cause", columnDefinition = "text")
  private String rootCause;

  @Column(columnDefinition = "text")
  private String solution;

  @Column(name = "lesson_learned", columnDefinition = "text")
  private String lessonLearned;

  @Column(name = "downtime_minutes")
  private Integer downtimeMinutes;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> tags;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "raw_payload", columnDefinition = "jsonb")
  private Map<String, Object> rawPayload;

  @Column(name = "imported_by")
  private UUID importedBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  protected HistoricalMachineRecordEntity() {
  }

  public HistoricalMachineRecordEntity(UUID id, UUID machineId, String importBatchId,
      String sourceFileName, Integer sourceRowNumber, Instant happenedAt, String title,
      String problemSummary, String rootCause, String solution, String lessonLearned,
      Integer downtimeMinutes, List<String> tags, Map<String, Object> rawPayload,
      UUID importedBy, Instant createdAt) {
    this.id = id;
    this.machineId = machineId;
    this.importBatchId = importBatchId;
    this.sourceFileName = sourceFileName;
    this.sourceRowNumber = sourceRowNumber;
    this.happenedAt = happenedAt;
    this.title = title;
    this.problemSummary = problemSummary;
    this.rootCause = rootCause;
    this.solution = solution;
    this.lessonLearned = lessonLearned;
    this.downtimeMinutes = downtimeMinutes;
    this.tags = tags;
    this.rawPayload = rawPayload;
    this.importedBy = importedBy;
    this.createdAt = createdAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getImportBatchId() {
    return importBatchId;
  }

  public String getSourceFileName() {
    return sourceFileName;
  }

  public Integer getSourceRowNumber() {
    return sourceRowNumber;
  }

  public Instant getHappenedAt() {
    return happenedAt;
  }

  public String getTitle() {
    return title;
  }

  public String getProblemSummary() {
    return problemSummary;
  }

  public String getRootCause() {
    return rootCause;
  }

  public String getSolution() {
    return solution;
  }

  public String getLessonLearned() {
    return lessonLearned;
  }

  public Integer getDowntimeMinutes() {
    return downtimeMinutes;
  }

  public List<String> getTags() {
    return tags;
  }

  public Map<String, Object> getRawPayload() {
    return rawPayload;
  }

  public UUID getImportedBy() {
    return importedBy;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }
}
