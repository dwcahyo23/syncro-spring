package com.syncro.maintenance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.maintenance.application.WorkOrderCategoryService.CreateWorkOrderCategoryCommand;
import com.syncro.maintenance.application.WorkOrderCategoryService.DuplicateWorkOrderCategoryCodeException;
import com.syncro.maintenance.application.WorkOrderCategoryService.UpdateWorkOrderCategoryCommand;
import com.syncro.maintenance.application.WorkOrderCategoryService.WorkOrderCategoryMutationForbiddenException;
import com.syncro.maintenance.domain.workorder.WorkOrderCategory;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderCategoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkOrderCategoryServiceTest {

  @Mock
  private WorkOrderCategoryRepository categories;

  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);

  private WorkOrderCategoryService service;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    service = new WorkOrderCategoryService(categories, auditLog, clock);
  }

  @Test
  @DisplayName("10.1-SVC-001 P0 gate denies STAFF_MAINTENANCE, TECHNICIAN and AUDITOR")
  void gateDeniesBelowSectionLeader() {
    for (var role : List.of(ApplicationRole.STAFF_MAINTENANCE, ApplicationRole.TECHNICIAN, ApplicationRole.AUDITOR)) {
      var user = user(role);
      assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCategoryCommand("01", "Breakdown")))
          .isInstanceOf(WorkOrderCategoryMutationForbiddenException.class);
      assertThatThrownBy(() -> service.update(user, "01", new UpdateWorkOrderCategoryCommand("01", "Breakdown")))
          .isInstanceOf(WorkOrderCategoryMutationForbiddenException.class);
    }
  }

  @Test
  @DisplayName("10.1-SVC-002 P0 gate allows SECTION_LEADER, MAINTENANCE_LEADER, MANAGER_MAINTENANCE and SUPER_ADMIN")
  void gateAllowsSectionLeaderAndAbove() {
    for (var role : List.of(ApplicationRole.SECTION_LEADER, ApplicationRole.MAINTENANCE_LEADER,
        ApplicationRole.MANAGER_MAINTENANCE, ApplicationRole.SUPER_ADMIN)) {
      var user = user(role);
      when(categories.existsByCode("01")).thenReturn(false);
      when(categories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

      var created = service.create(user, new CreateWorkOrderCategoryCommand(" 01 ", " Breakdown "));

      assertThat(created).isEqualTo(new WorkOrderCategory("01", "Breakdown"));
    }
  }

  @Test
  @DisplayName("10.1-SVC-003 P1 create persists and audits CREATE")
  void createPersistsAndAudits() {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(categories.existsByCode("01")).thenReturn(false);
    when(categories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var created = service.create(user, new CreateWorkOrderCategoryCommand("01", "Breakdown"));

    assertThat(created.code()).isEqualTo("01");
    verify(categories).saveAndFlush(any(WorkOrderCategoryEntity.class));
    verify(auditLog).record(eq(user),
        org.mockito.ArgumentMatchers.argThat(record -> record.action() == AuditAction.CREATE
            && record.entityType() == AuditEntityType.WORK_ORDER_CATEGORY
            && record.entityLabel().equals("01")
            && record.previousValue() == null));
  }

  @Test
  @DisplayName("10.1-SVC-004 P0 duplicate code throws DuplicateWorkOrderCategoryCodeException")
  void duplicateCodeThrows() {
    var user = user(ApplicationRole.MANAGER_MAINTENANCE);
    when(categories.existsByCode("01")).thenReturn(true);

    assertThatThrownBy(() -> service.create(user, new CreateWorkOrderCategoryCommand("01", "Breakdown")))
        .isInstanceOf(DuplicateWorkOrderCategoryCodeException.class);
  }

  @Test
  @DisplayName("10.1-SVC-005 P1 update audits UPDATE and can rename the code")
  void updateAuditsAndRenames() {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);
    var entity = new WorkOrderCategoryEntity(UUID.randomUUID(), "01", "Breakdown",
        UUID.fromString(user.id()), Instant.now(clock), Instant.now(clock));
    when(categories.findByCode("01")).thenReturn(Optional.of(entity));
    when(categories.existsByCode("02")).thenReturn(false);
    when(categories.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var updated = service.update(user, "01", new UpdateWorkOrderCategoryCommand("02", "Preventive"));

    assertThat(updated).isEqualTo(new WorkOrderCategory("02", "Preventive"));
    verify(auditLog).record(eq(user),
        org.mockito.ArgumentMatchers.argThat(record -> record.action() == AuditAction.UPDATE
            && record.entityType() == AuditEntityType.WORK_ORDER_CATEGORY
            && record.entityLabel().equals("02")
            && record.previousValue() != null
            && record.newValue() != null));
  }

  @Test
  @DisplayName("10.1-SVC-006 P1 update rejects a code collision with another category")
  void updateRejectsCodeCollision() {
    var user = user(ApplicationRole.SECTION_LEADER);
    var entity = new WorkOrderCategoryEntity(UUID.randomUUID(), "01", "Breakdown",
        UUID.fromString(user.id()), Instant.now(clock), Instant.now(clock));
    when(categories.findByCode("01")).thenReturn(Optional.of(entity));
    when(categories.existsByCode("02")).thenReturn(true);

    assertThatThrownBy(() -> service.update(user, "01", new UpdateWorkOrderCategoryCommand("02", "Preventive")))
        .isInstanceOf(DuplicateWorkOrderCategoryCodeException.class);
  }

  @Test
  @DisplayName("10.1-SVC-007 P1 update on a missing code throws WorkOrderCategoryNotFoundException")
  void updateMissingCodeThrows() {
    var user = user(ApplicationRole.SUPER_ADMIN);
    when(categories.findByCode("99")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.update(user, "99", new UpdateWorkOrderCategoryCommand("99", "Nope")))
        .isInstanceOf(WorkOrderCategoryService.WorkOrderCategoryNotFoundException.class);
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
