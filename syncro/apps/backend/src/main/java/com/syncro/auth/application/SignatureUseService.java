package com.syncro.auth.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.infrastructure.SignatureUseEntity;
import com.syncro.auth.infrastructure.SignatureUseRepository;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records every application of a signature (story 22-3, FR-132/133/175): one
 * {@code signature_uses} row per signed subject carrying the signer, the signature
 * reference (id + bucket/key/sha256 copied from {@code user_signatures} at signing
 * time), module, subject, action, reason, request metadata (ip, user-agent) and a
 * server-clock {@code signed_at} — plus an immutable {@code SIGNATURE_USE} audit row.
 *
 * <p>Used by the new PM paths (checklist approve, execution verify). The workorder
 * approve flow keeps its own write path so its {@code WORKORDER_SIGNATURE} audit type
 * and pre-22-3 null-reference contract stay byte-identical (design note 22-3).
 */
@Service
public class SignatureUseService {

  private final SignatureUseRepository signatureUses;
  private final UserSignatureRepository userSignatures;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public SignatureUseService(SignatureUseRepository signatureUses, UserSignatureRepository userSignatures,
      AuditLogWriter auditLog, Clock clock) {
    this.signatureUses = signatureUses;
    this.userSignatures = userSignatures;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  /**
   * Writes one signature-use row + audit row. Runs inside the caller's transaction so the
   * use row commits atomically with the business mutation it evidences.
   *
   * @param signatureId optional stored {@code user_signatures} id; when present its
   *                    bucket/key/sha256 are copied onto the use row (the reference at
   *                    signing time). When null, {@code objectKey} alone is recorded and
   *                    the id/hash columns stay null (legacy reference-less posture).
   * @param objectKey   the Garage object key applied (from the stored row when
   *                    {@code signatureId} is set, else the caller-supplied key)
   */
  @Transactional
  public SignatureUseEntity record(AuthenticatedUser signer, UUID signatureId, String objectKey, String module,
      String subjectType, String subjectId, String action, String reason, String ipAddress,
      String userAgent) {
    var stored = signatureId == null ? null
        : userSignatures.findById(signatureId).orElse(null);
    if (stored != null && !stored.getUserId().equals(UUID.fromString(signer.id()))) {
      // Review 22-3 P1: a stored signature evidences ITS owner — the signer may not
      // apply someone else's signature. (Unknown ids degrade to null reference
      // columns: pm_executions.spv_signature_id carries no FK, so the legacy
      // contract accepts any UUID — see spec Design Notes.)
      throw new SignatureUseForbiddenException();
    }
    var now = Instant.now(clock);
    var saved = signatureUses.saveAndFlush(new SignatureUseEntity(
        UUID.randomUUID(),
        UUID.fromString(signer.id()),
        stored == null ? null : stored.getId(),
        module, subjectType, subjectId, action, reason,
        stored == null ? null : stored.getBucket(),
        stored != null ? stored.getObjectKey() : objectKey,
        stored == null ? null : stored.getSha256(),
        null, null,
        truncate(ipAddress, 64), userAgent, now, now));

    var values = new LinkedHashMap<String, Object>();
    values.put("module", module);
    values.put("subjectType", subjectType);
    values.put("subjectId", subjectId);
    values.put("action", action);
    values.put("signerId", String.valueOf(saved.getSignerId()));
    values.put("signedAt", saved.getSignedAt().toString());
    if (saved.getSignatureId() != null) {
      values.put("signatureId", saved.getSignatureId());
    }
    if (saved.getSignatureObjectKey() != null) {
      values.put("signatureObjectKey", saved.getSignatureObjectKey());
    }
    if (saved.getSignatureSha256() != null) {
      values.put("signatureSha256", saved.getSignatureSha256());
    }
    auditLog.record(signer, new AuditRecord(AuditAction.CREATE, AuditEntityType.SIGNATURE_USE,
        saved.getId(), module + ":" + subjectType + ":" + subjectId, null, null, Map.copyOf(values), null));
    return saved;
  }

  private static String truncate(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    return value.length() > maxLength ? value.substring(0, maxLength) : value;
  }

  /** The signer applied a signature that belongs to another user (review 22-3 P1). */
  public static class SignatureUseForbiddenException extends RuntimeException {
  }
}
