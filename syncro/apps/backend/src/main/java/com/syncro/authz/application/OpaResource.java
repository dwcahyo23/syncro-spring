package com.syncro.authz.application;

import java.util.Map;
import java.util.UUID;

/**
 * Authoritative OPA wire schema (Addendum A3) — resource half. Identity fields stay
 * nullable because endpoint resources carry only {@code type} plus free-form attributes.
 */
public record OpaResource(
    String type,
    UUID id,
    UUID plantId,
    UUID machineGroupId,
    Map<String, Object> attributes) {

  public OpaResource {
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }
}
