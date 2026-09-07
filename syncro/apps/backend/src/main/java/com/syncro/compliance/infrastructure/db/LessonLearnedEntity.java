package com.syncro.compliance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code lesson_learned} row (blueprint H6, story 15-2; the table keeps the
 * blueprint's singular name). One project retrospective; {@code spareparts_used} is a
 * JSONB document and {@code tags} is a JSONB string array. Story 21-3 adds the
 * optional event links {@code nc_id}/{@code eight_d_id}/{@code work_order_id} (V18,
 * FK ON DELETE SET NULL — the lesson survives its source event), an {@code evidence}
 * JSONB array of Garage {@code {objectKey, filename}} references (8D pdfArtifactUrl
 * passthrough posture), and the optimistic-lock {@code version} (V13/V15/V17
 * precedent) — concurrent writes surface as 409 VERSION_CONFLICT.
 */
@Entity
@Table(name = "lesson_learned")
public class LessonLearnedEntity {

  @Id
  private UUID id;

  @Column(name = "project_id", nullable = false, length = 64)
  private String projectId;

  @Column(name = "machine_id")
  private UUID machineId;

  @Column(name = "project_type", length = 50)
  private String projectType;

  @Column(nullable = false, length = 255)
  private String title;

  @Column(name = "problem_summary", nullable = false, columnDefinition = "text")
  private String problemSummary;

  @Column(name = "root_cause", columnDefinition = "text")
  private String rootCause;

  @Column(columnDefinition = "text")
  private String solution;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "spareparts_used", columnDefinition = "jsonb")
  private Map<String, Object> sparepartsUsed;

  @Column(name = "duration_days")
  private Integer durationDays;

  @Column(name = "re_cycle_count")
  private Integer reCycleCount;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<String> tags;

  /** Source-event links (V18, story 21-3): all optional, FK ON DELETE SET NULL. */
  @Column(name = "nc_id")
  private UUID ncId;

  @Column(name = "eight_d_id")
  private UUID eightDId;

  @Column(name = "work_order_id", length = 50)
  private String workOrderId;

  /** Garage {objectKey, filename} references (V18) — passthrough, no attachment table. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private List<Map<String, Object>> evidence;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock (V18): concurrent updates → 409 VERSION_CONFLICT (21-1 P6 precedent). */
  @Version
  @Column(nullable = false)
  private long version;

  protected LessonLearnedEntity() {
  }

  /** Story 15-2 shape (no event links) — delegates with null links + evidence. */
  public LessonLearnedEntity(UUID id, String projectId, UUID machineId, String projectType,
      String title, String problemSummary, String rootCause, String solution,
      Map<String, Object> sparepartsUsed, Integer durationDays, Integer reCycleCount,
      List<String> tags, Instant createdAt, Instant updatedAt) {
    this(id, projectId, machineId, projectType, title, problemSummary, rootCause, solution,
        sparepartsUsed, durationDays, reCycleCount, tags, null, null, null, null, createdAt,
        updatedAt);
  }

  public LessonLearnedEntity(UUID id, String projectId, UUID machineId, String projectType,
      String title, String problemSummary, String rootCause, String solution,
      Map<String, Object> sparepartsUsed, Integer durationDays, Integer reCycleCount,
      List<String> tags, UUID ncId, UUID eightDId, String workOrderId,
      List<Map<String, Object>> evidence, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.projectId = projectId;
    this.machineId = machineId;
    this.projectType = projectType;
    this.title = title;
    this.problemSummary = problemSummary;
    this.rootCause = rootCause;
    this.solution = solution;
    this.sparepartsUsed = sparepartsUsed;
    this.durationDays = durationDays;
    this.reCycleCount = reCycleCount;
    this.tags = tags;
    this.ncId = ncId;
    this.eightDId = eightDId;
    this.workOrderId = workOrderId;
    this.evidence = evidence;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getProjectId() {
    return projectId;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public String getProjectType() {
    return projectType;
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

  public Map<String, Object> getSparepartsUsed() {
    return sparepartsUsed;
  }

  public Integer getDurationDays() {
    return durationDays;
  }

  public Integer getReCycleCount() {
    return reCycleCount;
  }

  public List<String> getTags() {
    return tags;
  }

  public UUID getNcId() {
    return ncId;
  }

  public UUID getEightDId() {
    return eightDId;
  }

  public String getWorkOrderId() {
    return workOrderId;
  }

  public List<Map<String, Object>> getEvidence() {
    return evidence;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getVersion() {
    return version;
  }

  /**
   * Partial update (story 21-3 PATCH, 21-1 precedent): a null argument keeps the
   * stored value. {@code projectId} and {@code machineId} are immutable after
   * creation; event links move through {@link #linkEvents}.
   */
  public void updateContent(String title, String problemSummary, String rootCause,
      String solution, Map<String, Object> sparepartsUsed, Integer durationDays,
      Integer reCycleCount, List<String> tags, List<Map<String, Object>> evidence,
      Instant updatedAt) {
    if (title != null) {
      this.title = title;
    }
    if (problemSummary != null) {
      this.problemSummary = problemSummary;
    }
    if (rootCause != null) {
      this.rootCause = rootCause;
    }
    if (solution != null) {
      this.solution = solution;
    }
    if (sparepartsUsed != null) {
      this.sparepartsUsed = sparepartsUsed;
    }
    if (durationDays != null) {
      this.durationDays = durationDays;
    }
    if (reCycleCount != null) {
      this.reCycleCount = reCycleCount;
    }
    if (tags != null) {
      this.tags = tags;
    }
    if (evidence != null) {
      this.evidence = evidence;
    }
    this.updatedAt = updatedAt;
  }

  /**
   * Re-links the source events (story 21-3): a null argument keeps the stored link;
   * an explicit clear is not offered (the FK is ON DELETE SET NULL — the link drops
   * only when its event row is deleted).
   */
  public void linkEvents(UUID ncId, UUID eightDId, String workOrderId, Instant updatedAt) {
    if (ncId != null) {
      this.ncId = ncId;
    }
    if (eightDId != null) {
      this.eightDId = eightDId;
    }
    if (workOrderId != null) {
      this.workOrderId = workOrderId;
    }
    this.updatedAt = updatedAt;
  }
}
