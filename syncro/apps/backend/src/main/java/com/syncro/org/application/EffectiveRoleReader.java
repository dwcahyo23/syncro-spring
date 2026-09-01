package com.syncro.org.application;

import com.syncro.auth.domain.ApplicationRole;
import com.syncro.org.infrastructure.JobTitleRepository;
import com.syncro.org.infrastructure.db.SystemRoleRepository;
import com.syncro.org.infrastructure.db.UserJobBindingRepository;
import com.syncro.org.infrastructure.db.UserRoleBindingRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the effective role set for a user from the data-driven role model
 * (story 16-5). Role set = application_role + job-title default system role
 * + each bound system role. Silently skips missing references (graceful
 * degradation for incomplete configuration data).
 */
@Service
public class EffectiveRoleReader {

  private final UserJobBindingRepository jobBindings;
  private final UserRoleBindingRepository roleBindings;
  private final JobTitleRepository jobTitles;
  private final SystemRoleRepository systemRoles;

  public EffectiveRoleReader(UserJobBindingRepository jobBindings,
      UserRoleBindingRepository roleBindings, JobTitleRepository jobTitles,
      SystemRoleRepository systemRoles) {
    this.jobBindings = jobBindings;
    this.roleBindings = roleBindings;
    this.jobTitles = jobTitles;
    this.systemRoles = systemRoles;
  }

  @Transactional(readOnly = true)
  public List<String> read(UUID userId, ApplicationRole applicationRole) {
    var roleNames = new ArrayList<String>();
    roleNames.add(applicationRole.name());

    // Job-title default system role
    jobBindings.findByUserId(userId).ifPresent(binding -> {
      jobTitles.findById(binding.getJobTitleId()).ifPresent(jobTitle -> {
        if (jobTitle.getDefaultSystemRoleId() != null) {
          systemRoles.findById(jobTitle.getDefaultSystemRoleId())
              .ifPresent(sr -> roleNames.add(sr.getCode()));
        }
      });
    });

    // Per-user role bindings (includes overrides)
    for (var binding : roleBindings.findByUserId(userId)) {
      systemRoles.findById(binding.getSystemRoleId())
          .ifPresent(sr -> roleNames.add(sr.getCode()));
    }

    return List.copyOf(roleNames);
  }
}