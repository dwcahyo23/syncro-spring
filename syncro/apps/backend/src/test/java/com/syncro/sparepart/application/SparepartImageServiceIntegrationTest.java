package com.syncro.sparepart.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentEntity;
import com.syncro.auth.infrastructure.AuthUserPlantAssignmentRepository;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.audit.infrastructure.AuditLogEntity;
import com.syncro.audit.infrastructure.AuditLogRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.domain.ResponsibilityLevel;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.machine.infrastructure.MachineResponsibilityRepository;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.sparepart.application.SparepartImageService.ImageNotFoundException;
import com.syncro.sparepart.application.SparepartImageService.MutationForbiddenException;
import com.syncro.sparepart.application.SparepartImageService.NotFoundException;
import com.syncro.sparepart.application.SparepartImageService.SparepartImageCommand;
import com.syncro.sparepart.application.SparepartImageService.StorageException;
import com.syncro.sparepart.application.SparepartImageService.ValidationException;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
import com.syncro.sparepart.infrastructure.SparepartEntity;
import com.syncro.sparepart.infrastructure.SparepartRepository;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity;
import com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository;
import com.syncro.storage.application.ObjectStorageException;
import com.syncro.storage.application.ObjectStorageService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
    "syncro.auth.jwt.secret=test-secret-for-auth-integration-32x",
    "syncro.auth.jwt.issuer=syncro-test",
    "syncro.auth.jwt.ttl-minutes=30",
    "syncro.auth.local-admin.enabled=false",
    "syncro.auth.local-admin.login-identifier=admin@syncro.dev",
    "syncro.auth.local-admin.password=test-password",
    "syncro.sparepart.image.max-bytes=5242880"
})
@Testcontainers
@Transactional
class SparepartImageServiceIntegrationTest {
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

  @MockitoBean
  private ObjectStorageService objectStorage;

  @Autowired
  private SparepartImageService imageService;

  @Autowired
  private SparepartService sparepartService;

  @Autowired
  private SparepartRepository spareparts;

  @Autowired
  private SparepartTaxonomyRepository taxonomy;

  @Autowired
  private MachineRepository machines;

  @Autowired
  private MachineGroupRepository machineGroups;

  @Autowired
  private com.syncro.auth.infrastructure.PlantRepository plants;

  @Autowired
  private AuthUserRepository users;

  @Autowired
  private AuthUserPlantAssignmentRepository assignments;

  @Autowired
  private MachineResponsibilityRepository responsibilities;

  @Autowired
  private AuditLogRepository auditLogs;

  @Autowired
  private PasswordEncoder passwordEncoder;

  @Test
  @DisplayName("8.4-SVC-001 P0 LEADER-scoped MANAGE upload persists key, stores object, audits CREATE")
  void leaderManageUploadPersistsKeyAndAuditsCreate() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "img-upload@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));

    var data = new byte[1024 * 1024];
    var view = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", data));

    assertThat(view.sparepartId()).isEqualTo(sparepart.getId());
    assertThat(view.objectKey()).startsWith("spareparts/" + sparepart.getId() + "/").endsWith(".png");
    assertThat(view.presignedUrl()).isEqualTo("https://presigned/" + view.objectKey());
    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey())
        .isEqualTo(view.objectKey());

    ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
    verify(objectStorage).store(keyCaptor.capture(), dataCaptor.capture(), eq("image/png"));
    assertThat(keyCaptor.getValue()).isEqualTo(view.objectKey());
    assertThat(dataCaptor.getValue()).isEqualTo(data);

    var audit = latestAuditEntryFor(sparepart.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.CREATE);
    assertThat(audit.getEntityType()).isEqualTo(AuditEntityType.SPAREPART);
    assertThat(audit.getEntityLabel()).isEqualTo(sparepart.getCode());
    assertThat(audit.getPlantId()).isEqualTo(machine.getPlant().getId());
    assertThat(audit.getActorName()).isEqualTo("img-upload@syncro.dev");
    assertThat(audit.getPreviousValue()).isNull();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    var newValue = mapper.readTree(audit.getNewValue());
    assertThat(newValue.get("imageObjectKey").asText()).isEqualTo(view.objectKey());
  }

  @Test
  @DisplayName("8.4-SVC-002 P0 replace deletes the previous object before storing and audits UPDATE")
  void replaceDeletesPreviousObjectAndAuditsUpdate() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "img-replace@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));

    var first = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1, 2, 3}));
    var second = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.jpg", "image/jpeg", new byte[] {4, 5, 6}));

    assertThat(second.objectKey()).isNotEqualTo(first.objectKey()).endsWith(".jpg");
    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey())
        .isEqualTo(second.objectKey());
    var inOrder = inOrder(objectStorage);
    inOrder.verify(objectStorage).delete(first.objectKey());
    inOrder.verify(objectStorage).store(eq(second.objectKey()), any(byte[].class), eq("image/jpeg"));

    var audit = latestAuditEntryFor(sparepart.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.UPDATE);
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readTree(audit.getPreviousValue()).get("imageObjectKey").asText())
        .isEqualTo(first.objectKey());
    assertThat(mapper.readTree(audit.getNewValue()).get("imageObjectKey").asText())
        .isEqualTo(second.objectKey());
  }

  @Test
  @DisplayName("8.4-SVC-003 P0 store failure after old delete leaves entity untouched with no audit")
  void storeFailureAfterOldDeleteLeavesEntityUntouched() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-store-fail@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var first = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));
    var entityReference = spareparts.findById(sparepart.getId()).orElseThrow();
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenThrow(new ObjectStorageException("s3 down"));
    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.jpg", "image/jpeg", new byte[] {2})), StorageException.class);

    assertThat(exception).isNotNull();
    verify(objectStorage).delete(first.objectKey());
    assertThat(entityReference.getImageObjectKey()).isEqualTo(first.objectKey());
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.4-SVC-004 P0 old-delete failure surfaces 502 without touching the entity")
  void oldDeleteFailureSurfacesStorageError() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-del-fail@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    var first = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));
    var entityReference = spareparts.findById(sparepart.getId()).orElseThrow();
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    doThrow(new ObjectStorageException("s3 down")).when(objectStorage).delete(anyString());
    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.jpg", "image/jpeg", new byte[] {2})), StorageException.class);

    assertThat(exception).isNotNull();
    assertThat(entityReference.getImageObjectKey()).isEqualTo(first.objectKey());
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.4-SVC-005 P0 remove deletes the object, clears the key, and audits DELETE")
  void removeDeletesObjectClearsKeyAndAuditsDelete() throws Exception {
    var user = persistedUser(ApplicationRole.MANAGE, "img-remove@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));
    var uploaded = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));

    imageService.delete(user, sparepart.getId());

    verify(objectStorage).delete(uploaded.objectKey());
    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey()).isNull();
    var audit = latestAuditEntryFor(sparepart.getId());
    assertThat(audit.getAction()).isEqualTo(AuditAction.DELETE);
    assertThat(audit.getNewValue()).isNull();
    var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
    assertThat(mapper.readTree(audit.getPreviousValue()).get("imageObjectKey").asText())
        .isEqualTo(uploaded.objectKey());
  }

  @Test
  @DisplayName("8.4-SVC-006 P0 remove-when-absent is an idempotent no-op with no storage call and no audit")
  void removeWhenAbsentIsNoop() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-noop@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    imageService.delete(user, sparepart.getId());

    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey()).isNull();
    verify(objectStorage, never()).delete(anyString());
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.4-SVC-007 P1 GET presigns when an image exists")
  void getPresignsWhenImageExists() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-get@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));
    var uploaded = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));

    var view = imageService.get(user, sparepart.getId());

    assertThat(view.objectKey()).isEqualTo(uploaded.objectKey());
    assertThat(view.presignedUrl()).isEqualTo("https://presigned/" + uploaded.objectKey());
  }

  @Test
  @DisplayName("8.4-SVC-008 P1 GET without image returns image-not-found")
  void getWithoutImageReturnsImageNotFound() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-no-get@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);

    assertThatThrownBy(() -> imageService.get(user, sparepart.getId()))
        .isInstanceOf(ImageNotFoundException.class);
    verify(objectStorage, never()).presignGetUrl(anyString());
  }

  @Test
  @DisplayName("8.4-SVC-009 P0 non-image content type is rejected with no storage call")
  void badContentTypeRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-badtype@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);

    for (var bad : List.of("application/pdf", "text/plain", "image/svg+xml", "image/png;charset=utf-8")) {
      var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
          new SparepartImageCommand("part.pdf", bad, new byte[] {1})), ValidationException.class);
      assertThat(exception).as(bad).isNotNull();
      assertThat(exception.getFieldErrors()).containsKey("contentType");
    }
    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-010 P0 oversize upload is rejected with no storage call")
  void oversizeRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-oversize@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);

    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png",
            new byte[5 * 1024 * 1024 + 1])), ValidationException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getFieldErrors()).containsKey("data");
    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-011 P0 empty file is rejected with no storage call")
  void emptyFileRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-empty@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);

    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[0])), ValidationException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getFieldErrors()).containsKey("data");
    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-012 P0 blank filename is rejected with no storage call")
  void blankFilenameRejected() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-name@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);

    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand(" ", "image/png", new byte[] {1})), ValidationException.class);

    assertThat(exception).isNotNull();
    assertThat(exception.getFieldErrors()).containsKey("filename");
    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-013 P0 MANAGE below LEADER job scope is denied with no mutation, storage, or audit")
  void manageBelowLeaderDenied() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-noscope@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    assertThatThrownBy(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1})))
        .isInstanceOf(JobScopeForbiddenException.class)
        .hasMessageContaining("LEADER");
    assertThatThrownBy(() -> imageService.delete(user, sparepart.getId()))
        .isInstanceOf(JobScopeForbiddenException.class);

    verifyNoInteractions(objectStorage);
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey()).isNull();
  }

  @Test
  @DisplayName("8.4-SVC-014 P0 VIEWER is rejected by the app-role gate before job scope")
  void viewerRejectedByAppRoleGate() {
    var viewer = persistedUser(ApplicationRole.VIEWER, "img-viewer@syncro.dev");
    var machine = machine();
    assign(viewer, machine.getPlant());
    assignJobScope(viewer, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));

    assertThatThrownBy(() -> imageService.replace(viewer, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1})))
        .isInstanceOf(MutationForbiddenException.class);
    assertThatThrownBy(() -> imageService.delete(viewer, sparepart.getId()))
        .isInstanceOf(MutationForbiddenException.class);

    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-015 P1 SUPER_ADMIN bypasses job scope without responsibility rows")
  void superAdminBypassesJobScope() {
    var admin = persistedUser(ApplicationRole.SUPER_ADMIN, "img-bypass@syncro.dev");
    var sparepart = createdSparepart(admin);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));

    var view = imageService.replace(admin, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));

    assertThat(view.objectKey()).isNotBlank();
  }

  @Test
  @DisplayName("8.4-SVC-016 P1 out-of-plant sparepart is masked as not found")
  void wrongPlantMaskedAsNotFound() {
    var outsider = persistedUser(ApplicationRole.MANAGE, "img-outsider@syncro.dev");
    assignJobScope(outsider, machine(), ResponsibilityLevel.MANAGER);
    var sparepart = createdSparepart(authenticatedUser(ApplicationRole.SUPER_ADMIN));

    assertThatThrownBy(() -> imageService.replace(outsider, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1})))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> imageService.get(outsider, sparepart.getId()))
        .isInstanceOf(NotFoundException.class);

    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-017 P1 unknown sparepart returns not found")
  void unknownSparepartReturnsNotFound() {
    var admin = authenticatedUser(ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> imageService.replace(admin, UUID.randomUUID(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1})))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> imageService.get(admin, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> imageService.delete(admin, UUID.randomUUID()))
        .isInstanceOf(NotFoundException.class);
    verifyNoInteractions(objectStorage);
  }

  @Test
  @DisplayName("8.4-SVC-018 P1 plant-scoped user without job scope can still read the image")
  void plantScopedReaderReadsWithoutJobScope() {
    var reader = persistedUser(ApplicationRole.MANAGE, "img-reader@syncro.dev");
    var writer = persistedUser(ApplicationRole.SUPER_ADMIN, "img-writer@syncro.dev");
    var machine = machine();
    assign(reader, machine.getPlant());
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));
    var sparepart = createdSparepart(writer);
    var uploaded = imageService.replace(writer, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));

    var view = imageService.get(reader, sparepart.getId());

    assertThat(view.objectKey()).isEqualTo(uploaded.objectKey());
  }

  @Test
  @DisplayName("8.4-SVC-019 P1 storage-down on upload surfaces storage error with no entity change")
  void storageDownOnUpload() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-storage-down@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenThrow(new ObjectStorageException("garage unreachable"));
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    var exception = catchThrowableOfType(() -> imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1})), StorageException.class);

    assertThat(exception).isNotNull();
    assertThat(spareparts.findById(sparepart.getId()).orElseThrow().getImageObjectKey()).isNull();
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
  }

  @Test
  @DisplayName("8.4-SVC-020 P1 storage-down on get surfaces storage error")
  void storageDownOnGet() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-get-down@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));
    when(objectStorage.presignGetUrl(anyString()))
        .thenThrow(new ObjectStorageException("garage unreachable"));

    var exception = catchThrowableOfType(() -> imageService.get(user, sparepart.getId()),
        StorageException.class);

    assertThat(exception).isNotNull();
  }

  @Test
  @DisplayName("8.4-SVC-021 P1 storage-down on remove leaves entity with old key and no audit")
  void storageDownOnRemove() {
    var user = persistedUser(ApplicationRole.MANAGE, "img-remove-down@syncro.dev");
    var machine = machine();
    assign(user, machine.getPlant());
    assignJobScope(user, machine, ResponsibilityLevel.LEADER);
    var sparepart = createdSparepart(user);
    when(objectStorage.store(anyString(), any(byte[].class), anyString()))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(objectStorage.presignGetUrl(anyString()))
        .thenAnswer(invocation -> "https://presigned/" + invocation.getArgument(0));
    var uploaded = imageService.replace(user, sparepart.getId(),
        new SparepartImageCommand("part.png", "image/png", new byte[] {1}));
    var entityReference = spareparts.findById(sparepart.getId()).orElseThrow();
    doThrow(new ObjectStorageException("garage unreachable")).when(objectStorage).delete(anyString());
    long auditCountBefore = sparepartAuditCount(sparepart.getId());

    var exception = catchThrowableOfType(() -> imageService.delete(user, sparepart.getId()),
        StorageException.class);

    assertThat(exception).isNotNull();
    assertThat(entityReference.getImageObjectKey()).isEqualTo(uploaded.objectKey());
    assertThat(sparepartAuditCount(sparepart.getId())).isEqualTo(auditCountBefore);
  }

  private SparepartEntity createdSparepart(AuthenticatedUser actor) {
    var refs = taxonomyRefs();
    var created = sparepartService.create(actor,
        new SparepartCommand(machine().getId(), refs.category().getId(), refs.brand().getId(),
            refs.kind().getId(), refs.type().getId()));
    return spareparts.findById(created.id()).orElseThrow();
  }

  private long sparepartAuditCount(UUID sparepartId) {
    return auditLogs.findAll().stream()
        .filter(entry -> entry.getEntityType() == AuditEntityType.SPAREPART)
        .filter(entry -> sparepartId.equals(entry.getEntityId()))
        .count();
  }

  private AuditLogEntity latestAuditEntryFor(UUID entityId) {
    return auditLogs.findAll(Sort.by(Sort.Direction.DESC, "createdAt"))
        .stream()
        .filter(entry -> entityId.equals(entry.getEntityId()))
        .findFirst()
        .orElseThrow();
  }

  private MachineEntity machine() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var plant = plants.findByCodeIgnoreCase("PLANT-1")
        .orElseGet(() -> plants.saveAndFlush(new com.syncro.auth.infrastructure.PlantEntity(
            UUID.randomUUID(), "PLANT-1", "Plant 1", now, now)));
    var group = machineGroups.findByPlantIdAndNameIgnoreCase(plant.getId(), "Assembly")
        .orElseGet(() -> machineGroups.saveAndFlush(new MachineGroupEntity(
            UUID.randomUUID(), plant, "Assembly", now, now)));
    return machines.findByPlantIdAndCodeIgnoreCase(plant.getId(), "MCH-1")
        .orElseGet(() -> machines.saveAndFlush(new MachineEntity(
            UUID.randomUUID(), plant, group, "MCH-1", "Machine 1", MachineStatus.ACTIVE,
            null, null, null, List.of(), now, now)));
  }

  private SparepartTaxonomyRefs taxonomyRefs() {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var category = taxonomy.findByDimensionAndCodeIgnoreCase(
        SparepartTaxonomyDimension.CATEGORY, "ELECTRIC")
        .orElseGet(() -> taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.CATEGORY, "ELECTRIC", "Electric", now, now)));
    var unique = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    return new SparepartTaxonomyRefs(
        category,
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.BRAND, "WECON-" + unique,
            "Wecon " + unique, category, now, now)),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.KIND, "PLC-" + unique,
            "PLC " + unique, category, now, now)),
        taxonomy.saveAndFlush(new SparepartTaxonomyEntity(
            UUID.randomUUID(), SparepartTaxonomyDimension.TYPE, "LX5-" + unique,
            "LX5 " + unique, category, now, now)));
  }

  private AuthenticatedUser persistedUser(ApplicationRole role, String loginIdentifier) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var user = users.saveAndFlush(new AuthUserEntity(UUID.randomUUID(), loginIdentifier,
        passwordEncoder.encode("syncro-test-password"), role, true, now, now));
    return new AuthenticatedUser(user.getId().toString(), user.getLoginIdentifier(), role);
  }

  private void assign(AuthenticatedUser user, com.syncro.auth.infrastructure.PlantEntity plant) {
    assignments.saveAndFlush(new AuthUserPlantAssignmentEntity(
        UUID.fromString(user.id()), plant.getId(), Instant.parse("2026-05-28T00:00:00Z")));
  }

  private void assignJobScope(AuthenticatedUser user, MachineEntity machine, ResponsibilityLevel level) {
    var now = Instant.parse("2026-05-28T00:00:00Z");
    var userEntity = users.findById(UUID.fromString(user.id())).orElseThrow();
    responsibilities.saveAndFlush(new com.syncro.machine.infrastructure.MachineResponsibilityEntity(
        UUID.randomUUID(), machine, userEntity, level, now, now));
  }

  private static AuthenticatedUser authenticatedUser(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private record SparepartTaxonomyRefs(
      SparepartTaxonomyEntity category,
      SparepartTaxonomyEntity brand,
      SparepartTaxonomyEntity kind,
      SparepartTaxonomyEntity type) {
  }
}