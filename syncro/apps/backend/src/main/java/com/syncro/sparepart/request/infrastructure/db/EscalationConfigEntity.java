package com.syncro.sparepart.request.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted escalation_configs row (story 12-3, FR-142/FR-147).
 */
@Entity
@Table(name = "escalation_configs")
public class EscalationConfigEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 20)
  private String scope;

  @Column(nullable = false, length = 30)
  private String step;

  @Column(name = "min_cost", precision = 18, scale = 2)
  private BigDecimal minCost;

  @Column(name = "max_cost", precision = 18, scale = 2)
  private BigDecimal maxCost;

  @Column(name = "duration_minutes", nullable = false)
  private int durationMinutes;

  @Column(name = "approval_role", length = 30)
  private String approvalRole;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected EscalationConfigEntity() {
  }

  public UUID getId() { return id; }
  public String getScope() { return scope; }
  public String getStep() { return step; }
  public BigDecimal getMinCost() { return minCost; }
  public BigDecimal getMaxCost() { return maxCost; }
  public int getDurationMinutes() { return durationMinutes; }
  public String getApprovalRole() { return approvalRole; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}