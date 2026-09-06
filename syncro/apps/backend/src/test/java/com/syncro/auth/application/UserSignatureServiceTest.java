package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.UserSignatureService.SignatureForbiddenException;
import com.syncro.auth.application.UserSignatureService.SignatureNotFoundException;
import com.syncro.auth.application.UserSignatureService.StorageException;
import com.syncro.auth.application.UserSignatureService.UnsupportedContentTypeException;
import com.syncro.auth.application.UserSignatureService.ValidationException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.config.GarageProperties;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Story 22-3 unit tests: upload upserts the single row, computes sha256 from the
 * in-memory bytes, deletes the superseded Garage object after commit, and enforces the
 * owner-or-SUPER_ADMIN write gate / SUPER_ADMIN-only read of another user's signature.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserSignatureServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
  private static final byte[] PNG = "fake-png-bytes".getBytes(StandardCharsets.UTF_8);

  @Mock
  private UserSignatureRepository signatures;
  @Mock
  private ObjectStorageService objectStorage;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID userId = UUID.fromString("9d9e0f10-1111-2222-3333-444455556666");
  private GarageProperties garage = new GarageProperties("http://garage", "ak", "sk", "syncro-test", "garage", 300L);

  private UserSignatureService service;

  @BeforeEach
  void setUp() {
    service = new UserSignatureService(signatures, objectStorage, garage, auditLog, clock);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://garage/" + invocation.getArgument(0));
    when(signatures.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  private AuthenticatedUser owner() {
    return new AuthenticatedUser(userId.toString(), "owner@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private AuthenticatedUser admin() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private AuthenticatedUser other() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "other@syncro.dev", ApplicationRole.TECHNICIAN);
  }

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  @Test
  @DisplayName("22.3-SIG-001 P0 first upload stores bytes, computes sha256, inserts one row, audits CREATE")
  void firstUploadStoresAndHashes() {
    when(signatures.findByUserId(userId)).thenReturn(Optional.empty());

    var result = service.store(owner(), userId, "image/png", PNG);

    assertThat(result.created()).isTrue();
    assertThat(result.view().sha256()).isEqualTo(sha256(PNG));
    assertThat(result.view().bucket()).isEqualTo("syncro-test");
    assertThat(result.view().objectKey()).startsWith("user-signatures/" + userId + "/").endsWith(".png");
    assertThat(result.view().presignedUrl()).isEqualTo("https://garage/" + result.view().objectKey());
    verify(objectStorage).store(result.view().objectKey(), PNG, "image/png");
    verify(signatures).saveAndFlush(any(UserSignatureEntity.class));
    verify(auditLog).record(eq(owner()), org.mockito.ArgumentMatchers.argThat(
        (com.syncro.audit.application.AuditRecord record) -> record.entityType() == AuditEntityType.SIGNATURE_USE
            && record.action() == AuditAction.CREATE));
  }

  @Test
  @DisplayName("22.3-SIG-002 P0 re-upload replaces the row content and deletes the superseded object")
  void reUploadReplacesAndDeletesSuperseded() {
    var oldKey = "user-signatures/" + userId + "/old.png";
    var existing = new UserSignatureEntity(UUID.randomUUID(), userId, "syncro-test", oldKey, "image/png",
        sha256("old".getBytes(StandardCharsets.UTF_8)), 0, null, NOW.minusSeconds(60), NOW.minusSeconds(60));
    when(signatures.findByUserId(userId)).thenReturn(Optional.of(existing));

    var result = service.store(owner(), userId, "image/png", PNG);

    assertThat(result.created()).isFalse();
    assertThat(existing.getObjectKey()).isEqualTo(result.view().objectKey());
    assertThat(existing.getSha256()).isEqualTo(sha256(PNG));
    verify(objectStorage).delete(oldKey);
    verify(auditLog).record(eq(owner()), org.mockito.ArgumentMatchers.argThat(
        (com.syncro.audit.application.AuditRecord record) -> record.action() == AuditAction.UPDATE));
  }

  @Test
  @DisplayName("22.3-SIG-003 P0 a failed superseded delete never fails the completed upload")
  void supersededDeleteFailureIsSwallowed() {
    var oldKey = "user-signatures/" + userId + "/old.png";
    var existing = new UserSignatureEntity(UUID.randomUUID(), userId, "syncro-test", oldKey, "image/png",
        null, 0, null, NOW.minusSeconds(60), NOW.minusSeconds(60));
    when(signatures.findByUserId(userId)).thenReturn(Optional.of(existing));
    org.mockito.Mockito.doThrow(new ObjectStorageException("garage down")).when(objectStorage).delete(oldKey);

    var result = service.store(owner(), userId, "image/png", PNG);

    assertThat(result.view().objectKey()).isNotEqualTo(oldKey);
  }

  @Test
  @DisplayName("22.3-SIG-004 P0 non-image content type is 415 and stores nothing")
  void nonImageRejected() {
    assertThatThrownBy(() -> service.store(owner(), userId, "application/pdf", PNG))
        .isInstanceOf(UnsupportedContentTypeException.class);
    verify(objectStorage, never()).store(anyString(), any(), anyString());
  }

  @Test
  @DisplayName("22.3-SIG-005 P0 empty bytes are a validation error")
  void emptyBytesRejected() {
    assertThatThrownBy(() -> service.store(owner(), userId, "image/png", new byte[0]))
        .isInstanceOf(ValidationException.class);
    assertThatThrownBy(() -> service.store(owner(), userId, "image/png", null))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("22.3-SIG-006 P0 a non-owner non-admin cannot store or read another user's signature")
  void writeAndForeignReadGates() {
    assertThatThrownBy(() -> service.store(other(), userId, "image/png", PNG))
        .isInstanceOf(SignatureForbiddenException.class);
    assertThatThrownBy(() -> service.get(other(), userId))
        .isInstanceOf(SignatureForbiddenException.class);
    verify(signatures, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.3-SIG-007 P0 SUPER_ADMIN may store for and read another user")
  void superAdminMayActForAnyUser() {
    when(signatures.findByUserId(userId)).thenReturn(Optional.empty());
    var result = service.store(admin(), userId, "image/png", PNG);
    assertThat(result.created()).isTrue();

    when(signatures.findByUserId(userId)).thenReturn(Optional.of(new UserSignatureEntity(
        UUID.randomUUID(), userId, "syncro-test", "k", "image/png", "h", 0, null, NOW, NOW)));
    assertThat(service.get(admin(), userId).userId()).isEqualTo(userId);
  }

  @Test
  @DisplayName("22.3-SIG-008 P0 reading an absent signature is 404 SIGNATURE_NOT_FOUND")
  void getAbsentIsNotFound() {
    when(signatures.findByUserId(userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(owner(), userId)).isInstanceOf(SignatureNotFoundException.class);
  }

  @Test
  @DisplayName("22.3-SIG-009 P0 storage failures surface as StorageException (502), never raw SDK errors")
  void storeFailureWrapped() {
    when(objectStorage.store(anyString(), any(), anyString())).thenThrow(new ObjectStorageException("down"));
    assertThatThrownBy(() -> service.store(owner(), userId, "image/png", PNG))
        .isInstanceOf(StorageException.class);
  }

  @Test
  @DisplayName("22.3-SIG-011 P0 concurrent first-upload race → conflict + the loser's object is deleted (review 22-3 P4)")
  void concurrentFirstUploadRaceIsConflict() {
    when(signatures.findByUserId(userId)).thenReturn(Optional.empty());
    when(signatures.saveAndFlush(any(UserSignatureEntity.class)))
        .thenThrow(new org.springframework.dao.DataIntegrityViolationException("uq_user_signatures_user"));

    assertThatThrownBy(() -> service.store(owner(), userId, "image/png", PNG))
        .isInstanceOf(UserSignatureService.SignatureUploadConflictException.class);
    // The just-stored object must not be orphaned.
    verify(objectStorage).delete(anyString());
  }

  @Test
  @DisplayName("22.3-SIG-012 P0 presign failure after a successful store yields a null URL, not a 502 (review 22-3 P5)")
  void presignFailureYieldsNullUrl() {
    when(signatures.findByUserId(userId)).thenReturn(Optional.empty());
    when(objectStorage.presignGetUrl(anyString())).thenThrow(new ObjectStorageException("presign down"));

    var result = service.store(owner(), userId, "image/png", PNG);

    assertThat(result.created()).isTrue();
    assertThat(result.view().presignedUrl()).isNull();
    assertThat(result.view().sha256()).isEqualTo(sha256(PNG));
  }
}
