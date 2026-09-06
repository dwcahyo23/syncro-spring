package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.SignatureUseEntity;
import com.syncro.auth.infrastructure.SignatureUseRepository;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.maintenance.application.WorkorderSignatureService;
import com.syncro.maintenance.application.WorkorderSignatureService.ApproveSignatureCommand;
import com.syncro.maintenance.application.WorkorderSignatureService.SignatureNotFoundException;
import com.syncro.maintenance.preventive.application.PmExecutionService;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService;
import com.syncro.storage.application.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.TestTransaction;

/**
 * Story 22-3 end-to-end evidence on a real database: upload upserts the single
 * {@code user_signatures} row (bucket/key/contentType/sha256) and deletes the superseded
 * Garage object; WO approve with a stored signature enriches the {@code signature_uses}
 * row (reference + hash + ip + user-agent) and audits; WO approve without a signatureId
 * keeps the legacy null-reference columns; PM checklist approve and PM execution verify
 * each write one {@code signature_uses} row (module=preventive) + SIGNATURE_USE audit.
 *
 * <p>Garage is mocked via {@link ObjectStorageService} (the WorkOrderEvidenceService
 * integration precedent); PostgreSQL is real (uniqueness, FKs, partial unique).
 * Mutations run after {@code commitSeed()} so the service transactions (and the
 * afterCommit supersede-delete) observe committed seed rows — same pattern as
 * AuthAuditIntegrationTest.
 */
class SignatureUseIntegrationTest extends AbstractPostgresIntegrationTest {

  @MockitoBean
  private ObjectStorageService objectStorage;

  @Autowired
  private UserSignatureService userSignaturesService;
  @Autowired
  private WorkorderSignatureService woSignatures;
  @Autowired
  private PreventiveChecklistService checklists;
  @Autowired
  private PmExecutionService executions;
  @Autowired
  private UserSignatureRepository userSignatures;
  @Autowired
  private SignatureUseRepository signatureUses;
  @Autowired
  private AuditLogRepository auditLogs;
  @Autowired
  private JdbcTemplate jdbc;

  private static final byte[] PNG = "signature-bytes".getBytes(StandardCharsets.UTF_8);

  private static String sha256(byte[] data) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    } catch (Exception exception) {
      throw new IllegalStateException(exception);
    }
  }

  // -- AC1: upload upsert + supersede delete ----------------------------------------

  @Test
  @DisplayName("22-3-INT-001 P0 upload stores one row with bucket/key/contentType/sha256; re-upload replaces and deletes the prior object")
  void uploadUpsertsSingleRowAndSupersedes() {
    when(objectStorage.store(anyString(), any(), anyString())).thenAnswer(i -> i.getArgument(0));
    when(objectStorage.presignGetUrl(anyString())).thenAnswer(i -> "https://garage/" + i.getArgument(0));
    var userId = seedUser("sig-upload");
    commitSeed();

    var first = userSignaturesService.store(ownerOf(userId), userId, "image/png", PNG);
    assertThat(first.created()).isTrue();
    var stored = userSignatures.findByUserId(userId).orElseThrow();
    assertThat(userSignatures.findByUserId(userId)).isPresent();
    assertThat(stored.getBucket()).isEqualTo("test");
    assertThat(stored.getObjectKey()).isEqualTo(first.view().objectKey());
    assertThat(stored.getContentType()).isEqualTo("image/png");
    assertThat(stored.getSha256()).isEqualTo(sha256(PNG));

    // Re-upload: still exactly one row, new key, prior object deleted after commit.
    var second = userSignaturesService.store(ownerOf(userId), userId, "image/jpeg", "other".getBytes());
    assertThat(second.created()).isFalse();
    var replaced = userSignatures.findByUserId(userId).orElseThrow();
    assertThat(replaced.getObjectKey()).isEqualTo(second.view().objectKey());
    assertThat(replaced.getSha256()).isEqualTo(sha256("other".getBytes()));
    verify(objectStorage).delete(stored.getObjectKey());
  }

  // -- AC2: WO approve enrichment + backward compatibility ---------------------------

  @Test
  @DisplayName("22-3-INT-002 P0 WO approve with signatureId enriches the use row (reference + hash + ip + ua) and audits")
  void woApproveWithSignatureEnrichesUseRow() {
    when(objectStorage.presignGetUrl(anyString())).thenAnswer(i -> "https://garage/" + i.getArgument(0));
    var admin = seedAdmin("sig-wo-admin");
    var woId = seedWorkOrder("sig-wo");
    // The approver owns the stored signature (review 22-3 P1 ownership gate).
    var signatureId = seedStoredSignature(UUID.fromString(admin.id()), "user-signatures/wo.png", "deadbeef");
    commitSeed();

    var result = woSignatures.approve(admin, woId,
        new ApproveSignatureCommand("client/legacy-key.png", "Leader", signatureId, "10.9.8.7", "IT-Agent"));

    var use = signatureUses.findBySubjectTypeAndSubjectId("WORK_ORDER", woId).orElseThrow();
    assertThat(use.getId()).isEqualTo(result.id());
    assertThat(use.getSignatureId()).isEqualTo(signatureId);
    assertThat(use.getSignatureBucket()).isEqualTo("test");
    assertThat(use.getSignatureObjectKey()).isEqualTo("user-signatures/wo.png");
    assertThat(use.getSignatureSha256()).isEqualTo("deadbeef");
    assertThat(use.getIpAddress()).isEqualTo("10.9.8.7");
    assertThat(use.getUserAgent()).isEqualTo("IT-Agent");
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(use.getId())).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.WORKORDER_SIGNATURE);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    });
  }

  @Test
  @DisplayName("22-3-INT-007 P0 WO approve with ANOTHER user's stored signature is forbidden (review 22-3 P1)")
  void woApproveCrossUserSignatureForbidden() {
    var admin = seedAdmin("sig-cross-admin");
    var otherUserId = seedUser("sig-cross-other");
    var woId = seedWorkOrder("sig-cross");
    var signatureId = seedStoredSignature(otherUserId, "user-signatures/other.png", "cafe");
    commitSeed();

    assertThatThrownBy(() -> woSignatures.approve(admin, woId,
        new ApproveSignatureCommand("k", "Leader", signatureId, null, null)))
        .isInstanceOf(WorkorderSignatureService.SignatureForbiddenException.class);
    assertThat(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", woId)).isFalse();
  }

  @Test
  @DisplayName("22-3-INT-008 P0 WO approve with a pre-V16 stored signature (null sha256) succeeds, no NPE (review 22-3 P2)")
  void woApproveNullSha256SignatureSucceeds() {
    var admin = seedAdmin("sig-nullhash-admin");
    var woId = seedWorkOrder("sig-nullhash");
    var signatureId = seedStoredSignature(UUID.fromString(admin.id()), "user-signatures/legacy.png", null);
    commitSeed();

    woSignatures.approve(admin, woId,
        new ApproveSignatureCommand("k", "Leader", signatureId, "10.0.0.1", "UA"));

    var use = signatureUses.findBySubjectTypeAndSubjectId("WORK_ORDER", woId).orElseThrow();
    assertThat(use.getSignatureId()).isEqualTo(signatureId);
    assertThat(use.getSignatureSha256()).isNull();
  }

  @Test
  @DisplayName("22-3-INT-003 P0 WO approve without signatureId keeps the legacy null-reference columns byte-identically")
  void woApproveWithoutSignatureIsUnchanged() {
    var userId = seedUser("sig-legacy");
    var admin = seedAdmin("sig-legacy-admin");
    var woId = seedWorkOrder("sig-legacy");
    commitSeed();

    woSignatures.approve(admin, woId, new ApproveSignatureCommand("client/legacy-key.png", "Leader"));

    var use = signatureUses.findBySubjectTypeAndSubjectId("WORK_ORDER", woId).orElseThrow();
    assertThat(use.getSignatureId()).isNull();
    assertThat(use.getSignatureBucket()).isNull();
    assertThat(use.getSignatureObjectKey()).isEqualTo("client/legacy-key.png");
    assertThat(use.getSignatureSha256()).isNull();
    assertThat(use.getIpAddress()).isNull();
    assertThat(use.getUserAgent()).isNull();
    assertThat(use.getSignerId()).isNotNull();
  }

  @Test
  @DisplayName("22-3-INT-004 P1 WO approve with an unknown signatureId is 404 SIGNATURE_NOT_FOUND")
  void woApproveUnknownSignature() {
    var userId = seedUser("sig-404");
    var admin = seedAdmin("sig-404-admin");
    var woId = seedWorkOrder("sig-404");
    commitSeed();

    assertThatThrownBy(() -> woSignatures.approve(admin, woId,
        new ApproveSignatureCommand("client/k.png", "Leader", UUID.randomUUID(), null, null)))
        .isInstanceOf(SignatureNotFoundException.class);
    assertThat(signatureUses.existsBySubjectTypeAndSubjectId("WORK_ORDER", woId)).isFalse();
  }

  // -- AC3: PM flows write use rows ---------------------------------------------------

  @Test
  @DisplayName("22-3-INT-005 P0 PM checklist approve writes one signature_uses row (module=preventive) + SIGNATURE_USE audit")
  void pmChecklistApproveWritesUseRow() {
    var userId = seedUser("sig-pmcl");
    var admin = seedAdmin("sig-pmcl-admin");
    var scheduleId = seedChecklistSchedule(userId);
    commitSeed();

    checklists.approve(admin, scheduleId, new PreventiveChecklistService.ApproveCommand(
        "preventive/sig.png", "Leader", "ok", "10.0.0.5", "PM-Agent"));

    var uses = jdbc.queryForList(
        "SELECT module, subject_type, subject_id, action, signature_object_key, ip_address, user_agent "
            + "FROM signature_uses WHERE subject_type = 'PREVENTIVE_SCHEDULE' AND subject_id = ?::text",
        scheduleId);
    assertThat(uses).hasSize(1);
    var use = uses.getFirst();
    assertThat(use.get("module")).isEqualTo("preventive");
    assertThat(use.get("action")).isEqualTo("APPROVE_CHECKLIST");
    assertThat(use.get("signature_object_key")).isEqualTo("preventive/sig.png");
    assertThat(use.get("ip_address")).isEqualTo("10.0.0.5");
    assertThat(use.get("user_agent")).isEqualTo("PM-Agent");
    var useId = jdbc.queryForObject(
        "SELECT id FROM signature_uses WHERE subject_type = 'PREVENTIVE_SCHEDULE' AND subject_id = ?::text",
        UUID.class, scheduleId);
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(useId)).anySatisfy(entry -> {
      assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.SIGNATURE_USE);
      assertThat(entry.getAction()).isEqualTo(AuditAction.CREATE);
    });
  }

  @Test
  @DisplayName("22-3-INT-006 P0 PM execution verify writes one signature_uses row referencing the stored signature")
  void pmExecutionVerifyWritesUseRow() {
    var userId = seedUser("sig-pmex");
    var admin = seedAdmin("sig-pmex-admin");
    // The SPV signer owns the stored signature (review 22-3 P1 ownership gate).
    var signatureId = seedStoredSignature(UUID.fromString(admin.id()), "user-signatures/spv.png", "cafe1234");
    var executionId = seedCompletedExecution(userId);
    commitSeed();

    executions.verify(admin, executionId, signatureId, "10.0.0.6", "SPV-Agent");

    var use = signatureUses.findBySubjectTypeAndSubjectId("PM_EXECUTION", executionId.toString()).orElseThrow();
    assertThat(use.getModule()).isEqualTo("preventive");
    assertThat(use.getAction()).isEqualTo("VERIFY_EXECUTION");
    assertThat(use.getSignatureId()).isEqualTo(signatureId);
    assertThat(use.getSignatureSha256()).isEqualTo("cafe1234");
    assertThat(use.getIpAddress()).isEqualTo("10.0.0.6");
    assertThat(auditLogs.findByEntityIdOrderByCreatedAtAsc(use.getId())).anySatisfy(entry ->
        assertThat(entry.getEntityType()).isEqualTo(AuditEntityType.SIGNATURE_USE));
  }

  @Test
  @DisplayName("22-3-INT-009 P0 PM execution verify with ANOTHER user's signature is forbidden (review 22-3 P1)")
  void pmExecutionVerifyCrossUserForbidden() {
    var userId = seedUser("sig-pmex-cross");
    var admin = seedAdmin("sig-pmex-cross-admin");
    var signatureId = seedStoredSignature(userId, "user-signatures/tech.png", "beef");
    var executionId = seedCompletedExecution(userId);
    commitSeed();

    assertThatThrownBy(() -> executions.verify(admin, executionId, signatureId, null, null))
        .isInstanceOf(SignatureUseService.SignatureUseForbiddenException.class);
    assertThat(signatureUses.existsBySubjectTypeAndSubjectId("PM_EXECUTION", executionId.toString())).isFalse();
  }

  @Test
  @DisplayName("22-3-INT-010 P0 PM execution verify without a signature writes NO use row (review 22-3 P6)")
  void pmExecutionVerifyWithoutSignatureWritesNoUseRow() {
    var userId = seedUser("sig-pmex-nosig");
    var admin = seedAdmin("sig-pmex-nosig-admin");
    var executionId = seedCompletedExecution(userId);
    commitSeed();

    executions.verify(admin, executionId, null, null, null);

    assertThat(signatureUses.existsBySubjectTypeAndSubjectId("PM_EXECUTION", executionId.toString())).isFalse();
  }

  // -------------------------------------------------------------------------
  // Seeding helpers (JDBC — the point is real schema constraints, not entities)
  // -------------------------------------------------------------------------

  private UUID seedUser(String tag) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled,"
            + " created_at, updated_at) VALUES (?::uuid, ?, 'hash', 'TECHNICIAN', TRUE, NOW(), NOW())",
        id, tag + "-" + id + "@syncro.test");
    return id;
  }

  private UUID seedStoredSignature(UUID userId, String objectKey, String sha) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO user_signatures (id, user_id, bucket, object_key, content_type, sha256,"
            + " signature_failed_attempts, created_at, updated_at)"
            + " VALUES (?::uuid, ?::uuid, 'test', ?, 'image/png', ?, 0, NOW(), NOW())",
        id, userId, objectKey, sha);
    return id;
  }

  /** plant → group → machine → category → PENDING_REVIEW workorder; returns the WO id. */
  private String seedWorkOrder(String tag) {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var woId = "WO-223-" + tag + "-" + UUID.randomUUID().toString().substring(0, 6);
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?, ?, NOW(), NOW())", plantId, "P-" + plantId, "Sig Plant");
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?, NOW(), NOW())", groupId, plantId, "Sig Group");
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'ACTIVE', NOW(), NOW())",
        machineId, plantId, groupId, "MC-" + machineId, "Sig Machine");
    jdbc.update("INSERT INTO work_order_categories (id, code, label, created_at, updated_at)"
        + " VALUES (?::uuid, ?, ?, NOW(), NOW())", categoryId,
        "C-" + categoryId.toString().substring(0, 8), "Sig Cat");
    jdbc.update("INSERT INTO work_orders (id, source, status, category_id, machine_id, description,"
            + " created_at, updated_at, sync_version) VALUES (?, 'INTERNAL', 'PENDING_REVIEW', ?::uuid,"
            + " ?::uuid, 'Sig test', NOW(), NOW(), 1)",
        woId, categoryId, machineId);
    return woId;
  }

  /** program → IN_PROGRESS schedule → submitted checklist result; returns the schedule id. */
  private String seedChecklistSchedule(UUID userId) {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var programId = UUID.randomUUID();
    var scheduleId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?, ?, NOW(), NOW())", plantId, "P-" + plantId, "Sig Plant");
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?, NOW(), NOW())", groupId, plantId, "Sig Group");
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'ACTIVE', NOW(), NOW())",
        machineId, plantId, groupId, "MC-" + machineId, "Sig Machine");
    jdbc.update("INSERT INTO preventive_programs (id, machine_id, category, schedule_type, day_of_month,"
            + " title, active, auto_workorder, created_by, created_at, updated_at)"
            + " VALUES (?::uuid, ?::uuid, 'MECHANICAL', 'MONTHLY', 15, 'Sig program', TRUE, FALSE, ?::uuid,"
            + " NOW(), NOW())", programId, machineId, userId);
    jdbc.update("INSERT INTO preventive_schedules (id, program_id, machine_id, due_date, status,"
            + " created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, 'IN_PROGRESS', NOW(), NOW())",
        scheduleId, programId, machineId, LocalDate.of(2026, 9, 15));
    jdbc.update("INSERT INTO preventive_checklist_results (id, schedule_id, performed_by, completed_at,"
            + " created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, NOW(), NOW(), NOW())",
        UUID.randomUUID(), scheduleId, userId);
    return scheduleId.toString();
  }

  /** pm_work_order → completed (unverified) execution; returns the execution id. */
  private UUID seedCompletedExecution(UUID userId) {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var pmWoId = UUID.randomUUID();
    var executionId = UUID.randomUUID();
    jdbc.update("INSERT INTO plants (id, code, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?, ?, NOW(), NOW())", plantId, "P-" + plantId, "Sig Plant");
    jdbc.update("INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?, NOW(), NOW())", groupId, plantId, "Sig Group");
    jdbc.update("INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)"
        + " VALUES (?::uuid, ?::uuid, ?::uuid, ?, ?, 'ACTIVE', NOW(), NOW())",
        machineId, plantId, groupId, "MC-" + machineId, "Sig Machine");
    jdbc.update("INSERT INTO pm_work_orders (id, machine_id, status, assigned_technician_id, created_at,"
            + " updated_at) VALUES (?::uuid, ?::uuid, 'IN_PROGRESS', ?::uuid, NOW(), NOW())",
        pmWoId, machineId, userId);
    jdbc.update("INSERT INTO pm_executions (id, pm_wo_id, technician_id, started_at, completed_at,"
            + " created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, NOW(), NOW(), NOW(), NOW())",
        executionId, pmWoId, userId);
    return executionId;
  }

  private static AuthenticatedUser ownerOf(UUID userId) {
    return new AuthenticatedUser(userId.toString(), "owner@syncro.test", ApplicationRole.TECHNICIAN);
  }

  /** SUPER_ADMIN actor backed by a real auth_users row (signature_uses.signer_id FK). */
  private AuthenticatedUser seedAdmin(String tag) {
    var id = UUID.randomUUID();
    jdbc.update("INSERT INTO auth_users (id, login_identifier, password_hash, application_role, enabled,"
            + " created_at, updated_at) VALUES (?::uuid, ?, 'hash', 'SUPER_ADMIN', TRUE, NOW(), NOW())",
        id, tag + "-" + id + "@syncro.test");
    return new AuthenticatedUser(id.toString(), tag + "@syncro.test", ApplicationRole.SUPER_ADMIN);
  }

  /** Commits the test-managed transaction so service transactions see the seed rows. */
  private static void commitSeed() {
    if (TestTransaction.isActive()) {
      TestTransaction.flagForCommit();
      TestTransaction.end();
    }
  }
}
