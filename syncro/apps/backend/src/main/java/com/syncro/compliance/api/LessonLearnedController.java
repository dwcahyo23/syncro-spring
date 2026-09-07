package com.syncro.compliance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.LessonLearnedService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lesson-learned REST surface (story 21-3, blueprint H6):
 * {@code /api/v1/lessons-learned} CRUD plus the {@code ?q=&tag=} scoped search.
 * Controllers only bind/validate and delegate — event-link validation, scope
 * filtering, and audit live in {@link LessonLearnedService} (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/lessons-learned")
public class LessonLearnedController {

  private final LessonLearnedService lessons;

  public LessonLearnedController(LessonLearnedService lessons) {
    this.lessons = lessons;
  }

  @Operation(operationId = "searchLessonsLearned",
      summary = "Search lessons visible in the caller's scope "
          + "(?q= substring over title/problem_summary, ?tag= tag containment)")
  @GetMapping
  public List<LessonLearnedService.LessonView> search(
      @AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String tag) {
    return lessons.search(user, q, tag);
  }

  @Operation(operationId = "createLessonLearned",
      summary = "Capture a lesson (unique project id, optional NC/8D/workorder event links)")
  @PostMapping
  public ResponseEntity<LessonLearnedService.LessonView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody ComplianceDtos.CreateLessonRequest request) {
    var view = lessons.create(user, new LessonLearnedService.CreateLessonCommand(
        request.projectId(), request.machineId(), request.projectType(), request.title(),
        request.problemSummary(), request.rootCause(), request.solution(),
        request.sparepartsUsed(), request.durationDays(), request.reCycleCount(),
        request.tags(), request.ncId(), request.eightDId(), request.workOrderId(),
        request.evidence()));
    return ResponseEntity.created(URI.create("/api/v1/lessons-learned/" + view.id())).body(view);
  }

  @Operation(operationId = "getLessonLearned", summary = "Read one lesson (scope filtered)")
  @GetMapping("/{id}")
  public LessonLearnedService.LessonView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return lessons.get(user, id);
  }

  @Operation(operationId = "updateLessonLearned",
      summary = "Partial update of a lesson (project id and machine are immutable)")
  @PatchMapping("/{id}")
  public LessonLearnedService.LessonView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody ComplianceDtos.UpdateLessonRequest request) {
    return lessons.update(user, id, new LessonLearnedService.UpdateLessonCommand(
        request.title(), request.problemSummary(), request.rootCause(), request.solution(),
        request.sparepartsUsed(), request.durationDays(), request.reCycleCount(),
        request.tags(), request.evidence(), request.ncId(), request.eightDId(),
        request.workOrderId()));
  }

  @Operation(operationId = "deleteLessonLearned", summary = "Delete a lesson (audited)")
  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    lessons.delete(user, id);
    return ResponseEntity.noContent().build();
  }
}
