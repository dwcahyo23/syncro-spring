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
 * Persisted {@code lesson_learned} row (blueprint H6, story 15-2; the table keeps the
 * blueprint's singular name). One project retrospective; {@code spareparts_used} is a
 * JSONB document and {@code tags} is a JSONB string array.
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

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected LessonLearnedEntity() {
  }

  public LessonLearnedEntity(UUID id, String projectId, UUID machineId, String projectType,
      String title, String problemSummary, String rootCause, String solution,
      Map<String, Object> sparepartsUsed, Integer durationDays, Integer reCycleCount,
      List<String> tags, Instant createdAt, Instant updatedAt) {
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

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
