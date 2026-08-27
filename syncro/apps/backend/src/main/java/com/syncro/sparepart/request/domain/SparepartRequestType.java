package com.syncro.sparepart.request.domain;

/**
 * Sparepart request type (FR-140, story 12-1). {@code SPAREPART} uses the electric/mechanic
 * taxonomy and binds a machine; {@code CONSUMABLE} has no machine binding; {@code SERVICE_EXTERNAL}
 * is bound to a parent workorder and has no stock flow.
 */
public enum SparepartRequestType {
  SPAREPART,
  CONSUMABLE,
  SERVICE_EXTERNAL
}
