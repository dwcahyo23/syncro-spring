package com.syncro.maintenance.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.maintenance.api.WorkOrderDtos.AssignWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.CreateWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.RepairSessionView;
import com.syncro.maintenance.api.WorkOrderDtos.RepairSessionsView;
import com.syncro.maintenance.api.WorkOrderDtos.StartSessionRequest;
import com.syncro.maintenance.api.WorkOrderDtos.TransitionWorkOrderRequest;
import com.syncro.maintenance.api.WorkOrderDtos.WorkorderAttachmentView;
import com.syncro.maintenance.api.WorkOrderDtos.WorkorderAttachmentsView;
import com.syncro.maintenance.api.WorkOrderDtos.SaveWorkOrderReportRequest;
import com.syncro.maintenance.api.WorkOrderDtos.WorkOrderReportView;
import com.syncro.maintenance.api.WorkOrderDtos.WorkOrderView;
import com.syncro.maintenance.application.WorkOrderEvidenceService;
import com.syncro.maintenance.application.WorkOrderEvidenceService.EvidenceCommand;
import com.syncro.maintenance.application.WorkOrderReportService;
import com.syncro.maintenance.application.WorkOrderReportService.CpkPdfCommand;
import com.syncro.maintenance.application.WorkOrderReportService.SaveReportCommand;
import com.syncro.maintenance.application.WorkOrderService;
import com.syncro.maintenance.application.WorkOrderService.AssignWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.CreateResult;
import com.syncro.maintenance.application.WorkOrderService.CreateWorkOrderCommand;
import com.syncro.maintenance.application.WorkOrderService.RepairSessionsResult;
import com.syncro.maintenance.application.WorkOrderService.StartSessionCommand;
import com.syncro.maintenance.application.WorkOrderService.TransitionWorkOrderCommand;
import com.syncro.maintenance.domain.workorder.RepairSession;
import com.syncro.maintenance.domain.workorder.WorkOrder;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@Validated
@RestController
@RequestMapping("/api/v1/workorders")
public class WorkOrderController {

  private final WorkOrderService workOrders;
  private final WorkOrderEvidenceService evidence;
  private final WorkOrderReportService report;

  public WorkOrderController(WorkOrderService workOrders, WorkOrderEvidenceService evidence,
      WorkOrderReportService report) {
    this.workOrders = workOrders;
    this.evidence = evidence;
    this.report = report;
  }

  @Operation(operationId = "createWorkOrder", summary = "Create an internal workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Idempotent replay — returns the existing workorder"),
      @ApiResponse(responseCode = "201", description = "Workorder created", content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Category, machine or parent not found"),
      @ApiResponse(responseCode = "503", description = "Workorder id sequence exhausted")
  })
  @PostMapping
  public ResponseEntity<WorkOrderView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestHeader(value = "Idempotency-Key", required = false) @Size(max = 64) String idempotencyKey,
      @Valid @RequestBody CreateWorkOrderRequest request) {
    var created = workOrders.create(user, new CreateWorkOrderCommand(request.categoryCode(), request.machineId(),
        request.description(), request.parentId(), idempotencyKey));
    if (created.replay()) {
      return ResponseEntity.ok(toDto(created.workorder()));
    }
    return ResponseEntity.created(URI.create("/api/v1/workorders/" + created.workorder().id()))
        .body(toDto(created.workorder()));
  }

  @Operation(operationId = "assignWorkOrder", summary = "Assign an open workorder to a technician")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder assigned"),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or assignee not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition or self-assignment")
  })
  @PostMapping("/{id}/assign")
  public WorkOrderView assign(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody AssignWorkOrderRequest request) {
    return toDto(workOrders.assign(user, id, new AssignWorkOrderCommand(request.assigneeUserId())));
  }

  @Operation(operationId = "transitionWorkOrder", summary = "Transition a workorder through its lifecycle")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Workorder transitioned", content = @Content(schema = @Schema(implementation = WorkOrderView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON or missing override reason"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Invalid state transition, procurement conflict or non-terminal children")
  })
  @PostMapping("/{id}/transition")
  public WorkOrderView transition(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody TransitionWorkOrderRequest request) {
    return toDto(workOrders.transition(user, id,
        new TransitionWorkOrderCommand(request.toStatus(), request.reason(), request.overrideReason())));
  }

  @Operation(operationId = "startRepairSession", summary = "Start a repair session on an IN_PROGRESS workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Session started", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "Session already open, overlap, or workorder not in progress")
  })
  @PostMapping("/{id}/sessions")
  public RepairSessionsView startSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody(required = false) StartSessionRequest request) {
    var command = new StartSessionCommand(request != null ? request.description() : null);
    return toSessionsDto(workOrders.startSession(user, id, command));
  }

  @Operation(operationId = "stopRepairSession", summary = "Stop the open repair session and recompute MTTR")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Session stopped", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "409", description = "No open session")
  })
  @PostMapping("/{id}/sessions/stop")
  public RepairSessionsView stopSession(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
    return toSessionsDto(workOrders.stopSession(user, id));
  }

  @Operation(operationId = "listRepairSessions", summary = "List the workorder's repair sessions")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sessions returned", content = @Content(schema = @Schema(implementation = RepairSessionsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/sessions")
  public RepairSessionsView listSessions(@PathVariable String id) {
    return toSessionsDto(workOrders.listSessions(id));
  }

  /**
   * Multipart parts mirror the API contract names: {@code filename} and {@code contentType}
   * are text parts, {@code data} carries the file bytes — same pattern as 8-4 sparepart images.
   */
  @Operation(operationId = "createWorkorderAttachment", summary = "Upload evidence or a technical drawing to a workorder")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkorderAttachmentView createAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toAttachmentDto(evidence.create(user, id, command(filename, contentType, data)));
  }

  @Operation(operationId = "replaceWorkorderAttachment", summary = "Replace an attachment's file (deletes the old object)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment replaced; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PutMapping(value = "/{id}/attachments/{attachmentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkorderAttachmentView replaceAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID attachmentId,
      @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toAttachmentDto(evidence.replace(user, id, attachmentId, command(filename, contentType, data)));
  }

  @Operation(operationId = "listWorkorderAttachments", summary = "List a workorder's attachments ordered by createdAt")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachments returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentsView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/attachments")
  public WorkorderAttachmentsView listAttachments(@PathVariable String id) {
    return toAttachmentsDto(evidence.list(id));
  }

  @Operation(operationId = "getWorkorderAttachment", summary = "Get a single attachment with its presigned URL")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Attachment returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkorderAttachmentView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found")
  })
  @GetMapping("/{id}/attachments/{attachmentId}")
  public WorkorderAttachmentView getAttachment(@PathVariable String id, @PathVariable UUID attachmentId) {
    return toAttachmentDto(evidence.get(id, attachmentId));
  }

  @Operation(operationId = "deleteWorkorderAttachment", summary = "Delete an attachment (Garage object and row)")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Attachment removed"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder or attachment not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @DeleteMapping("/{id}/attachments/{attachmentId}")
  public ResponseEntity<Void> deleteAttachment(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable String id, @PathVariable UUID attachmentId) {
    evidence.delete(user, id, attachmentId);
    return ResponseEntity.noContent().build();
  }

  /**
   * Report write (FR-117/FR-118/FR-122): four-section narrative + optional CP/CPK
   * values, FMEA failure tag and stop-time reason/detail. Gate: executor/leader.
   */
  @Operation(operationId = "saveWorkOrderReport", summary = "Save the workorder report narrative and capability data")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Report saved",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "400", description = "Validation failed (narrative length, CPK range, enum)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @PutMapping("/{id}/report")
  public WorkOrderReportView saveReport(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @Valid @RequestBody SaveWorkOrderReportRequest request) {
    var command = new SaveReportCommand(request.reportChronological(), request.reportAnalyze(),
        request.reportCorrective(), request.reportPreventive(), request.cpCkLower(), request.cpCkUpper(),
        request.cpk(), request.fmeaFailureType(), request.stopTimeReason(), request.stopTimeDetail());
    return toReportDto(report.saveReport(user, id, command));
  }

  /** Report read (GET /{id}/report): any authenticated user; fresh short-TTL presigned URL. */
  @Operation(operationId = "getWorkOrderReport", summary = "Read the workorder report and capability data")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Report returned",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Workorder not found")
  })
  @GetMapping("/{id}/report")
  public WorkOrderReportView getReport(@PathVariable String id) {
    return toReportDto(report.getReport(id));
  }

  /**
   * CP/CPK capability PDF upload (PUT /{id}/report/cpk). PDF-only, size-capped by
   * {@code syncro.workorder.evidence.max-bytes}. Multipart parts mirror the evidence
   * contract: {@code filename}/{@code contentType} text parts, {@code data} file bytes.
   */
  @Operation(operationId = "uploadWorkOrderCpkPdf", summary = "Upload or replace the CP/CPK capability PDF")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "CP/CPK PDF stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error (PDF-only, size cap)"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PutMapping(value = "/{id}/report/cpk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public WorkOrderReportView uploadCpkPdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id,
      @RequestParam("filename") @Size(max = 255) String filename,
      @RequestParam("contentType") @Size(max = 100) String contentType,
      @RequestPart("data") MultipartFile data) throws IOException {
    return toReportDto(report.uploadCpkPdf(user, id, new CpkPdfCommand(filename, contentType, data.getBytes())));
  }

  /** CP/CPK PDF delete (DELETE /{id}/report/cpk): removes object + clears key, idempotent. */
  @Operation(operationId = "deleteWorkOrderCpkPdf", summary = "Delete the CP/CPK capability PDF")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "CP/CPK PDF removed",
          content = @Content(schema = @Schema(implementation = WorkOrderReportView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Workorder not found"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @DeleteMapping("/{id}/report/cpk")
  public WorkOrderReportView deleteCpkPdf(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable String id) {
    return toReportDto(report.deleteCpkPdf(user, id));
  }

  private static WorkOrderReportView toReportDto(WorkOrderReportService.WorkOrderReportView view) {
    return new WorkOrderReportView(view.workOrderId(), view.reportChronological(), view.reportAnalyze(),
        view.reportCorrective(), view.reportPreventive(), view.cpCkLower(), view.cpCkUpper(), view.cpk(),
        view.cpkPdfPresignedUrl(), view.fmeaFailureType(), view.stopTimeReason(), view.stopTimeDetail());
  }

  private static EvidenceCommand command(String filename, String contentType, MultipartFile data)
      throws IOException {
    return new EvidenceCommand(filename, contentType, data.getBytes());
  }

  private WorkorderAttachmentsView toAttachmentsDto(WorkOrderEvidenceService.WorkorderAttachmentsView view) {
    return new WorkorderAttachmentsView(view.workOrderId(),
        view.attachments().stream().map(WorkOrderController::toAttachmentDto).toList());
  }

  private static WorkorderAttachmentView toAttachmentDto(WorkOrderEvidenceService.WorkorderAttachmentView view) {
    return new WorkorderAttachmentView(view.id(), view.workOrderId(), view.filename(), view.contentType(),
        view.objectKey(), view.sizeBytes(), view.uploadedBy(), view.createdAt(), view.updatedAt(),
        view.presignedUrl());
  }

  private WorkOrderView toDto(WorkOrder workOrder) {
    return new WorkOrderView(workOrder.id(), workOrder.source(), workOrder.status(), workOrder.categoryId(),
        workOrder.machineId(), workOrder.description(), workOrder.parentId(), workOrder.assignedTechnicianId(),
        workOrder.createdBy(), workOrder.createdAt(), workOrder.updatedAt(), workOrder.mttrMinutes(),
        workOrder.responseTimeMinutes(), workOrder.doneReason());
  }

  private RepairSessionsView toSessionsDto(RepairSessionsResult result) {
    return new RepairSessionsView(toDto(result.workOrder()),
        result.sessions().stream().map(WorkOrderController::toSessionDto).toList());
  }

  private static RepairSessionView toSessionDto(RepairSession session) {
    return new RepairSessionView(session.id(), session.workOrderId(), session.technicianId(), session.description(),
        session.startedAt(), session.endedAt(), session.durationMinutes());
  }
}