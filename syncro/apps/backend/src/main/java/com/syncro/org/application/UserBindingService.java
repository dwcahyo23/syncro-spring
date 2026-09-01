package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.org.infrastructure.JobTitleRepository;
import com.syncro.org.infrastructure.db.SystemRoleRepository;
import com.syncro.org.infrastructure.db.UserJobBindingEntity;
import com.syncro.org.infrastructure.db.UserJobBindingRepository;
import com.syncro.org.infrastructure.db.UserRoleBindingEntity;
import com.syncro.org.infrastructure.db.UserRoleBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manages per-user job-title and role bindings (blueprint A9/A10, story 16-3).
 * One user = one job title (replaced on write). Role bindings are additive,
 * unique per (user, system_role), and may carry the is_override flag.
 * Mutations require SUPER_ADMIN|MANAGER_MAINTENANCE, audit-logged as
 * USER_JOB_BINDING / USER_ROLE_BINDING.
 */
@Service
public class UserBindingService {

  private final UserJobBindingRepository jobBindings;
  private final UserRoleBindingRepository roleBindings;
  private final AuthUserRepository users;
  private final JobTitleRepository jobTitles;
  private final SystemRoleRepository systemRoles;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public UserBindingService(UserJobBindingRepository jobBindings,
      UserRoleBindingRepository roleBindings, AuthUserRepository users,
      JobTitleRepository jobTitles, SystemRoleRepository systemRoles,
      AuditLogWriter auditLog, Clock clock) {
    this.jobBindings = jobBindings;
    this.roleBindings = roleBindings;
    this.users = users;
    this.jobTitles = jobTitles;
    this.systemRoles = systemRoles;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public UserBindingsView getBindings(AuthenticatedUser actor, UUID userId) {
    resolveUser(userId);
    var job = jobBindings.findByUserId(userId);
    var roles = roleBindings.findByUserId(userId);
    return new UserBindingsView(
        job.map(b -> new JobBindingView(b.getId(), b.getJobTitleId())).orElse(null),
        roles.stream().map(r -> new RoleBindingView(r.getId(), r.getSystemRoleId(), r.isOverride())).toList());
  }

  @Transactional
  public UserBindingsView setJob(AuthenticatedUser actor, UUID userId, UUID jobTitleId) {
    requireMutationRole(actor);
    resolveUser(userId);
    if (jobTitleId != null && jobTitles.findById(jobTitleId).isEmpty()) {
      throw new JobTitleNotFoundException();
    }
    var now = Instant.now(clock);
    var actorId = UUID.fromString(actor.id());

    jobBindings.findByUserId(userId).ifPresent(existing ->
        jobBindings.delete(existing));
    jobBindings.flush();

    UserJobBindingEntity saved = null;
    if (jobTitleId != null) {
      saved = jobBindings.saveAndFlush(
          new UserJobBindingEntity(UUID.randomUUID(), userId, jobTitleId, actorId, now));
      auditLog.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.USER_JOB_BINDING,
          saved.getId(), userId.toString(), null, null, jobAuditValues(saved, actorId), null));
    } else {
      auditLog.record(actor, new AuditRecord(AuditAction.DELETE, AuditEntityType.USER_JOB_BINDING,
          null, userId.toString(), null, null, Map.of("userId", userId.toString()), null));
    }
    var roles = roleBindings.findByUserId(userId);
    return new UserBindingsView(
        saved == null ? null : new JobBindingView(saved.getId(), saved.getJobTitleId()),
        roles.stream().map(r -> new RoleBindingView(r.getId(), r.getSystemRoleId(), r.isOverride())).toList());
  }

  @Transactional
  public UserBindingsView addRole(AuthenticatedUser actor, UUID userId, UUID systemRoleId, boolean override) {
    requireMutationRole(actor);
    resolveUser(userId);
    if (systemRoles.findById(systemRoleId).isEmpty()) {
      throw new SystemRoleNotFoundException();
    }
    var now = Instant.now(clock);
    var actorId = UUID.fromString(actor.id());

    var existing = roleBindings.findByUserIdAndSystemRoleId(userId, systemRoleId);
    UserRoleBindingEntity saved;
    if (existing.isPresent()) {
      var entity = existing.get();
      saved = new UserRoleBindingEntity(entity.getId(), entity.getUserId(), entity.getSystemRoleId(),
          override, actorId, now);
      roleBindings.saveAndFlush(saved);
    } else {
      saved = roleBindings.saveAndFlush(new UserRoleBindingEntity(UUID.randomUUID(), userId, systemRoleId,
          override, actorId, now));
    }
    auditLog.record(actor, new AuditRecord(AuditAction.CREATE, AuditEntityType.USER_ROLE_BINDING,
        saved.getId(), userId.toString(), null, null,
        Map.of("userId", userId.toString(), "systemRoleId", systemRoleId.toString(), "isOverride", override), null));
    return new UserBindingsView(
        jobBindings.findByUserId(userId)
            .map(b -> new JobBindingView(b.getId(), b.getJobTitleId())).orElse(null),
        roleBindings.findByUserId(userId).stream()
            .map(r -> new RoleBindingView(r.getId(), r.getSystemRoleId(), r.isOverride())).toList());
  }

  @Transactional
  public void removeRole(AuthenticatedUser actor, UUID userId, UUID bindingId) {
    requireMutationRole(actor);
    var binding = roleBindings.findById(bindingId).orElseThrow(RoleBindingNotFoundException::new);
    if (!binding.getUserId().equals(userId)) {
      throw new RoleBindingNotFoundException();
    }
    roleBindings.delete(binding);
    roleBindings.flush();
    auditLog.record(actor, new AuditRecord(AuditAction.DELETE, AuditEntityType.USER_ROLE_BINDING,
        bindingId, userId.toString(), null, null, Map.of("userId", userId.toString(), "systemRoleId",
            binding.getSystemRoleId().toString()), null));
  }

  private void resolveUser(UUID userId) {
    if (!users.existsById(userId)) {
      throw new UserNotFoundException();
    }
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new UserBindingMutationForbiddenException();
    }
  }

  private static Map<String, Object> jobAuditValues(UserJobBindingEntity binding, UUID actorId) {
    var m = new LinkedHashMap<String, Object>();
    m.put("userId", binding.getUserId().toString());
    m.put("jobTitleId", binding.getJobTitleId().toString());
    m.put("assignedBy", actorId.toString());
    return m;
  }

  public record JobBindingView(UUID id, UUID jobTitleId) {
  }

  public record RoleBindingView(UUID id, UUID systemRoleId, boolean override) {
  }

  public record UserBindingsView(JobBindingView job, List<RoleBindingView> roles) {
  }

  public static class UserBindingMutationForbiddenException extends RuntimeException {
  }

  public static class UserNotFoundException extends RuntimeException {
  }

  public static class JobTitleNotFoundException extends RuntimeException {
  }

  public static class SystemRoleNotFoundException extends RuntimeException {
  }

  public static class RoleBindingNotFoundException extends RuntimeException {
  }
}