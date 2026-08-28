package com.syncro.sync.application;

import com.syncro.sync.domain.SyncFieldDomain;
import com.syncro.sync.infrastructure.SyncFieldMappingRepository;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Loads field classification mappings from {@code sync_field_mappings} (AD-8, story 13-2)
 * and provides {@link #isMaster} queries. Unmapped fields default to MASTER.
 * The mappings are loaded once at startup since the table changes infrequently.
 */
@Service
public class FieldClassificationService {

  private final Map<String, SyncFieldDomain> mappings;

  public FieldClassificationService(SyncFieldMappingRepository repository) {
    var map = new HashMap<String, SyncFieldDomain>();
    for (var mapping : repository.findAll()) {
      map.put(mapping.getFieldName(), mapping.getDomain());
    }
    this.mappings = Collections.unmodifiableMap(map);
  }

  /**
   * Returns true when the field is classified as MASTER (external value overwrites local).
   * Unmapped fields default to MASTER per AD-8.
   */
  public boolean isMaster(String fieldName) {
    return mappings.getOrDefault(fieldName, SyncFieldDomain.MASTER) == SyncFieldDomain.MASTER;
  }
}