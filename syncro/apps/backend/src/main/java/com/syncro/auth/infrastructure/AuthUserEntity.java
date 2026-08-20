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
}
