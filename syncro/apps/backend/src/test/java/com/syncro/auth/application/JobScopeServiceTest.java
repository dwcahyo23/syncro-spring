package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.machine.domain.ResponsibilityLevel;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JobScopeServiceTest {

  @Mock
  private UserJobScopeReader reader;

  private JobScopeService service;

  @BeforeEach
  void setUp() {
    service = new JobScopeService(reader);
  }

  private static AuthenticatedUser manage() {
    return new AuthenticatedUser(UUID.randomUUID().toString(), "manage@syncro.dev", ApplicationRole.MANAGE);
  }

  @Test
  void ladderMatchesResponsibilityLevelDeclarationOrder() {
    // The auth-side ladder is contract strings; it must mirror the domain enum's rank order
    // (declaration order is authoritative — cf. escalation ordering). A drift here silently
    // changes who passes LEADER-or-above, so pin it.
    assertThat(service.levelOrder()).containsExactlyElementsOf(
        Arrays.stream(ResponsibilityLevel.values()).map(Enum::name).toList());
  }

  @Test
  void manageHoldsQualifyingLevel_passes() {
    var user = manage();
    when(reader.hasAnyLevel(eq(UUID.fromString(user.id())), anySet())).thenReturn(true);

    assertThat(service.hasLevelOrAbove(user, "LEADER")).isTrue();
  }

  @Test
  void manageBelowLevel_fails() {
    var user = manage();
    when(reader.hasAnyLevel(eq(UUID.fromString(user.id())), anySet())).thenReturn(false);

    assertThat(service.hasLevelOrAbove(user, "LEADER")).isFalse();
  }

  @Test
  void qualifyingSetContainsOnlyLevelsAtOrAboveTheMinimum() {
    var user = manage();
    when(reader.hasAnyLevel(eq(UUID.fromString(user.id())), anySet())).thenReturn(true);

    service.hasLevelOrAbove(user, "LEADER");

    verify(reader).hasAnyLevel(eq(UUID.fromString(user.id())), eq(Set.of("LEADER", "SPV", "MANAGER")));
  }

  @Test
  void superAdminBypassesWithoutConsultingReader() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    assertThat(service.hasLevelOrAbove(admin, "LEADER")).isTrue();
    verify(reader, never()).hasAnyLevel(any(), any());
  }

  @Test
  void unknownLevelFailsFastEvenForSuperAdmin() {
    var admin = new AuthenticatedUser(UUID.randomUUID().toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);

    assertThatThrownBy(() -> service.hasLevelOrAbove(admin, "BOSS"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BOSS");
  }

  @Test
  void requireLevelOrAbove_throwsJobScopeForbiddenWhenBelow() {
    var user = manage();
    when(reader.hasAnyLevel(eq(UUID.fromString(user.id())), anySet())).thenReturn(false);

    assertThatThrownBy(() -> service.requireLevelOrAbove(user, "LEADER"))
        .isInstanceOf(JobScopeForbiddenException.class)
        .hasMessageContaining("LEADER");
  }
}
