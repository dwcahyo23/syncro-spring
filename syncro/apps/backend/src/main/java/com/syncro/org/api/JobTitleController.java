package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.application.JobTitleService;
import com.syncro.org.application.JobTitleService.CreateJobTitleCommand;
import com.syncro.org.application.JobTitleService.JobTitleListView;
import com.syncro.org.application.JobTitleService.JobTitleView;
import com.syncro.org.application.JobTitleService.UpdateJobTitleCommand;
import com.syncro.org.domain.JobBindingScope;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/job-titles")
public class JobTitleController {

  private final JobTitleService jobTitles;

  public JobTitleController(JobTitleService jobTitles) {
    this.jobTitles = jobTitles;
  }

  @Operation(operationId = "listJobTitles", summary = "List job titles")
  @GetMapping
  public JobTitleListView list() {
    return jobTitles.list();
  }

  @Operation(operationId = "createJobTitle", summary = "Create a job title")
  @PostMapping
  public ResponseEntity<JobTitleView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateJobTitleRequest request) {
    var created = jobTitles.create(user, new CreateJobTitleCommand(
        request.code(), request.name(), request.description(), request.bindingScope(), request.defaultSystemRoleId()));
    return ResponseEntity.created(URI.create("/api/v1/job-titles/" + created.id())).body(created);
  }

  @Operation(operationId = "updateJobTitle", summary = "Update a job title")
  @PutMapping("/{jobTitleId}")
  public JobTitleView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobTitleId,
      @Valid @RequestBody UpdateJobTitleRequest request) {
    return jobTitles.update(user, jobTitleId, new UpdateJobTitleCommand(
        request.code(), request.name(), request.description(), request.bindingScope(), request.defaultSystemRoleId(), request.active()));
  }

  /** Deactivate-style delete: soft-inactive. */
  @Operation(operationId = "deleteJobTitle", summary = "Deactivate a job title")
  @DeleteMapping("/{jobTitleId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID jobTitleId) {
    jobTitles.delete(user, jobTitleId);
    return ResponseEntity.noContent().build();
  }

  public record CreateJobTitleRequest(
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 1000) String description,
      JobBindingScope bindingScope,
      UUID defaultSystemRoleId) {
  }

  public record UpdateJobTitleRequest(
      @NotBlank @Size(max = 50) String code,
      @NotBlank @Size(max = 200) String name,
      @Size(max = 1000) String description,
      JobBindingScope bindingScope,
      UUID defaultSystemRoleId,
      boolean active) {
  }
}