package com.syncro.authz.infrastructure;

import com.syncro.authz.domain.AuthzDecision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "authz_decisions")
public class AuthzDecisionEntity {

  @Id
  private UUID id;

  @Column(name = "decision_id", nullable = true)
  private UUID decisionId;

  @Column(name = "policy_revision")
  private String policyRevision;

  @Column(nullable = false)
  private boolean allowed;

  @Column(nullable = false)
  private boolean degraded;

  @Column(name = "subject_user_id")
  private UUID subjectUserId;

  @Column(nullable = false, length = 255)
  private String action;

  @Column(name = "resource_type", length = 64)
  private String resourceType;

  @Column(name = "decided_at", nullable = false)
  private Instant decidedAt;

  protected AuthzDecisionEntity() {
  }

  public AuthzDecisionEntity(AuthzDecision decision) {
    this.id = UUID.randomUUID();
    this.decisionId = decision.decisionId();
    this.policyRevision = decision.policyRevision();
    this.allowed = decision.allowed();
    this.degraded = decision.degraded();
    this.subjectUserId = decision.userId();
    this.action = decision.action();
    this.resourceType = decision.resourceType();
    this.decidedAt = decision.decidedAt();
  }

  public UUID getId() {
    return id;
  }

  public UUID getDecisionId() {
    return decisionId;
  }

  public String getPolicyRevision() {
    return policyRevision;
  }

  public boolean isAllowed() {
    return allowed;
  }

  public boolean isDegraded() {
    return degraded;
  }

  public UUID getSubjectUserId() {
    return subjectUserId;
  }

  public String getAction() {
    return action;
  }

  public String getResourceType() {
    return resourceType;
  }

  public Instant getDecidedAt() {
    return decidedAt;
  }

  public AuthzDecision toDomain() {
    return new AuthzDecision(decisionId, policyRevision, allowed, degraded, subjectUserId,
        action, resourceType, decidedAt);
  }
}