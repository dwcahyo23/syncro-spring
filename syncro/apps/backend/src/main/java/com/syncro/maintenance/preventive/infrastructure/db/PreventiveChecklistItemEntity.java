package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One {@code preventive_checklist_items} row (FR-132, story 11-2). Items are replaced
 * wholesale on amend (PUT); {@code lsl}/{@code usl} are optional numeric bounds where
 * applicable to the assessment.
 */
@Entity
@Table(name = "preventive_checklist_items")
public class PreventiveChecklistItemEntity {

  @Id
  private UUID id;

  @Column(name = "result_id", nullable = false)
  private UUID resultId;

  @Column(nullable = false)
  private short position;

  @Column(nullable = false, length = 200)
  private String label;

  @Column(columnDefinition = "text")
  private String value;

  @Column(precision = 20, scale = 6)
  private BigDecimal lsl;

  @Column(precision = 20, scale = 6)
  private BigDecimal usl;

  @Column(columnDefinition = "text")
  private String note;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected PreventiveChecklistItemEntity() {
  }

  public PreventiveChecklistItemEntity(UUID id, UUID resultId, short position, String label, String value,
      BigDecimal lsl, BigDecimal usl, String note, Instant createdAt) {
    this.id = id;
    this.resultId = resultId;
    this.position = position;
    this.label = label;
    this.value = value;
    this.lsl = lsl;
    this.usl = usl;
    this.note = note;
    this.createdAt = createdAt;
  }

  public UUID getId() { return id; }
  public UUID getResultId() { return resultId; }
  public short getPosition() { return position; }
  public String getLabel() { return label; }
  public String getValue() { return value; }
  public BigDecimal getLsl() { return lsl; }
  public BigDecimal getUsl() { return usl; }
  public String getNote() { return note; }
  public Instant getCreatedAt() { return createdAt; }
}
