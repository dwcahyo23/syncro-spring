package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.org.application.UserBindingService.UserBindingMutationForbiddenException;
import com.syncro.org.application.UserBindingService.UserNotFoundException;
import com.syncro.org.infrastructure.JobTitleEntity;
import com.syncro.org.infrastructure.JobTitleRepository;
import com.syncro.org.infrastructure.db.SystemRoleEntity;
import com.syncro.org.infrastructure.db.SystemRoleRepository;
import com.syncro.org.infrastructure.db.UserJobBindingEntity;
import com.syncro.org.infrastructure.db.UserJobBindingRepository;
import com.syncro.org.infrastructure.db.UserRoleBindingEntity;
import com.syncro.org.infrastructure.db.UserRoleBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserBindingServiceTest {

  @Mock private UserJobBindingRepository jobBindings;
  @Mock private UserRoleBindingRepository roleBindings;
  @Mock private AuthUserRepository users;
  @Mock private JobTitleRepository jobTitles;
  @Mock private SystemRoleRepository systemRoles;
  @Mock private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);

  private UserBindingService service() {
    return new UserBindingService(jobBindings, roleBindings, users, jobTitles, systemRoles, auditLog, clock);
  }

  @Test
  void setJobReplacesExistingBinding() {
    var userId = UUID.randomUUID();
    var jobTitleId = UUID.randomUUID();
    var existingId = UUID.randomUUID();
    when(users.existsById(userId)).thenReturn(true);
    when(jobTitles.findById(jobTitleId)).thenReturn(Optional.of(
        new com.syncro.org.infrastructure.JobTitleEntity(existingId, "TECH", "Technician", null,
            Instant.now(clock), Instant.now(clock))));
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.of(
        new UserJobBindingEntity(existingId, userId, jobTitleId, userId, Instant.now(clock))));
    when(jobBindings.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(roleBindings.findByUserId(userId)).thenReturn(List.of());

    var result = service().setJob(manager(), userId, jobTitleId);

    verify(jobBindings).delete(any());
    var captor = ArgumentCaptor.forClass(UserJobBindingEntity.class);
    verify(jobBindings).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getJobTitleId()).isEqualTo(jobTitleId);
    assertThat(result.job().jobTitleId()).isEqualTo(jobTitleId);
  }

  @Test
  void setJobNullClearsBinding() {
    var userId = UUID.randomUUID();
    var oldJobId = UUID.randomUUID();
    var existing = new UserJobBindingEntity(UUID.randomUUID(), userId, oldJobId, userId,
        Instant.now(clock));
    when(users.existsById(userId)).thenReturn(true);
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.of(existing))
        .thenReturn(Optional.empty()); // first call finds, second after delete returns empty
    when(roleBindings.findByUserId(userId)).thenReturn(List.of());

    var result = service().setJob(manager(), userId, null);

    verify(jobBindings).delete(existing);
    verify(jobBindings, never()).saveAndFlush(any());
    assertThat(result.job()).isNull();
  }

  @Test
  void addRolePersistsNewBinding() {
    var userId = UUID.randomUUID();
    var roleId = UUID.randomUUID();
    when(users.existsById(userId)).thenReturn(true);
    when(systemRoles.findById(roleId)).thenReturn(Optional.of(
        new SystemRoleEntity(roleId, "ADMIN", "Admin", 100, true, null,
            Instant.now(clock), Instant.now(clock))));
    when(roleBindings.findByUserIdAndSystemRoleId(userId, roleId)).thenReturn(Optional.empty());
    when(roleBindings.saveAndFlush(any())).thenAnswer(invocation -> {
      var saved = (UserRoleBindingEntity) invocation.getArgument(0);
      when(roleBindings.findByUserId(userId)).thenReturn(List.of(saved));
      return saved;
    });

    var result = service().addRole(manager(), userId, roleId, true);

    assertThat(result.roles()).hasSize(1);
    assertThat(result.roles().getFirst().systemRoleId()).isEqualTo(roleId);
    assertThat(result.roles().getFirst().override()).isTrue();
  }

  @Test
  void addRoleUpdatesOverrideInPlace() {
    var userId = UUID.randomUUID();
    var roleId = UUID.randomUUID();
    var bindingId = UUID.randomUUID();
    when(users.existsById(userId)).thenReturn(true);
    when(systemRoles.findById(roleId)).thenReturn(Optional.of(
        new SystemRoleEntity(roleId, "ADMIN", "Admin", 100, true, null,
            Instant.now(clock), Instant.now(clock))));
    when(roleBindings.findByUserIdAndSystemRoleId(userId, roleId)).thenReturn(Optional.of(
        new UserRoleBindingEntity(bindingId, userId, roleId, false, userId, Instant.now(clock))));
    when(roleBindings.saveAndFlush(any())).thenAnswer(invocation -> {
      var saved = (UserRoleBindingEntity) invocation.getArgument(0);
      when(roleBindings.findByUserId(userId)).thenReturn(List.of(saved));
      return saved;
    });

    var result = service().addRole(manager(), userId, roleId, true);

    var captor = ArgumentCaptor.forClass(UserRoleBindingEntity.class);
    verify(roleBindings).saveAndFlush(captor.capture());
    assertThat(captor.getValue().getId()).isEqualTo(bindingId);
    assertThat(captor.getValue().isOverride()).isTrue();
    assertThat(result.roles()).hasSize(1);
  }

  @Test
  void removeRoleDeletesBinding() {
    var userId = UUID.randomUUID();
    var roleId = UUID.randomUUID();
    var bindingId = UUID.randomUUID();
    when(roleBindings.findById(bindingId)).thenReturn(Optional.of(
        new UserRoleBindingEntity(bindingId, userId, roleId, false, userId, Instant.now(clock))));

    service().removeRole(manager(), userId, bindingId);

    verify(roleBindings).delete(any());
  }

  @Test
  void removeRoleRejectsMismatchedUser() {
    var bindingId = UUID.randomUUID();
    when(roleBindings.findById(bindingId)).thenReturn(Optional.of(
        new UserRoleBindingEntity(bindingId, UUID.randomUUID(), UUID.randomUUID(), false,
            UUID.randomUUID(), Instant.now(clock))));

    assertThatThrownBy(() -> service().removeRole(manager(), UUID.randomUUID(), bindingId))
        .isInstanceOf(UserBindingService.RoleBindingNotFoundException.class);
  }

  @Test
  void mutationRejectedForNonManagerRole() {
    assertThatThrownBy(() -> service().setJob(technician(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(UserBindingMutationForbiddenException.class);
  }

  @Test
  void setJobRejectsUnknownUser() {
    when(users.existsById(any())).thenReturn(false);
    assertThatThrownBy(() -> service().setJob(manager(), UUID.randomUUID(), UUID.randomUUID()))
        .isInstanceOf(UserNotFoundException.class);
  }

  private AuthenticatedUser manager() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "manager", ApplicationRole.MANAGER_MAINTENANCE);
  }

  private AuthenticatedUser technician() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "tech", ApplicationRole.TECHNICIAN);
  }
}