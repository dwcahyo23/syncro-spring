package com.syncro.maintenance.preventive.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ApproveScheduleRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecklistItemRequest;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecklistResultView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ChecklistView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.MachineShiftConfigView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PreventiveAttachmentView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.PreventiveScheduleView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.ShiftWindowView;
import com.syncro.maintenance.preventive.api.PreventiveDtos.SubmitChecklistRequest;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ItemCommand;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceCommand;
import com.syncro.maintenance.preventive.application.PreventiveScheduleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/preventive-schedules")
public class PreventiveScheduleController {

  private final PreventiveScheduleService schedules;
  private final PreventiveChecklistService checklists;
  private final PreventiveEvidenceService evidence;

  public PreventiveScheduleController(PreventiveScheduleService schedules, PreventiveChecklistService checklists,
      PreventiveEvidenceService evidence) {
    this.schedules = schedules;
    this.checklists = checklists;
    this.evidence = evidence;
  }

  @Operation(operationId = "listPreventiveSchedules", summary = "Scope-filtered preventive schedules with derived status and shift context")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Schedules returned",
          content = @Content(schema = @Schema(implementation = PreventiveScheduleView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public List<PreventiveScheduleView> list(@AuthenticationPrincipal AuthenticatedUser user) {
    return schedules.list(user).stream().map(PreventiveScheduleController::toView).toList();
  }

  // -------------------------------------------------------------------------
  // Checklist
  // -------------------------------------------------------------------------

  @Operation(operationId = "submitChecklist", summary = "Submit preventive checklist (SCHEDULED → IN_PROGRESS)")
  @PostMapping("/{id}/checklist")
  @ResponseStatus(HttpStatus.CREATED)
  public ChecklistResultView submitChecklist(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody SubmitChecklistRequest request) {
    var command = new ChecklistCommand(request.notes(), toItemCommands(request.items()));
    return toResultView(checklists.submit(user, id, command));
  }

  @Operation(operationId = "amendChecklist", summary = "Amend checklist items before approval (PUT)")
  @PutMapping("/{id}/checklist")
  public ChecklistResultView amendChecklist(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody SubmitChecklistRequest request) {
    var command = new ChecklistCommand(request.notes(), toItemCommands(request.items()));
    return toResultView(checklists.amend(user, id, command));
  }

  @Operation(operationId = "getChecklist", summary = "Read checklist result + items + derived status")
  @GetMapping("/{id}/checklist")
  public ChecklistView getChecklist(@PathVariable String id) {
    var view = checklists.get(id);
    var result = view.result() == null ? null : toResultView(view.result());
    return new ChecklistView(view.status().name(), result);
  }

  // -------------------------------------------------------------------------
  // Approval & skip
  // -------------------------------------------------------------------------

  @Operation(operationId = "approveSchedule", summary = "Leader approves the checklist (IN_PROGRESS → PERFORMED + roll forward)")
  @PostMapping("/{id}/approve")
  public ChecklistResultView approve(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @Valid @RequestBody ApproveScheduleRequest request) {
    var command = new com.syncro.maintenance.preventive.application.PreventiveChecklistService.ApproveCommand(
        request.signatureObjectKey(), request.signerIdentity(), request.assessment());
    return toResultView(checklists.approve(user, id, command));
  }

  @Operation(operationId = "skipSchedule", summary = "Leader skips the schedule (SCHEDULED/IN_PROGRESS → SKIPPED, no roll-forward)")
  @PostMapping("/{id}/skip")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void skip(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
    checklists.skip(user, id);
  }

  // -------------------------------------------------------------------------
  // Evidence
  // -------------------------------------------------------------------------

  @Operation(operationId = "uploadEvidence", summary = "Upload evidence for a schedule (Garage, object-key-only)")
  @PostMapping(value = "/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public PreventiveAttachmentView uploadEvidence(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @RequestParam("file") MultipartFile file) throws Exception {
    var command = new EvidenceCommand(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    return toAttachmentView(evidence.create(user, id, command));
  }

  @Operation(operationId = "replaceEvidence", summary = "Replace evidence file (new Garage object, old removed)")
  @PutMapping(value = "/{id}/evidence/{attachmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public PreventiveAttachmentView replaceEvidence(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @PathVariable UUID attachmentId,
      @RequestParam("file") MultipartFile file) throws Exception {
    var command = new EvidenceCommand(file.getOriginalFilename(), file.getContentType(), file.getBytes());
    return toAttachmentView(evidence.replace(user, id, attachmentId, command));
  }

  @Operation(operationId = "deleteEvidence", summary = "Delete evidence (Garage object + row)")
  @DeleteMapping("/{id}/evidence/{attachmentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteEvidence(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID id, @PathVariable UUID attachmentId) {
    evidence.delete(user, id, attachmentId);
  }

  @Operation(operationId = "listEvidence", summary = "List evidence for a schedule")
  @GetMapping("/{id}/evidence")
  public List<PreventiveAttachmentView> listEvidence(@PathVariable UUID id) {
    return evidence.list(id).stream().map(PreventiveScheduleController::toAttachmentView).toList();
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static List<ItemCommand> toItemCommands(List<ChecklistItemRequest> items) {
    return items.stream()
        .map(r -> new ItemCommand(r.label(), r.value(), r.lsl(), r.usl(), r.note()))
        .toList();
  }

  private static ChecklistResultView toResultView(PreventiveChecklistService.ChecklistResultView view) {
    var itemViews = view.items() == null ? java.util.List.<ChecklistItemRequest>of() : view.items().stream()
        .map(i -> new ChecklistItemRequest(i.label(), i.value(), i.lsl(), i.usl(), i.note()))
        .toList();
    return new ChecklistResultView(view.id(), view.scheduleId(), view.performedBy(), view.completedAt(),
        view.notes(), view.leaderId(), view.assessment(), view.approvedAt(), view.signatureObjectKey(),
        view.signerIdentity(), itemViews);
  }

  private static PreventiveAttachmentView toAttachmentView(PreventiveEvidenceService.AttachmentView view) {
    return new PreventiveAttachmentView(view.id(), view.scheduleId(), view.filename(), view.contentType(),
        view.objectKey(), view.sizeBytes(), view.uploadedBy(), view.createdAt(), view.updatedAt(),
        view.presignedUrl());
  }

  private static PreventiveScheduleView toView(PreventiveScheduleService.ScheduleView sv) {
    var shiftView = sv.shiftConfig() == null ? null : new MachineShiftConfigView(sv.shiftConfig().source(),
        sv.shiftConfig().inheritedFromGroup(),
        sv.shiftConfig().shifts().stream()
            .map(s -> new ShiftWindowView(s.shiftNumber(), s.startTime().toString(), s.endTime().toString()))
            .toList());
    return new PreventiveScheduleView(sv.id(), sv.programId(), sv.machineId(), sv.dueDate(), sv.status(),
        sv.derivedStatus(), sv.completedAt(), sv.performedBy(), sv.category(), sv.scheduleType(), shiftView,
        sv.today(), sv.checklistStatus());
  }
}