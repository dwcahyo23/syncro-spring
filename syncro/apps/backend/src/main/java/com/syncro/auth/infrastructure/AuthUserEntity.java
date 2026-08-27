package com.syncro.auth.infrastructure;

import com.syncro.auth.domain.ApplicationRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "auth_users")
public class AuthUserEntity {

  @Id
  private UUID id;

  @Column(name = "login_identifier", nullable = false, unique = true)
  private String loginIdentifier;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "application_role", nullable = false)
  private ApplicationRole applicationRole;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "whatsapp_number", length = 32)
  private String whatsappNumber;

  @Column(name = "display_name", length = 200)
  private String displayName;

  @Column(name = "nik", length = 50)
  private String nik;

  @Column(name = "phone_number", length = 32)
  private String phoneNumber;

  @Column(name = "job_title_id")
  private UUID jobTitleId;

  @Column(name = "department_id")
  private UUID departmentId;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AuthUserEntity() {
  }

  public AuthUserEntity(UUID id, String loginIdentifier, String passwordHash, ApplicationRole applicationRole,
      boolean enabled, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.loginIdentifier = loginIdentifier;
    this.passwordHash = passwordHash;
    this.applicationRole = applicationRole;
    this.enabled = enabled;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public void updateMasterFields(String displayName, String nik, String phoneNumber, UUID jobTitleId,
      UUID departmentId, Instant updatedAt) {
    this.displayName = displayName;
    this.nik = nik;
    this.phoneNumber = phoneNumber;
    this.jobTitleId = jobTitleId;
    this.departmentId = departmentId;
    this.updatedAt = updatedAt;
  }

  public UUID getId() {
    return id;
  }

  public String getLoginIdentifier() {
    return loginIdentifier;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public ApplicationRole getApplicationRole() {
    return applicationRole;
  }

  public boolean isEnabled() {
    return enabled;
  }

  public String getWhatsappNumber() {
    return whatsappNumber;
  }

  public String getDisplayName() {
    return displayName;
  }

  public String getNik() {
    return nik;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public UUID getJobTitleId() {
    return jobTitleId;
  }

  public UUID getDepartmentId() {
    return departmentId;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
