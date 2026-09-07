package com.syncro.compliance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.syncro.compliance.application.LessonLearnedService.CreateLessonCommand;
import com.syncro.compliance.application.LessonLearnedService.LessonNotFoundException;
import com.syncro.compliance.application.LessonLearnedService.UpdateLessonCommand;
import com.syncro.compliance.application.NonConformanceService.ComplianceForbiddenException;
import com.syncro.compliance.application.NonConformanceService.ComplianceReferenceNotFoundException;
import com.syncro.compliance.application.NonConformanceService.ComplianceValidationException;
import com.syncro.compliance.application.NonConformanceService.DuplicateIdentifierException;
import com.syncro.compliance.infrastructure.db.LessonLearnedEntity;
import com.syncro.compliance.infrastructure.db.LessonLearnedRepository;
import com.syncro.org.application.OperationalScope;
import com.syncro.org.application.OperationalScopeService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 21-3 unit tests for {@link LessonLearnedService}: event-link existence
 * validation (stable 404 codes), duplicate project_id handling, scoped search
 * parameter shaping (LikePattern + sentinels), the six-role gate, and the audit
 * trail with previous/new values.
 */
@ExtendWith(MockitoExtension.class)
class LessonLearnedServiceTest {

  private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private final UUID lessonId = UUID.randomUUID();
  private final UUID userId = UUID.randomUUID();
  private final UUID machineId = UUID.randomUUID();

  @Mock
  private LessonLearnedRepository lessons;
  @Mock
  private OperationalScopeService scopes;
  @Mock
  private AuditLogWriter auditLog;

  private LessonLearnedService service;

  @BeforeEach
  void setUp() {
    service = new LessonLearnedService(lessons, scopes, auditLog, CLOCK);
  }

  private AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(userId.toString(), "u@syncro.test", role);
  }

  private LessonLearnedEntity entity() {
    return new LessonLearnedEntity(lessonId, "PRJ-1", machineId, "KAIZEN",
        "Guide rail wear", "Three guide failures", null, null, null, null, null,
        List.of("mechanical"), null, null, null, null, NOW.minusSeconds(3600),
        NOW.minusSeconds(3600));
  }

  private LessonLearnedRepository.MachineScopeView scopeView(UUID plantId, UUID groupId) {
    return new LessonLearnedRepository.MachineScopeView() {
      @Override
      public UUID getPlantId() {
        return plantId;
      }

      @Override
      public UUID getGroupId() {
        return groupId;
      }
    };
  }

  private void stubVisible(LessonLearnedEntity entity) {
    when(lessons.findById(lessonId)).thenReturn(Optional.of(entity));
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(lessons.countVisible(eq(lessonId), eq(true), any(), any())).thenReturn(1L);
  }

  private CreateLessonCommand command(UUID ncId, UUID eightDId, String workOrderId) {
    return new CreateLessonCommand("PRJ-1", null, null, "Guide rail wear",
        "Three guide failures", null, null, null, null, null, List.of("mechanical"),
        ncId, eightDId, workOrderId, List.of(Map.of("objectKey", "k1", "filename", "f.pdf")));
  }

  @Test
  @DisplayName("21.3-SVC-001 P0 technician/auditor cannot mutate lessons (six-role gate)")
  void readOnlyRolesDenied() {
    assertThatThrownBy(() -> service.create(user(ApplicationRole.TECHNICIAN),
        command(null, null, null)))
        .isInstanceOf(ComplianceForbiddenException.class);
    assertThatThrownBy(() -> service.delete(user(ApplicationRole.AUDITOR), lessonId))
        .isInstanceOf(ComplianceForbiddenException.class);
    verify(lessons, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-002 P0 create persists links + evidence, audits CREATE with new values")
  void createAudits() {
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, null));

    assertThat(view.projectId()).isEqualTo("PRJ-1");
    assertThat(view.evidence()).containsExactly(Map.of("objectKey", "k1", "filename", "f.pdf"));
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.CREATE);
    assertThat(captor.getValue().entityType()).isEqualTo(AuditEntityType.LESSON_LEARNED);
    assertThat(captor.getValue().entityLabel()).isEqualTo("PRJ-1");
    assertThat(captor.getValue().previousValue()).isNull();
    assertThat(captor.getValue().newValue()).containsEntry("projectId", "PRJ-1")
        .containsEntry("title", "Guide rail wear");
  }

  @Test
  @DisplayName("21.3-SVC-003 P0 duplicate project_id → DUPLICATE_IDENTIFIER")
  void duplicateProjectId() {
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
    verify(lessons, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-004 P0 unknown event links → 404 with stable codes")
  void unknownEventLinks() {
    var nc = UUID.randomUUID();
    var eightD = UUID.randomUUID();
    when(lessons.countNonConformance(nc)).thenReturn(0L);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(nc, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("NON_CONFORMANCE_NOT_FOUND"));

    when(lessons.countEightD(eightD)).thenReturn(0L);
    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, eightD, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("EIGHT_D_REPORT_NOT_FOUND"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, "NOPE-404")))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("WORK_ORDER_NOT_FOUND"));
    verify(lessons, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-005 P0 valid event links persist on create")
  void validEventLinksPersist() {
    var nc = UUID.randomUUID();
    when(lessons.countNonConformance(nc)).thenReturn(1L);
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(nc, null, null));

    assertThat(view.ncId()).isEqualTo(nc);
  }

  @Test
  @DisplayName("21.3-SVC-006 P0 search shapes q/tag through LikePattern, sentinel for empty sets")
  void searchParameterShaping() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(), Set.of(), Set.of()));
    when(lessons.findScoped(anyBoolean(), any(), any(), anyString(), anyString()))
        .thenReturn(List.of());

    service.search(user(ApplicationRole.STAFF_MAINTENANCE), "Forming", "setup");

    var plants = ArgumentCaptor.forClass(List.class);
    var groups = ArgumentCaptor.forClass(List.class);
    var patterns = ArgumentCaptor.forClass(String.class);
    verify(lessons).findScoped(eq(false), plants.capture(), groups.capture(),
        patterns.capture(), patterns.capture());
    // q: lowercased escaped %...%; tag: raw escaped %...% for tags::text ilike.
    assertThat(patterns.getAllValues().get(0)).isEqualTo("%forming%");
    assertThat(patterns.getAllValues().get(1)).isEqualTo("%setup%");
    // VG-7: empty scope sets get the never-matching sentinel (native IN cannot be
    // empty) — both plantIds AND groupIds.
    assertThat(plants.getValue()).containsExactly(new UUID(0L, 0L));
    assertThat(groups.getValue()).containsExactly(new UUID(0L, 0L));
  }

  @Test
  @DisplayName("21.3-SVC-007 P0 blank q/tag pass the empty-string sentinel (no filter)")
  void blankSearchSentinel() {
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(lessons.findScoped(anyBoolean(), any(), any(), anyString(), anyString()))
        .thenReturn(List.of());

    service.search(user(ApplicationRole.SUPER_ADMIN), "  ", null);

    verify(lessons).findScoped(eq(true), any(), any(),
        eq(LessonLearnedService.SEARCH_NO_FILTER), eq(LessonLearnedService.SEARCH_NO_FILTER));
  }

  @Test
  @DisplayName("21.3-SVC-008 P0 out-of-scope detail is 404 LESSON_NOT_FOUND (not leaked)")
  void outOfScopeNotFound() {
    when(lessons.findById(lessonId)).thenReturn(Optional.of(entity()));
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(lessons.countVisible(eq(lessonId), eq(false), any(), any())).thenReturn(0L);

    assertThatThrownBy(() -> service.get(user(ApplicationRole.SECTION_LEADER), lessonId))
        .isInstanceOf(LessonNotFoundException.class);
  }

  @Test
  @DisplayName("21.3-SVC-009 P0 partial update: null keeps stored, provided replaces, audits prev/new")
  void partialUpdateKeepsStoredValues() {
    stubVisible(entity());
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.STAFF_MAINTENANCE), lessonId,
        new UpdateLessonCommand("Renamed", null, null, "Add torque check", null, null, null,
            null, null, null, null, null));

    assertThat(view.title()).isEqualTo("Renamed");
    assertThat(view.problemSummary()).isEqualTo("Three guide failures");
    assertThat(view.solution()).isEqualTo("Add torque check");
    assertThat(view.machineId()).isEqualTo(machineId);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.UPDATE);
    assertThat(captor.getValue().previousValue()).containsEntry("title", "Guide rail wear");
    assertThat(captor.getValue().newValue()).containsEntry("title", "Renamed");
  }

  @Test
  @DisplayName("21.3-SVC-010 P0 blank PATCH text rejected with fieldErrors (nothing persisted)")
  void blankUpdateFieldsRejected() {
    stubVisible(entity());

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), lessonId,
        new UpdateLessonCommand("  ", "", " ", "", null, null, null, null, null, null, null,
            null)))
        .isInstanceOfSatisfying(ComplianceValidationException.class,
            e -> assertThat(e.getFieldErrors()).containsKeys("title", "problemSummary",
                "rootCause", "solution"));
    verify(lessons, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-010b P0 PATCH with unknown ncId → 404, nothing persisted (VG-2)")
  void updateUnknownEventLinkRejected() {
    stubVisible(entity());
    var unknownNc = UUID.randomUUID();
    when(lessons.countNonConformance(unknownNc)).thenReturn(0L);

    assertThatThrownBy(() -> service.update(user(ApplicationRole.STAFF_MAINTENANCE), lessonId,
        new UpdateLessonCommand(null, null, null, null, null, null, null, null, null,
            unknownNc, null, null)))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("NON_CONFORMANCE_NOT_FOUND"));
    verify(lessons, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("21.3-SVC-010c P1 happy re-link audits previous/new ncId (VG-2)")
  void updateRelinksEventsWithAudit() {
    stubVisible(entity());
    var newNc = UUID.randomUUID();
    when(lessons.countNonConformance(newNc)).thenReturn(1L);
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(user(ApplicationRole.STAFF_MAINTENANCE), lessonId,
        new UpdateLessonCommand(null, null, null, null, null, null, null, null, null,
            newNc, null, null));

    assertThat(view.ncId()).isEqualTo(newNc);
    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().previousValue().get("ncId")).isNull();
    assertThat(captor.getValue().newValue().get("ncId")).isEqualTo(newNc.toString());
  }

  @Test
  @DisplayName("21.3-SVC-010d P0 create with nonexistent machineId → MACHINE_NOT_FOUND (VG-3)")
  void createUnknownMachine() {
    var cmd = new CreateLessonCommand("PRJ-1", machineId, null, "T", "P", null, null, null,
        null, null, null, null, null, null, null);
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(lessons.findMachineScope(machineId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SECTION_LEADER), cmd))
        .isInstanceOfSatisfying(ComplianceReferenceNotFoundException.class,
            e -> assertThat(e.getCode()).isEqualTo("MACHINE_NOT_FOUND"));
  }

  @Test
  @DisplayName("21.3-SVC-010e P1 machine-linked create audits the machine's plant (VG-4)")
  void createAuditsPlantDimension() {
    var plant = UUID.randomUUID();
    var cmd = new CreateLessonCommand("PRJ-1", machineId, null, "T", "P", null, null, null,
        null, null, null, null, null, null, null);
    when(scopes.derive(any())).thenReturn(new OperationalScope(null, Set.of(), Set.of()));
    when(lessons.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(plant,
        UUID.randomUUID())));
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(user(ApplicationRole.STAFF_MAINTENANCE), cmd);

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().plantId()).isEqualTo(plant);
  }

  @Test
  @DisplayName("21.3-SVC-011 P0 create-time machine scope gate: sibling machine → FORBIDDEN")
  void createScopeGate() {
    var cmd = new CreateLessonCommand("PRJ-1", machineId, null, "T", "P", null, null, null,
        null, null, null, null, null, null, null);
    when(scopes.derive(any())).thenReturn(new OperationalScope(Set.of(UUID.randomUUID()),
        Set.of(UUID.randomUUID()), Set.of()));
    when(lessons.findMachineScope(machineId)).thenReturn(Optional.of(scopeView(
        UUID.randomUUID(), UUID.randomUUID())));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.SECTION_LEADER), cmd))
        .isInstanceOf(ComplianceForbiddenException.class);
  }

  @Test
  @DisplayName("21.3-SVC-012 P1 delete audits DELETE with previous values")
  void deleteAudits() {
    stubVisible(entity());

    service.delete(user(ApplicationRole.MANAGER_MAINTENANCE), lessonId);

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(), captor.capture());
    assertThat(captor.getValue().action()).isEqualTo(AuditAction.DELETE);
    assertThat(captor.getValue().previousValue()).containsEntry("projectId", "PRJ-1");
    assertThat(captor.getValue().newValue()).isNull();
  }

  @Test
  @DisplayName("21.3-SVC-013 P1 duplicate-race on save classified to DUPLICATE_IDENTIFIER (7d)")
  void duplicateRaceClassified() {
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint uq_lesson_learned_project"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, null)))
        .isInstanceOf(DuplicateIdentifierException.class);
  }

  @Test
  @DisplayName("21.3-SVC-014 P1 unrelated constraint violation rethrown, never misclassified")
  void unrelatedRaceRethrown() {
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violated constraint some_other_constraint"));

    assertThatThrownBy(() -> service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, null)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  @DisplayName("21.3-SVC-015 P1 blank workOrderId normalized to null (never FK-violated)")
  void blankWorkOrderLinkNormalized() {
    when(lessons.existsByProjectId("PRJ-1")).thenReturn(false);
    when(lessons.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(user(ApplicationRole.STAFF_MAINTENANCE),
        command(null, null, "   "));

    assertThat(view.workOrderId()).isNull();
    verify(lessons, never()).countWorkOrder(any());
  }
}
