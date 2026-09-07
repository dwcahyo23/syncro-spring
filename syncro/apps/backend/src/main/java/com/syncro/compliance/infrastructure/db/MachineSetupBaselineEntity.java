package com.syncro.compliance.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted {@code machine_setup_baselines} row (blueprint H5, story 15-2). Versioned
 * setup-parameter snapshot for a machine ({@code parameters} JSONB), optionally
 * produced by an ECN ({@code uq_machine_setup_baselines_ecn}). Story 21-3 adds the
 * optimistic-lock {@code lock_version} (V18): the existing {@code version} column is
 * the server-assigned BUSINESS version (part of
 * {@code uq_machine_setup_baselines_machine_ecn_version}), so the JPA {@code @Version}
 * needs its own column — concurrent writes surface as 409 VERSION_CONFLICT (21-1 P6
 * precedent). Exactly one baseline per machine stays active; creating or activating a
 * version supersedes the machine's siblings in the same transaction.
 */
@Entity
@Table(name = "machine_setup_baselines")
public class MachineSetupBaselineEntity {

  @Id
  private UUID id;

  @Column(name = "machine_id", nullable = false)
  private UUID machineId;

  @Column(name = "ecn_id")
  private UUID ecnId;

  @Column(nullable = false)
  private int version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb")
  private Map<String, Object> parameters;

  @Column(name = "validated_by")
  private UUID validatedBy;

  @Column(name = "validated_at")
  private Instant validatedAt;

  @Column(name = "is_active", nullable = false)
  private boolean active = true;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  /** Optimistic lock (V18): concurrent updates → 409 VERSION_CONFLICT (21-1 P6 precedent). */
  @Version
  @Column(name = "lock_version", nullable = false)
  private long lockVersion;

  protected MachineSetupBaselineEntity() {
  }

  public MachineSetupBaselineEntity(UUID id, UUID machineId, UUID ecnId, int version,
      Map<String, Object> parameters, UUID validatedBy, Instant validatedAt, boolean active,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.machineId = machineId;
    this.ecnId = ecnId;
    this.version = version;
    this.parameters = parameters;
    this.validatedBy = validatedBy;
    this.validatedAt = validatedAt;
    this.active = active;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getMachineId() {
    return machineId;
  }

  public UUID getEcnId() {
    return ecnId;
  }

  public int getVersion() {
    return version;
  }

  public Map<String, Object> getParameters() {
    return parameters;
  }

  public UUID getValidatedBy() {
    return validatedBy;
  }

  public Instant getValidatedAt() {
    return validatedAt;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public long getLockVersion() {
    return lockVersion;
  }

  /** Supersedes this baseline with a new validated version (H5). */
  public void supersed(UUID validatedBy, Instant validatedAt, Instant updatedAt) {
    this.validatedBy = validatedBy;
    this.validatedAt = validatedAt;
    this.active = false;
    this.updatedAt = updatedAt;
  }

  /** Activates this baseline (story 21-3): the sibling deactivation is the service's job. */
  public void activate(Instant updatedAt) {
    this.active = true;
    this.updatedAt = updatedAt;
  }

  /** Deactivates a superseded sibling (story 21-3, PmChecksheetService.deactivate precedent). */
  public void deactivate(Instant updatedAt) {
    this.active = false;
    this.updatedAt = updatedAt;
  }
}
