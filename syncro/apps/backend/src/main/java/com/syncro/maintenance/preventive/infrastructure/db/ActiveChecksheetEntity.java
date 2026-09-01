package com.syncro.maintenance.preventive.infrastructure.db;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Persisted {@code active_checksheets} row (blueprint F3, story 15-2). One row per
 * {@code (machine_id, frequency_id)} pointing at the currently active checksheet
 * revision; composite PK via V1, unique {@code checksheet_id}.
 */
@Entity
@Table(name = "active_checksheets")
public class ActiveChecksheetEntity {

  @EmbeddedId
  private ActiveChecksheetId id;

  @Column(name = "checksheet_id", nullable = false)
  private UUID checksheetId;

  protected ActiveChecksheetEntity() {
  }

  public ActiveChecksheetEntity(ActiveChecksheetId id, UUID checksheetId) {
    this.id = id;
    this.checksheetId = checksheetId;
  }

  public ActiveChecksheetId getId() {
    return id;
  }

  public UUID getChecksheetId() {
    return checksheetId;
  }
}
