package com.syncro.org.application;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.domain.JobBindingScope;
import com.syncro.org.infrastructure.JobTitleEntity;
import com.syncro.org.infrastructure.JobTitleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job-title master (user-master reference data, blueprint A4). CRUD with
 * soft-inactivate delete. Code is unique and case-insensitive at lookup; the DB
 * unique constraint is exact-case, so the service pre-checks IgnoreCase
 * duplicates to keep the UX consistent. Mutations require
 * SUPER_ADMIN/MANAGER_MAINTENANCE and are audit-logged as {@code JOB_TITLE}.
 */
@Service
public class JobTitleService {

  private static final String JOB_TITLE_CODE_UNIQUE_CONSTRAINT = "uq_job_titles_code";

  private final JobTitleRepository jobTitles;
  private final AuditLogWriter auditLog;
  private final Clock clock;

  public JobTitleService(JobTitleRepository jobTitles, AuditLogWriter auditLog, Clock clock) {
    this.jobTitles = jobTitles;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public JobTitleListView list() {
    return new JobTitleListView(jobTitles.findAllByOrderByNameAsc().stream().map(this::toView).toList());
  }

  @Transactional
  public JobTitleView create(AuthenticatedUser user, CreateJobTitleCommand command) {
    requireMutationRole(user);
    var code = normalize(command.code(), 50);
    var name = normalize(command.name(), 200);
    var description = command.description() == null ? null : command.description().trim();
    if (jobTitles.existsByCodeIgnoreCase(code)) {
      throw new DuplicateJobTitleCodeException();
    }
    var now = Instant.now(clock);
    var saved = saveJobTitle(new JobTitleEntity(UUID.randomUUID(), code, name, description,
        command.bindingScope() == null ? JobBindingScope.NONE : command.bindingScope(), true,
        command.defaultSystemRoleId(), now, now));
    auditLog.record(user, new AuditRecord(AuditAction.CREATE, AuditEntityType.JOB_TITLE, saved.getId(),
        saved.getName(), null, null, JobTitleAuditValues.of(saved), null));
    return toView(saved);
  }

  @Transactional
  public JobTitleView update(AuthenticatedUser user, UUID jobTitleId, UpdateJobTitleCommand command) {
    requireMutationRole(user);
    var jobTitle = findScoped(jobTitleId);
    var entityLabel = jobTitle.getName();
    var previous = JobTitleAuditValues.of(jobTitle);
    var code = normalize(command.code(), 50);
    var name = normalize(command.name(), 200);
    var existing = jobTitles.findByCodeIgnoreCase(code);
    if (existing.isPresent() && !existing.get().getId().equals(jobTitleId)) {
      throw new DuplicateJobTitleCodeException();
    }
    jobTitle.update(code, name,
        command.description() == null ? null : command.description().trim(),
        command.bindingScope(), command.defaultSystemRoleId(), command.active(), Instant.now(clock));
    var saved = saveJobTitle(jobTitle);
    auditLog.record(user, new AuditRecord(AuditAction.UPDATE, AuditEntityType.JOB_TITLE, jobTitleId, entityLabel,
        null, previous, JobTitleAuditValues.of(saved), null));
    return toView(saved);
  }

  /** Deactivate-style delete: soft-inactive only, no hard delete. */
  @Transactional
  public void delete(AuthenticatedUser user, UUID jobTitleId) {
    requireMutationRole(user);
    var jobTitle = findScoped(jobTitleId);
    var entityLabel = jobTitle.getName();
    var previous = JobTitleAuditValues.of(jobTitle);
    jobTitle.deactivate(Instant.now(clock));
    var saved = saveJobTitle(jobTitle);
    auditLog.record(user, new AuditRecord(AuditAction.DELETE, AuditEntityType.JOB_TITLE, jobTitleId, entityLabel,
        null, previous, JobTitleAuditValues.of(saved), null));
  }

  private JobTitleEntity findScoped(UUID jobTitleId) {
    return jobTitles.findById(jobTitleId).orElseThrow(JobTitleNotFoundException::new);
  }

  private void requireMutationRole(AuthenticatedUser user) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN
        && user.applicationRole() != ApplicationRole.MANAGER_MAINTENANCE) {
      throw new JobTitleMutationForbiddenException();
    }
  }

  private JobTitleEntity saveJobTitle(JobTitleEntity jobTitle) {
    try {
      return jobTitles.saveAndFlush(jobTitle);
    } catch (DataIntegrityViolationException exception) {
      var message = String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
      if (message.contains(JOB_TITLE_CODE_UNIQUE_CONSTRAINT)) {
        throw new DuplicateJobTitleCodeException();
      }
      throw new JobTitleDataIntegrityException();
    }
  }

  private String normalize(String value, int maxLength) {
    if (value == null) {
      return null;
    }
    var trimmed = value.trim();
    return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
  }

  private JobTitleView toView(JobTitleEntity jobTitle) {
    return new JobTitleView(jobTitle.getId(), jobTitle.getCode(), jobTitle.getName(), jobTitle.getDescription(),
        jobTitle.getBindingScope(), jobTitle.isActive(), jobTitle.getDefaultSystemRoleId());
  }

  public record CreateJobTitleCommand(String code, String name, String description, JobBindingScope bindingScope,
      UUID defaultSystemRoleId) {
  }

  public record UpdateJobTitleCommand(String code, String name, String description, JobBindingScope bindingScope,
      UUID defaultSystemRoleId, Boolean active) {
  }

  public record JobTitleView(UUID id, String code, String name, String description, JobBindingScope bindingScope,
      boolean active, UUID defaultSystemRoleId) {
  }

  public record JobTitleListView(List<JobTitleView> items) {
  }

  public static class DuplicateJobTitleCodeException extends RuntimeException {
  }

  public static class JobTitleDataIntegrityException extends RuntimeException {
  }

  public static class JobTitleMutationForbiddenException extends RuntimeException {
  }

  public static class JobTitleNotFoundException extends RuntimeException {
  }

  /** Audit snapshot helper for a job title. */
  private static final class JobTitleAuditValues {
    private JobTitleAuditValues() {
    }

    static Map<String, Object> of(JobTitleEntity jobTitle) {
      var values = new LinkedHashMap<String, Object>();
      values.put("code", jobTitle.getCode());
      values.put("name", jobTitle.getName());
      values.put("description", jobTitle.getDescription());
      values.put("bindingScope", jobTitle.getBindingScope().name());
      values.put("active", jobTitle.isActive());
      values.put("defaultSystemRoleId",
          jobTitle.getDefaultSystemRoleId() == null ? null : jobTitle.getDefaultSystemRoleId().toString());
      return values;
    }
  }
}
