package com.syncro.org.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Job title master (user-master reference data). */
@Entity
@Table(name = "job_titles")
public class JobTitleEntity {

  @Id
  private UUID id;

  @Column(nullable = false, length = 50)
  private String code;

  @Column(nullable = false, length = 200)
  private String name;

  @Column(length = 1000)
  private String description;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected JobTitleEntity() {
  }

  public JobTitleEntity(UUID id, String code, String name, String description, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.description = description;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public String getDescription() {
    return description;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
