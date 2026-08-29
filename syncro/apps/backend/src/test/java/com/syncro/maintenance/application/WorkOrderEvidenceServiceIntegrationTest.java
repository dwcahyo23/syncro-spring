package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceCommand;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceForbiddenException;
import com.syncro.maintenance.application.WorkOrderEvidenceService.ValidationException;
import com.syncro.storage.application.ObjectStorageService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * DW-140: real PostgreSQL integration test for the workorder evidence service, mirroring
 * the SparepartImageServiceIntegrationTest pattern (mock Garage via ObjectStorageService,
 * real DB). Exercises key format, content-type handling, the access gate, list ordering,
 * and delete (row removal + storage delete).
 */
@Testcontainers
@SpringBootTest(properties = {
    "server.port=0",
    "spring.lifecycle.timeout-per-shutdown-phase=5s",
    "REDIS_HOST=localhost",
    "REDIS_PORT=6379",
    "INFLUXDB_HOST=localhost",
    "INFLUXDB_PORT=8086",
    "INFLUXDB_USERNAME=test",
    "INFLUXDB_PASSWORD=test",
    "INFLUXDB_TOKEN=test",
    "INFLUXDB_ORG=test",
    "INFLUXDB_BUCKET=test",
    "SYNCRO_MQTT_HOST=localhost",
    "SYNCRO_MQTT_PORT=1883",
    "SYNCRO_MQTT_USERNAME=test",
    "SYNCRO_MQTT_PASSWORD=test",
    "SYNCRO_MQTT_CLIENT_ID=test",
    "SYNCRO_MQTT_TOPIC_FILTER=syncro/+/telemetry",
    "WAHA_HOST=localhost",
    "WAHA_PORT=3000",
    "WAHA_API_KEY=test",
    "GARAGE_HOST=localhost",
    "GARAGE_S3_PORT=3900",
    "GARAGE_ACCESS_KEY=test",
    "GARAGE_SECRET_KEY=test",
    "GARAGE_BUCKET=test",
    "GARAGE_REGION=garage",
    "OPA_HOST=localhost",
    "OPA_PORT=18181",
    "SYNCRO_OPA_URL=http://localhost:18181",
    "SYNCRO_AUTHZ_ENFORCED_PATHS=",
    "SYNCRO_AUTHZ_DEGRADED_ALLOWLIST=/api/v1/health,/actuator/**",
    "syncro.auth.jwt.secret=test-secret-for-evidence-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password",
    "syncro.workorder.evidence.max-bytes=10485760"
})
@Import(WorkOrderEvidenceServiceIntegrationTest.MockObjectStorageConfig.class)
@Transactional
class WorkOrderEvidenceServiceIntegrationTest {

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  static {
    postgres.withReuse(true);
  }

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);
  }

  @TestConfiguration
  static class MockObjectStorageConfig {
    @Bean
    @Primary
    ObjectStorageService mockObjectStorage() {
      return org.mockito.Mockito.mock(ObjectStorageService.class);
    }
  }

  @Autowired
  private WorkOrderEvidenceService evidenceService;

  @Autowired
  private ObjectStorageService objectStorage;

  @Autowired
  private JdbcTemplate jdbc;

  private static final AuthenticatedUser ADMIN = new AuthenticatedUser(
      UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

  private String workOrderId;

  @BeforeEach
  void setUp() {
    workOrderId = seedChain();
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://garage/" + invocation.getArgument(0));
  }

  @Test
  @DisplayName("10.5-SVC-008 P0 upload persists the attachment row with correct key format")
  void uploadPersistsAttachmentWithCorrectKeyFormat() {
    var data = "evidence data".getBytes(StandardCharsets.UTF_8);

    var view = evidenceService.create(ADMIN, workOrderId,
        new EvidenceCommand("photo.jpg", "image/jpeg", data));

    assertThat(view.filename()).isEqualTo("photo.jpg");
    assertThat(view.contentType()).isEqualTo("image/jpeg");
    assertThat(view.sizeBytes()).isEqualTo(data.length);
    assertThat(view.objectKey()).startsWith("workorders/" + workOrderId + "/");
    assertThat(view.objectKey()).endsWith(".jpg");
    assertThat(view.presignedUrl()).isEqualTo("https://garage/" + view.objectKey());
    verify(objectStorage).store(view.objectKey(), data, "image/jpeg");
  }

  @Test
  @DisplayName("10.5-SVC-009 P0 upload with a non-leader/non-executor user is forbidden")
  void uploadForbiddenForUnauthorizedRole() {
    var viewer = new AuthenticatedUser(UUID.randomUUID().toString(), "viewer@syncro.dev", ApplicationRole.AUDITOR);

    assertThatThrownBy(() -> evidenceService.create(viewer, workOrderId,
        new EvidenceCommand("photo.jpg", "image/jpeg", new byte[100])))
        .isInstanceOf(EvidenceForbiddenException.class);
  }

  @Test
  @DisplayName("10.5-SVC-010 P0 upload with empty filename fails validation")
  void uploadRejectsEmptyFilename() {
    assertThatThrownBy(() -> evidenceService.create(ADMIN, workOrderId,
        new EvidenceCommand("", "image/jpeg", new byte[100])))
        .isInstanceOf(ValidationException.class);
  }

  @Test
  @DisplayName("10.5-SVC-013 P0 list returns attachments ordered by createdAt")
  void listReturnsAttachmentsOrderedByCreatedAt() {
    var data = "attachment".getBytes(StandardCharsets.UTF_8);
    var v1 = evidenceService.create(ADMIN, workOrderId,
        new EvidenceCommand("first.png", "image/png", data));
    var v2 = evidenceService.create(ADMIN, workOrderId,
        new EvidenceCommand("second.pdf", "application/pdf", data));

    var list = evidenceService.list(workOrderId);

    assertThat(list.attachments()).hasSize(2);
    assertThat(list.attachments().get(0).id()).isEqualTo(v1.id());
    assertThat(list.attachments().get(1).id()).isEqualTo(v2.id());
  }

  @Test
  @DisplayName("10.5-SVC-014 P0 delete removes the row and calls objectStorage.delete")
  void deleteRemovesRowAndCallsStorageDelete() {
    var data = "to-delete".getBytes(StandardCharsets.UTF_8);
    var view = evidenceService.create(ADMIN, workOrderId,
        new EvidenceCommand("delete-me.png", "image/png", data));

    evidenceService.delete(ADMIN, workOrderId, view.id());

    assertThat(evidenceService.list(workOrderId).attachments()).isEmpty();
    verify(objectStorage).delete(view.objectKey());
  }

  @Test
  @DisplayName("10.5-SVC-015 P0 unknown workorder on upload is 404 (no store call made)")
  void uploadUnknownWorkorderThrowsNotFound() {
    assertThatThrownBy(() -> evidenceService.create(ADMIN, "WO-NOT-FOUND",
        new EvidenceCommand("orphan.pdf", "application/pdf", new byte[100])))
        .isInstanceOf(WorkOrderEvidenceService.EvidenceWorkOrderNotFoundException.class);
    // The store call must never be reached for a missing workorder (no orphan window).
    verify(objectStorage, org.mockito.Mockito.never())
        .store(anyString(), any(byte[].class), anyString());
  }

  private String seedChain() {
    var plantId = UUID.randomUUID();
    var groupId = UUID.randomUUID();
    var machineId = UUID.randomUUID();
    var categoryId = UUID.randomUUID();
    var woId = "WO-EVIDENCE-" + UUID.randomUUID().toString().substring(0, 8);

    jdbc.update("""
        INSERT INTO plants (id, code, name, created_at, updated_at) VALUES (?::uuid, 'EVD', 'Evidence Plant', now(), now())
        """, plantId.toString());
    jdbc.update("""
        INSERT INTO machine_groups (id, plant_id, name, created_at, updated_at) VALUES (?::uuid, ?::uuid, 'EVD Group', now(), now())
        """, groupId.toString(), plantId.toString());
    jdbc.update("""
        INSERT INTO machines (id, plant_id, machine_group_id, code, name, status, created_at, updated_at)
        VALUES (?::uuid, ?::uuid, ?::uuid, 'EVD-MC', 'EVD Machine', 'ACTIVE', now(), now())
        """, machineId.toString(), plantId.toString(), groupId.toString());
    jdbc.update("""
        INSERT INTO work_order_categories (id, code, label, created_at, updated_at) VALUES (?::uuid, '98', 'EVD Category', now(), now())
        """, categoryId.toString());
    jdbc.update("""
        INSERT INTO work_orders (id, source, status, category_id, machine_id, description, created_at, updated_at, sync_version)
        VALUES (?, 'INTERNAL', 'DONE', ?::uuid, ?::uuid, 'EVD evidence test', now(), now(), 1)
        """, woId, categoryId.toString(), machineId.toString());

    return woId;
  }
}
