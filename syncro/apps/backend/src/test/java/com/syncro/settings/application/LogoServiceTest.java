package com.syncro.settings.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.settings.application.LogoService.LogoCommand;
import com.syncro.settings.application.LogoService.LogoNotConfiguredException;
import com.syncro.settings.application.LogoService.LogoValidationException;
import com.syncro.settings.infrastructure.SettingsEntity;
import com.syncro.settings.infrastructure.SettingsRepository;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LogoServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-28T00:00:00Z");

  @Mock
  private SettingsRepository settings;
  @Mock
  private ObjectStorageService objectStorage;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private LogoService service;

  @BeforeEach
  void setUp() {
    service = new LogoService(settings, objectStorage, clock);
    lenient().when(objectStorage.presignGetUrl(any())).thenReturn("https://garage/presigned");
  }

  private SettingsEntity settingsRow(String key) {
    return new SettingsEntity((short) 1, key, NOW, NOW);
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-001 P0 get returns the presigned URL when a logo key is set")
  void getOk() {
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.of(settingsRow("settings/logo/abc.png")));

    var view = service.get();

    assertThat(view.objectKey()).isEqualTo("settings/logo/abc.png");
    assertThat(view.presignedUrl()).isEqualTo("https://garage/presigned");
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-002 P0 get returns nulls when no logo is configured")
  void getAbsent() {
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.of(settingsRow(null)));

    var view = service.get();

    assertThat(view.objectKey()).isNull();
    assertThat(view.presignedUrl()).isNull();
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-003 P0 replace stores the new object and updates the settings row")
  void replaceOk() {
    var entity = settingsRow("settings/logo/old.png");
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.of(entity));

    var view = service.replace(new LogoCommand("logo.png", "image/png", new byte[] {1, 2, 3}));

    assertThat(view.objectKey()).startsWith("settings/logo/");
    assertThat(view.objectKey()).endsWith(".png");
    verify(objectStorage).delete("settings/logo/old.png");
    verify(objectStorage).store(any(), any(), any());
    verify(settings).saveAndFlush(entity);
    assertThat(entity.getLogoObjectKey()).isEqualTo(view.objectKey());
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-004 P0 replace without a previous logo skips the delete")
  void replaceNoPrevious() {
    var entity = settingsRow(null);
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.of(entity));

    service.replace(new LogoCommand("logo.png", "image/png", new byte[] {1}));

    verify(objectStorage, never()).delete(any());
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-005 P0 invalid content type is rejected")
  void replaceInvalidContentType() {
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.of(settingsRow(null)));

    assertThatThrownBy(() -> service.replace(new LogoCommand("logo.txt", "text/plain", new byte[] {1})))
        .isInstanceOf(LogoValidationException.class);
    verify(objectStorage, never()).store(any(), any(), any());
  }

  @Test
  @DisplayName("14.3-LOGO-SVC-006 P0 missing settings row is a not-configured error")
  void missingSettings() {
    when(settings.findFirstByOrderBySingletonKeyAsc()).thenReturn(Optional.empty());

    assertThatThrownBy(service::get).isInstanceOf(LogoNotConfiguredException.class);
  }
}
