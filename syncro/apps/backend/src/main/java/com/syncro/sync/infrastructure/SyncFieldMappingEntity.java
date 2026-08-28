package com.syncro.sync.infrastructure;

import com.syncro.sync.domain.SyncFieldDomain;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Persisted {@code sync_field_mappings} row (AD-8, story 13-2). Declares which
 * external fields sync may overwrite (MASTER) versus locally-owned fields that are
 * always preserved (OPERATIONAL). Unmapped fields default to MASTER at runtime.
 */
@Entity
@Table(name = "sync_field_mappings")
public class SyncFieldMappingEntity {

  @Id
  @Column(name = "field_name", length = 64)
  private String fieldName;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private SyncFieldDomain domain;

  protected SyncFieldMappingEntity() {
  }

  public SyncFieldMappingEntity(String fieldName, SyncFieldDomain domain) {
    this.fieldName = fieldName;
    this.domain = domain;
  }

  public String getFieldName() {
    return fieldName;
  }

  public SyncFieldDomain getDomain() {
    return domain;
  }
}
