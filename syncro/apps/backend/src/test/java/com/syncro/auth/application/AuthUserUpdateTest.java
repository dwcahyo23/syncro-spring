package com.syncro.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.auth.api.AuthDtos.UpdateUserRequest;
import com.syncro.auth.application.AuthService.DuplicateUserIdentifierException;
import com.syncro.auth.application.AuthService.UserMasterForbiddenException;
import com.syncro.auth.application.AuthService.UserNotFoundException;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthUserUpdateTest {
  @Mock
  private AuthUserRepository users;
  @Mock
  private PasswordEncoder passwordEncoder;
  @Mock
  private JwtTokenService tokens;
  @Mock
  private AuditLogWriter auditLog;

  private final Clock clock = Clock.fixed(Instant.parse("2026-08-27T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void updateUserPersistsMasterFields() {
    var userId = UUID.randomUUID();
    var user = user(userId);
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(users.findByNikIgnoreCase("NIK-001")).thenReturn(Optional.empty());
    when(users.findByPhoneNumber("0812-3456")).thenReturn(Optional.empty());
    when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    var view = service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("John Doe", "NIK-001", "0812-3456", null, null));

    assertThat(view.displayName()).isEqualTo("John Doe");
    assertThat(view.nik()).isEqualTo("NIK-001");
    assertThat(view.phoneNumber()).isEqualTo("0812-3456");
  }

  @Test
  void emptyStringClearsToNull() {
    var userId = UUID.randomUUID();
    var user = user(userId);
    user.updateMasterFields("Old", "OLD-NIK", "0811", null, null, Instant.now(clock));
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(users.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    var view = service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("", "", "", null, null));

    assertThat(view.nik()).isNull();
    assertThat(view.phoneNumber()).isNull();
  }

  @Test
  void duplicateNikRejected() {
    var userId = UUID.randomUUID();
    var otherId = UUID.randomUUID();
    var user = user(userId);
    var other = user(otherId);
    other.updateMasterFields("Other", "NIK-001", null, null, null, Instant.now(clock));
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(users.findByNikIgnoreCase("NIK-001")).thenReturn(Optional.of(other));

    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    assertThatThrownBy(() -> service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("John", "NIK-001", null, null, null)))
        .isInstanceOf(DuplicateUserIdentifierException.class);
  }

  @Test
  void duplicatePhoneRejected() {
    var userId = UUID.randomUUID();
    var otherId = UUID.randomUUID();
    var user = user(userId);
    var other = user(otherId);
    other.updateMasterFields("Other", null, "0812-3456", null, null, Instant.now(clock));
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(users.findByNikIgnoreCase("NIK-001")).thenReturn(Optional.empty());
    when(users.findByPhoneNumber("0812-3456")).thenReturn(Optional.of(other));

    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    assertThatThrownBy(() -> service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("John", "NIK-001", "0812-3456", null, null)))
        .isInstanceOf(DuplicateUserIdentifierException.class);
  }

  @Test
  void uniqueIndexViolationMapsToDuplicate() {
    var userId = UUID.randomUUID();
    var user = user(userId);
    when(users.findById(userId)).thenReturn(Optional.of(user));
    when(users.findByNikIgnoreCase("NIK-001")).thenReturn(Optional.empty());
    when(users.findByPhoneNumber("0812-3456")).thenReturn(Optional.empty());
    when(users.saveAndFlush(any())).thenThrow(uniqueViolation("uq_auth_users_nik"));

    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    assertThatThrownBy(() -> service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("John", "NIK-001", "0812-3456", null, null)))
        .isInstanceOf(DuplicateUserIdentifierException.class);
  }

  @Test
  void auditorForbidden() {
    var userId = UUID.randomUUID();
    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    assertThatThrownBy(() -> service.updateUser(actor(ApplicationRole.AUDITOR), userId,
        new UpdateUserRequest("John", null, null, null, null)))
        .isInstanceOf(UserMasterForbiddenException.class);
  }

  @Test
  void unknownUserThrowsNotFound() {
    var userId = UUID.randomUUID();
    when(users.findById(userId)).thenReturn(Optional.empty());
    var service = new AuthService(users, passwordEncoder, tokens, auditLog, clock);
    assertThatThrownBy(() -> service.updateUser(actor(ApplicationRole.MANAGER_MAINTENANCE), userId,
        new UpdateUserRequest("John", null, null, null, null)))
        .isInstanceOf(UserNotFoundException.class);
  }

  private static AuthUserEntity user(UUID id) {
    var now = Instant.parse("2026-08-27T00:00:00Z");
    return new AuthUserEntity(id, "user-" + id + "@syncro.dev", "hash", ApplicationRole.TECHNICIAN, true, now, now);
  }

  private static DataIntegrityViolationException uniqueViolation(String constraintName) {
    return new DataIntegrityViolationException(
        "could not execute statement",
        new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraintName + "\""));
  }

  private static AuthenticatedUser actor(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }
}
