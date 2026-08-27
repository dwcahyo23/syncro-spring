package com.syncro.org.infrastructure;

import com.syncro.auth.infrastructure.PlantEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sections")
public class SectionEntity {

  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "plant_id", nullable = false)
  private PlantEntity plant;

  // Stored as the raw uppercase string (DB CHECK constrains the value set); SectionType is the
  // Java-side contract. A String column keeps IgnoreCase lookups and audit snapshots simple.
  @Column(nullable = false, length = 24)
  private String code;

  @Column(nullable = false)
  private String name;

  @Column(nullable = false)
  private boolean active;

  @Version
  @Column(nullable = false)
  private long version;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "leader_user_id")
  private UUID leaderUserId;

  protected SectionEntity() {
  }

  public SectionEntity(UUID id, PlantEntity plant, String code, String name, boolean active,
      Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.plant = plant;
    this.code = code;
    this.name = name;
    this.active = active;
    this.version = 0;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void update(String name, boolean active, Instant updatedAt) {
    this.name = name;
    this.active = active;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public PlantEntity getPlant() {
    return plant;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public boolean isActive() {
    return active;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }

  public UUID getLeaderUserId() {
    return leaderUserId;
  }

  public void setLeaderUserId(UUID leaderUserId) {
    this.leaderUserId = leaderUserId;
  }

  public void assignLeader(UUID leaderUserId, Instant updatedAt) {
    this.leaderUserId = leaderUserId;
    this.updatedAt = updatedAt;
  }

  public void clearLeader(Instant updatedAt) {
    this.leaderUserId = null;
    this.updatedAt = updatedAt;
  }
}
