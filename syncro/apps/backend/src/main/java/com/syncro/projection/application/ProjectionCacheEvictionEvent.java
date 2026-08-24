package com.syncro.projection.application;

import java.util.UUID;

/**
 * Cache invalidation signal published by shift-config, installation, and procurement mutators
 * (stories 8-5/8-2 write paths). A non-null {@code machineId} evicts only that machine's cached
 * projections; {@code null} means ALL machines (dev-scale pattern delete, no SCAN loop).
 */
public record ProjectionCacheEvictionEvent(UUID machineId) {

  public static ProjectionCacheEvictionEvent all() {
    return new ProjectionCacheEvictionEvent(null);
  }
}
