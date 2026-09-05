package com.syncro.compliance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.compliance.application.EightDReportService;
import com.syncro.compliance.application.NonConformanceService;
import com.syncro.compliance.domain.EightDStatus;
import com.syncro.compliance.domain.NcSeverity;
import com.syncro.compliance.domain.NcStatus;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-conformance & 8D REST surface (story 21-1, blueprint H1/H2):
 * {@code /api/v1/non-conformances} CRUD + lifecycle and the one-per-NC
 * {@code /{id}/eight-d} sub-resource. Controllers only bind/validate and delegate —
 * role gates, transitions, scope filtering, and audit live in the application
 * services (spine API rule).
 */
@RestController
@RequestMapping("/api/v1/non-conformances")
public class NonConformanceController {

  private final NonConformanceService nonConformances;
  private final EightDReportService eightDReports;

  public NonConformanceController(NonConformanceService nonConformances,
      EightDReportService eightDReports) {
    this.nonConformances = nonConformances;
    this.eightDReports = eightDReports;
  }

  @Operation(operationId = "listNonConformances", summary = "List non-conformances visible in the caller's machine scope")
  @GetMapping
  public List<NonConformanceService.NcView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) NcStatus status) {
    return nonConformances.list(user, status);
  }

  @Operation(operationId = "createNonConformance", summary = "Create a non-conformance (OPEN)")
  @PostMapping
  public ResponseEntity<NonConformanceService.NcView> create(
      @AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateNcRequest request) {
    var view = nonConformances.create(user, new NonConformanceService.CreateNcCommand(
        request.ncNumber(), request.description(), request.severity(), request.projectId(),
        request.workOrderId(), request.machineId(), request.responsibleId(),
        request.targetCloseDate()));
    return ResponseEntity.created(URI.create("/api/v1/non-conformances/" + view.id())).body(view);
  }

  @Operation(operationId = "getNonConformance", summary = "Read one non-conformance (machine-scope filtered)")
  @GetMapping("/{id}")
  public NonConformanceService.NcView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return nonConformances.get(user, id);
  }

  @Operation(operationId = "updateNonConformance", summary = "Partial update + status transition of a non-conformance")
  @PatchMapping("/{id}")
  public NonConformanceService.NcView update(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody UpdateNcRequest request) {
    return nonConformances.update(user, id, new NonConformanceService.UpdateNcCommand(
        request.status(), request.description(), request.severity(), request.rootCause(),
        request.correctiveAction(), request.responsibleId(), request.targetCloseDate()));
  }

  @Operation(operationId = "createEightDReport", summary = "Create the one-per-NC 8D report (DRAFT)")
  @PostMapping("/{id}/eight-d")
  public ResponseEntity<EightDReportService.EightDView> createEightD(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody CreateEightDRequest request) {
    var view = eightDReports.create(user, id, new EightDReportService.CreateEightDCommand(
        request.reportNumber(), request.d1Team(), request.d2Description(),
        request.d3Containment(), request.d4RootCause(), request.d5CaPermanent(),
        request.d6Implementation(), request.d7LessonLearned(), request.d8ClosureNotes()));
    return ResponseEntity.created(URI.create("/api/v1/non-conformances/" + id + "/eight-d"))
        .body(view);
  }

  @Operation(operationId = "getEightDReport", summary = "Read the NC's 8D report")
  @GetMapping("/{id}/eight-d")
  public EightDReportService.EightDView getEightD(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id) {
    return eightDReports.getForNc(user, id);
  }

  @Operation(operationId = "updateEightDReport", summary = "Partial D1–D8 update + status transition")
  @PatchMapping("/{id}/eight-d")
  public EightDReportService.EightDView updateEightD(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @Valid @RequestBody UpdateEightDRequest request) {
    return eightDReports.update(user, id, new EightDReportService.UpdateEightDCommand(
        request.status(), request.d1Team(), request.d2Description(), request.d3Containment(),
        request.d4RootCause(), request.d5CaPermanent(), request.d6Implementation(),
        request.d7LessonLearned(), request.d8ClosureNotes(), request.pdfArtifactUrl()));
  }

  @Operation(operationId = "verifyEightDEffectiveness",
      summary = "Record the effectiveness verdict (SUPER_ADMIN/MANAGER_MAINTENANCE, CLOSED only)")
  @PostMapping("/{id}/eight-d/verify-effectiveness")
  public EightDReportService.EightDView verifyEffectiveness(
      @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
      @Valid @RequestBody VerifyEffectivenessRequest request) {
    return eightDReports.verifyEffectiveness(user, id,
        new EightDReportService.VerifyEffectivenessCommand(request.verdict()));
  }

  public record CreateNcRequest(
      @NotBlank @Size(max = 50) String ncNumber,
      @NotBlank String description,
      NcSeverity severity,
      @Size(max = 64) String projectId,
      @Size(max = 50) String workOrderId,
      UUID machineId,
      UUID responsibleId,
      LocalDate targetCloseDate) {
  }

  /** Partial update — null keeps the stored value; {@code status} rides the transition gate. */
  public record UpdateNcRequest(
      NcStatus status,
      @Size(max = 10_000) String description,
      NcSeverity severity,
      String rootCause,
      String correctiveAction,
      UUID responsibleId,
      LocalDate targetCloseDate) {
  }

  public record CreateEightDRequest(
      @NotBlank @Size(max = 50) String reportNumber,
      Map<String, Object> d1Team,
      String d2Description,
      String d3Containment,
      Map<String, Object> d4RootCause,
      String d5CaPermanent,
      String d6Implementation,
      String d7LessonLearned,
      String d8ClosureNotes) {
  }

  public record UpdateEightDRequest(
      EightDStatus status,
      Map<String, Object> d1Team,
      String d2Description,
      String d3Containment,
      Map<String, Object> d4RootCause,
      String d5CaPermanent,
      String d6Implementation,
      String d7LessonLearned,
      String d8ClosureNotes,
      String pdfArtifactUrl) {
  }

  public record VerifyEffectivenessRequest(@NotNull EightDStatus verdict) {
  }
}
