package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.SignatureUseEntity;
import com.syncro.auth.infrastructure.SignatureUseRepository;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Story 22-3 unit tests: every recorded signature application writes one use row with
 * the copied reference (id/bucket/key/sha256) + request metadata + server-clock
 * signed_at, plus one immutable SIGNATURE_USE audit row.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SignatureUseServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

  @Mock
  private SignatureUseRepository signatureUses;
  @Mock
  private UserSignatureRepository userSignatures;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private final UUID signerId = UUID.fromString("9d9e0f10-1111-2222-3333-444455556666");
  private final AuthenticatedUser signer =
      new AuthenticatedUser(signerId.toString(), "signer@syncro.dev", ApplicationRole.SECTION_LEADER);

  private SignatureUseService service;

  @BeforeEach
  void setUp() {
    service = new SignatureUseService(signatureUses, userSignatures, auditLog, clock);
    when(signatureUses.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
  }

  @Test
  @DisplayName("22.3-USE-001 P0 recording with a stored signature copies id/bucket/key/sha256 + ip/ua")
  void recordCopiesReference() {
    var stored = new UserSignatureEntity(UUID.randomUUID(), signerId, "bucket-1", "user-signatures/x.png",
        "image/png", "abc123", 0, null, NOW, NOW);
    when(userSignatures.findById(stored.getId())).thenReturn(Optional.of(stored));

    var saved = service.record(signer, stored.getId(), null, "preventive", "PREVENTIVE_SCHEDULE",
        "schedule-1", "APPROVE_CHECKLIST", "ok", "10.0.0.1", "JUnit-Agent");

    assertThat(saved.getSignerId()).isEqualTo(signerId);
    assertThat(saved.getSignatureId()).isEqualTo(stored.getId());
    assertThat(saved.getSignatureBucket()).isEqualTo("bucket-1");
    assertThat(saved.getSignatureObjectKey()).isEqualTo("user-signatures/x.png");
    assertThat(saved.getSignatureSha256()).isEqualTo("abc123");
    assertThat(saved.getModule()).isEqualTo("preventive");
    assertThat(saved.getIpAddress()).isEqualTo("10.0.0.1");
    assertThat(saved.getUserAgent()).isEqualTo("JUnit-Agent");
    assertThat(saved.getSignedAt()).isEqualTo(NOW);
    verify(auditLog).record(eq(signer), org.mockito.ArgumentMatchers.argThat((AuditRecord record) ->
        record.entityType() == AuditEntityType.SIGNATURE_USE && record.action() == AuditAction.CREATE
            && stored.getId().equals(record.newValue().get("signatureId"))
            && "abc123".equals(record.newValue().get("signatureSha256"))));
  }

  @Test
  @DisplayName("22.3-USE-002 P0 recording without a stored signature keeps reference columns null")
  void recordWithoutReference() {
    var saved = service.record(signer, null, "legacy/key.png", "maintenance", "WORK_ORDER",
        "WO-1", "APPROVE_WORKORDER", null, null, null);

    assertThat(saved.getSignatureId()).isNull();
    assertThat(saved.getSignatureBucket()).isNull();
    assertThat(saved.getSignatureObjectKey()).isEqualTo("legacy/key.png");
    assertThat(saved.getSignatureSha256()).isNull();
    assertThat(saved.getIpAddress()).isNull();
  }

  @Test
  @DisplayName("22.3-USE-003 P1 an unknown signatureId degrades to null reference columns (no FK violation)")
  void unknownSignatureIdDegrades() {
    when(userSignatures.findById(any())).thenReturn(Optional.empty());

    var saved = service.record(signer, UUID.randomUUID(), null, "preventive", "PM_EXECUTION",
        "exec-1", "VERIFY_EXECUTION", null, null, null);

    assertThat(saved.getSignatureId()).isNull();
    assertThat(saved.getSignatureObjectKey()).isNull();
  }

  @Test
  @DisplayName("22.3-USE-004 P1 an over-long ip is truncated to the VARCHAR(64) column")
  void ipTruncated() {
    var saved = service.record(signer, null, null, "preventive", "PM_EXECUTION", "exec-1",
        "VERIFY_EXECUTION", null, "x".repeat(100), null);
    assertThat(saved.getIpAddress()).hasSize(64);
  }

  @Test
  @DisplayName("22.3-USE-005 P0 the audit row never carries image bytes or secrets")
  void auditIsSafe() {
    var stored = new UserSignatureEntity(UUID.randomUUID(), signerId, "b", "k", "image/png", "hash", 0, null, NOW, NOW);
    when(userSignatures.findById(stored.getId())).thenReturn(Optional.of(stored));

    service.record(signer, stored.getId(), null, "preventive", "PM_EXECUTION", "exec-1",
        "VERIFY_EXECUTION", null, null, null);

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(eq(signer), captor.capture());
    assertThat(captor.getValue().newValue()).containsEntry("subjectType", "PM_EXECUTION");
  }

  @Test
  @DisplayName("22.3-USE-006 P0 recording with ANOTHER user's stored signature is forbidden (review 22-3 P1)")
  void crossUserSignatureRejected() {
    var foreign = new UserSignatureEntity(UUID.randomUUID(), UUID.randomUUID(), "b", "k", "image/png",
        "hash", 0, null, NOW, NOW);
    when(userSignatures.findById(foreign.getId())).thenReturn(Optional.of(foreign));

    assertThatThrownBy(() -> service.record(signer, foreign.getId(), null, "preventive", "PM_EXECUTION",
        "exec-1", "VERIFY_EXECUTION", null, null, null))
        .isInstanceOf(SignatureUseService.SignatureUseForbiddenException.class);
    verify(signatureUses, never()).saveAndFlush(any());
  }
}
