package com.syncro.sync.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.sync.domain.SyncFieldDomain;
import com.syncro.sync.infrastructure.SyncFieldMappingEntity;
import com.syncro.sync.infrastructure.SyncFieldMappingRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Story 13-2 unit tests for {@link FieldClassificationService} (AD-8): the field
 * classification config loaded from {@code sync_field_mappings}, with unmapped fields
 * defaulting to MASTER.
 */
@ExtendWith(MockitoExtension.class)
class FieldClassificationServiceTest {

  @Mock
  private SyncFieldMappingRepository repository;

  @Test
  @DisplayName("13.2-FC-001 P0 MASTER fields resolve to MASTER, OPERATIONAL fields to OPERATIONAL")
  void classifiesConfiguredFields() {
    when(repository.findAll()).thenReturn(List.of(
        new SyncFieldMappingEntity("status", SyncFieldDomain.MASTER),
        new SyncFieldMappingEntity("machine_id", SyncFieldDomain.MASTER),
        new SyncFieldMappingEntity("report_chronological", SyncFieldDomain.OPERATIONAL),
        new SyncFieldMappingEntity("cpk", SyncFieldDomain.OPERATIONAL)));

    var service = new FieldClassificationService(repository);

    assertThat(service.isMaster("status")).isTrue();
    assertThat(service.isMaster("machine_id")).isTrue();
    assertThat(service.isMaster("category_id")).isTrue();
    assertThat(service.isMaster("report_chronological")).isFalse();
    assertThat(service.isMaster("cpk")).isFalse();
  }

  @Test
  @DisplayName("13.2-FC-002 P0 unmapped fields default to MASTER (AD-8)")
  void unmappedDefaultsToMaster() {
    when(repository.findAll()).thenReturn(List.of(
        new SyncFieldMappingEntity("status", SyncFieldDomain.MASTER)));

    var service = new FieldClassificationService(repository);

    // 'sync_version' and any unknown field are unmapped → MASTER.
    assertThat(service.isMaster("sync_version")).isTrue();
    assertThat(service.isMaster("some_new_field")).isTrue();
  }

  @Test
  @DisplayName("13.2-FC-003 P0 empty mappings table → every field is MASTER (AD-8 default)")
  void emptyMappingsAllMaster() {
    when(repository.findAll()).thenReturn(List.of());

    var service = new FieldClassificationService(repository);

    assertThat(service.isMaster("status")).isTrue();
    assertThat(service.isMaster("report_chronological")).isTrue();
  }

  @Test
  @DisplayName("13.2-FC-004 P0 case-sensitivity: field names are matched exactly as configured")
  void fieldNamesCaseSensitive() {
    when(repository.findAll()).thenReturn(List.of(
        new SyncFieldMappingEntity("report_chronological", SyncFieldDomain.OPERATIONAL)));

    var service = new FieldClassificationService(repository);

    // The configured name matches; a differently-cased variant is unmapped → MASTER.
    assertThat(service.isMaster("report_chronological")).isFalse();
    assertThat(service.isMaster("REPORT_CHRONOLOGICAL")).isTrue();
  }
}
