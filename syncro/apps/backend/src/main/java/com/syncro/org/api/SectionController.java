package com.syncro.org.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.org.api.SectionDtos.AssignLeaderRequest;
import com.syncro.org.api.SectionDtos.CreateSectionRequest;
import com.syncro.org.api.SectionDtos.SectionLeaderView;
import com.syncro.org.api.SectionDtos.SectionListResponse;
import com.syncro.org.api.SectionDtos.SectionView;
import com.syncro.org.api.SectionDtos.UpdateSectionRequest;
import com.syncro.org.application.SectionService;
import com.syncro.org.application.SectionService.CreateSectionCommand;
import com.syncro.org.application.SectionService.UpdateSectionCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/sections")
public class SectionController {

  private final SectionService sections;

  public SectionController(SectionService sections) {
    this.sections = sections;
  }

  @Operation(operationId = "listSections", summary = "List sections for a plant")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sections returned"),
      @ApiResponse(responseCode = "400", description = "Invalid plant id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @GetMapping
  public SectionListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam UUID plantId,
      @RequestParam(defaultValue = "false") boolean includeInactive) {
    var result = sections.list(user, plantId, includeInactive);
    return new SectionListResponse(result.items().stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getSection", summary = "Get a section")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Section returned"),
      @ApiResponse(responseCode = "400", description = "Invalid section id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Section not found")
  })
  @GetMapping("/{sectionId}")
  public SectionView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sectionId) {
    return toDto(sections.get(user, sectionId));
  }

  @Operation(operationId = "createSection", summary = "Create a section")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Section created", content = @Content(schema = @Schema(implementation = SectionView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate code or name"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Plant not found")
  })
  @PostMapping
  public ResponseEntity<SectionView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody CreateSectionRequest request) {
    var created = toDto(sections.create(user, new CreateSectionCommand(request.plantId(), request.code(), request.name())));
    return ResponseEntity.created(URI.create("/api/v1/sections/" + created.id())).body(created);
  }

  @Operation(operationId = "updateSection", summary = "Update a section (name and active status)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Section updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate name"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Section not found"),
      @ApiResponse(responseCode = "409", description = "Section has active machine groups")
  })
  @PutMapping("/{sectionId}")
  public SectionView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sectionId,
      @Valid @RequestBody UpdateSectionRequest request) {
    return toDto(sections.update(user, sectionId, new UpdateSectionCommand(request.name(), request.active())));
  }

  @Operation(operationId = "assignSectionLeader", summary = "Assign section leader")
  @PutMapping("/{sectionId}/leader")
  public SectionLeaderView assignLeader(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sectionId, @Valid @RequestBody AssignLeaderRequest request) {
    var result = sections.assignLeader(user, sectionId, request.userId());
    return new SectionLeaderView(result.sectionId(), result.leaderUserId());
  }

  @Operation(operationId = "clearSectionLeader", summary = "Clear section leader")
  @DeleteMapping("/{sectionId}/leader")
  public ResponseEntity<Void> clearLeader(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sectionId) {
    sections.clearLeader(user, sectionId);
    return ResponseEntity.noContent().build();
  }

  private SectionView toDto(SectionService.SectionView section) {
    return new SectionView(
        section.id(),
        section.plantId(),
        section.plantCode(),
        section.plantName(),
        section.code(),
        section.name(),
        section.active(),
        section.leaderUserId(),
        section.createdAt(),
        section.updatedAt());
  }
}
