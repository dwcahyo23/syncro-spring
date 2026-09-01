package com.syncro.compliance.infrastructure.db;

import com.syncro.compliance.domain.EightDStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code eight_d_reports} row (blueprint H2, story 15-2). One 8D report per
 * non-conformance ({@code uq_eight_d_reports_nc}); D1 team and D4 root-cause analysis
 * are JSONB documents, the rest are free-text sections.
 */
@Entity
@Table(name = "eight_d_reports")
public class EightDReportEntity {

  @Id
  private UUID id;

  @Column(name = "nc_id", nullable = false)
  private UUID ncId;

  @Column(name = "report_number", nullable = false, length = 50)
  private String reportNumber;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "d1_team", columnDefinition = "jsonb")
  private Map<String, Object> d1Team;

  @Column(name = "d2_description", columnDefinition = "text")
  private String d2Description;

  @Column(name = "d3_containment", columnDefinition = "text")
  private String d3Containment;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "d4_root_cause", columnDefinition = "jsonb")
  private Map<String, Object> d4RootCause;

  @Column(name = "d5_ca_permanent", columnDefinition = "text")
  private String d5CaPermanent;

  @Column(name = "d6_implementation", columnDefinition = "text")
  private String d6Implementation;

  @Column(name = "d7_lesson_learned", columnDefinition = "text")
  private String d7LessonLearned;

  @Column(name = "d8_closure_notes", columnDefinition = "text")
  private String d8ClosureNotes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EightDStatus status;

  @Column(name = "effectiveness_verified_at")
  private Instant effectivenessVerifiedAt;

  @Column(name = "pdf_artifact_url", columnDefinition = "text")
  private String pdfArtifactUrl;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EightDReportEntity() {
  }

  public EightDReportEntity(UUID id, UUID ncId, String reportNumber, Map<String, Object> d1Team,
      String d2Description, String d3Containment, Map<String, Object> d4RootCause,
      String d5CaPermanent, String d6Implementation, String d7LessonLearned,
      String d8ClosureNotes, EightDStatus status, Instant effectivenessVerifiedAt,
      String pdfArtifactUrl, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.ncId = ncId;
    this.reportNumber = reportNumber;
    this.d1Team = d1Team;
    this.d2Description = d2Description;
    this.d3Containment = d3Containment;
    this.d4RootCause = d4RootCause;
    this.d5CaPermanent = d5CaPermanent;
    this.d6Implementation = d6Implementation;
    this.d7LessonLearned = d7LessonLearned;
    this.d8ClosureNotes = d8ClosureNotes;
    this.status = status;
    this.effectivenessVerifiedAt = effectivenessVerifiedAt;
    this.pdfArtifactUrl = pdfArtifactUrl;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getNcId() {
    return ncId;
  }

  public String getReportNumber() {
    return reportNumber;
  }

  public Map<String, Object> getD1Team() {
    return d1Team;
  }

  public String getD2Description() {
    return d2Description;
  }

  public String getD3Containment() {
    return d3Containment;
  }

  public Map<String, Object> getD4RootCause() {
    return d4RootCause;
  }

  public String getD5CaPermanent() {
    return d5CaPermanent;
  }

  public String getD6Implementation() {
    return d6Implementation;
  }

  public String getD7LessonLearned() {
    return d7LessonLearned;
  }

  public String getD8ClosureNotes() {
    return d8ClosureNotes;
  }

  public EightDStatus getStatus() {
    return status;
  }

  public Instant getEffectivenessVerifiedAt() {
    return effectivenessVerifiedAt;
  }

  public String getPdfArtifactUrl() {
    return pdfArtifactUrl;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /** Effectiveness verification (H2): stamps the verdict time. */
  public void verifyEffectiveness(EightDStatus status, Instant verifiedAt, Instant updatedAt) {
    this.status = status;
    this.effectivenessVerifiedAt = verifiedAt;
    this.updatedAt = updatedAt;
  }
}
