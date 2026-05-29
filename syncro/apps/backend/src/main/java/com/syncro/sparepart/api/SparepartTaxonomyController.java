package com.syncro.sparepart.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.api.SparepartTaxonomyDtos.SparepartTaxonomyListResponse;
import com.syncro.sparepart.api.SparepartTaxonomyDtos.SparepartTaxonomyRequest;
import com.syncro.sparepart.api.SparepartTaxonomyDtos.SparepartTaxonomyView;
import com.syncro.sparepart.application.SparepartTaxonomyService;
import com.syncro.sparepart.application.SparepartTaxonomyService.SparepartTaxonomyCommand;
import com.syncro.sparepart.domain.SparepartTaxonomyDimension;
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
@RequestMapping("/api/v1/sparepart-taxonomies")
public class SparepartTaxonomyController {
  private final SparepartTaxonomyService taxonomy;

  public SparepartTaxonomyController(SparepartTaxonomyService taxonomy) {
    this.taxonomy = taxonomy;
  }

  @Operation(operationId = "listSparepartTaxonomies", summary = "List sparepart taxonomy entries")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sparepart taxonomy entries returned"),
      @ApiResponse(responseCode = "400", description = "Invalid dimension"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public SparepartTaxonomyListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) SparepartTaxonomyDimension dimension,
      @RequestParam(required = false) UUID categoryId) {
    return new SparepartTaxonomyListResponse(taxonomy.list(user, dimension, categoryId).stream().map(this::toDto).toList());
  }

  @Operation(operationId = "getSparepartTaxonomy", summary = "Get sparepart taxonomy entry")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sparepart taxonomy entry returned"),
      @ApiResponse(responseCode = "400", description = "Invalid taxonomy id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Sparepart taxonomy entry not found")
  })
  @GetMapping("/{taxonomyId}")
  public SparepartTaxonomyView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID taxonomyId) {
    return toDto(taxonomy.get(user, taxonomyId));
  }

  @Operation(operationId = "createSparepartTaxonomy", summary = "Create sparepart taxonomy entry")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Sparepart taxonomy entry created", content = @Content(schema = @Schema(implementation = SparepartTaxonomyView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate name, or invalid dimension"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Sparepart taxonomy data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<SparepartTaxonomyView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody SparepartTaxonomyRequest request) {
    var created = toDto(taxonomy.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/sparepart-taxonomies/" + created.id())).body(created);
  }

  @Operation(operationId = "updateSparepartTaxonomy", summary = "Update sparepart taxonomy entry")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sparepart taxonomy entry updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate name, or invalid taxonomy id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart taxonomy entry not found"),
      @ApiResponse(responseCode = "409", description = "Sparepart taxonomy data integrity conflict")
  })
  @PutMapping("/{taxonomyId}")
  public SparepartTaxonomyView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID taxonomyId,
      @Valid @RequestBody SparepartTaxonomyRequest request) {
    return toDto(taxonomy.update(user, taxonomyId, command(request)));
  }

  @Operation(operationId = "deleteSparepartTaxonomy", summary = "Delete sparepart taxonomy entry")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Sparepart taxonomy entry deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid taxonomy id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart taxonomy entry not found"),
      @ApiResponse(responseCode = "409", description = "Sparepart taxonomy data integrity conflict")
  })
  @DeleteMapping("/{taxonomyId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID taxonomyId) {
    taxonomy.delete(user, taxonomyId);
    return ResponseEntity.noContent().build();
  }

  private SparepartTaxonomyCommand command(SparepartTaxonomyRequest request) {
    return new SparepartTaxonomyCommand(request.dimension(), request.code(), request.name(), request.categoryId());
  }

  private SparepartTaxonomyView toDto(SparepartTaxonomyService.SparepartTaxonomyView entry) {
    return new SparepartTaxonomyView(
        entry.id(), entry.dimension(), entry.code(), entry.name(), entry.categoryId(), entry.createdAt(), entry.updatedAt());
  }
}
