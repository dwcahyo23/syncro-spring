package com.syncro.org.application;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.infrastructure.JobTitleEntity;
import com.syncro.org.infrastructure.JobTitleRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Job-title master (user-master reference data). Minimal CRUD: list (any
 * authenticated) and create (SUPER_ADMIN|MANAGER_MAINTENANCE). Code is unique and
 * case-insensitive at lookup; the DB unique constraint is exact-case, so the
 * service pre-checks IgnoreCase duplicates to keep the UX consistent.
 */
@Service
public class JobTitleService {

  private static final String JOB_TITLE_CODE_UNIQUE_CONSTRAINT = "uq_job_titles_code";

  private final JobTitleRepository jobTitles;
  private final Clock clock;

  public JobTitleService(JobTitleRepository jobTitles, Clock clock) {
    this.jobTitles = jobTitles;
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
    var saved = saveJobTitle(new JobTitleEntity(UUID.randomUUID(), code, name, description, now, now));
    return toView(saved);
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
    return new JobTitleView(jobTitle.getId(), jobTitle.getCode(), jobTitle.getName(), jobTitle.getDescription());
  }

  public record CreateJobTitleCommand(String code, String name, String description) {
  }

  public record JobTitleView(UUID id, String code, String name, String description) {
  }

  public record JobTitleListView(List<JobTitleView> items) {
  }

  public static class DuplicateJobTitleCodeException extends RuntimeException {
  }

  public static class JobTitleDataIntegrityException extends RuntimeException {
  }

  public static class JobTitleMutationForbiddenException extends RuntimeException {
  }
}
