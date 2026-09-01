package com.syncro.org.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.infrastructure.JobTitleEntity;
import com.syncro.org.infrastructure.JobTitleRepository;
import com.syncro.org.infrastructure.db.SystemRoleEntity;
import com.syncro.org.infrastructure.db.SystemRoleRepository;
import com.syncro.org.infrastructure.db.UserJobBindingEntity;
import com.syncro.org.infrastructure.db.UserJobBindingRepository;
import com.syncro.org.infrastructure.db.UserRoleBindingEntity;
import com.syncro.org.infrastructure.db.UserRoleBindingRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EffectiveRoleReaderTest {

  private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

  @Mock private UserJobBindingRepository jobBindings;
  @Mock private UserRoleBindingRepository roleBindings;
  @Mock private JobTitleRepository jobTitles;
  @Mock private SystemRoleRepository systemRoles;

  private EffectiveRoleReader reader() {
    return new EffectiveRoleReader(jobBindings, roleBindings, jobTitles, systemRoles);
  }

  @Test
  void userWithoutBindingsGetsApplicationRoleOnly() {
    var userId = UUID.randomUUID();
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.empty());
    when(roleBindings.findByUserId(userId)).thenReturn(List.of());

    var roles = reader().read(userId, ApplicationRole.TECHNICIAN);

    assertThat(roles).containsExactly("TECHNICIAN");
  }

  @Test
  void jobTitleDefaultSystemRoleIsIncluded() {
    var userId = UUID.randomUUID();
    var jobTitleId = UUID.randomUUID();
    var systemRoleId = UUID.randomUUID();
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.of(
        new UserJobBindingEntity(UUID.randomUUID(), userId, jobTitleId, userId, NOW)));
    when(roleBindings.findByUserId(userId)).thenReturn(List.of());
    when(jobTitles.findById(jobTitleId)).thenReturn(Optional.of(
        new JobTitleEntity(jobTitleId, "TECH", "Technician", null, null, true, systemRoleId, NOW, NOW)));
    when(systemRoles.findById(systemRoleId)).thenReturn(Optional.of(
        new SystemRoleEntity(systemRoleId, "TECHNICIAN", "Technician", 40, true, null, NOW, NOW)));

    var roles = reader().read(userId, ApplicationRole.STAFF_MAINTENANCE);

    assertThat(roles).containsExactly("STAFF_MAINTENANCE", "TECHNICIAN");
  }

  @Test
  void roleBindingsAreIncluded() {
    var userId = UUID.randomUUID();
    var roleId = UUID.randomUUID();
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.empty());
    when(roleBindings.findByUserId(userId)).thenReturn(List.of(
        new UserRoleBindingEntity(UUID.randomUUID(), userId, roleId, true, userId, NOW)));
    when(systemRoles.findById(roleId)).thenReturn(Optional.of(
        new SystemRoleEntity(roleId, "SECTION_LEADER", "Section Leader", 60, true, null, NOW, NOW)));

    var roles = reader().read(userId, ApplicationRole.TECHNICIAN);

    assertThat(roles).containsExactly("TECHNICIAN", "SECTION_LEADER");
  }

  @Test
  void missingJobTitleIsSkippedSilently() {
    var userId = UUID.randomUUID();
    var jobTitleId = UUID.randomUUID();
    when(jobBindings.findByUserId(userId)).thenReturn(Optional.of(
        new UserJobBindingEntity(UUID.randomUUID(), userId, jobTitleId, userId, NOW)));
    when(roleBindings.findByUserId(userId)).thenReturn(List.of());
    when(jobTitles.findById(jobTitleId)).thenReturn(Optional.empty());

    var roles = reader().read(userId, ApplicationRole.TECHNICIAN);

    assertThat(roles).containsExactly("TECHNICIAN");
  }
}