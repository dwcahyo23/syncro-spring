package com.syncro.sync.domain;

/**
 * Field classification domain (AD-8, story 13-2). MASTER fields take the external
 * value on re-sync; OPERATIONAL fields are locally owned and always preserved.
 * Persisted in {@code sync_field_mappings.domain} as uppercase contract strings.
 */
public enum SyncFieldDomain {
  MASTER,
  OPERATIONAL
}
