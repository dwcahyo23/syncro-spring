package com.syncro.sparepart.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.api.SparepartDtos.SparepartListResponse;
import com.syncro.sparepart.api.SparepartDtos.SparepartMachineRefView;
import com.syncro.sparepart.api.SparepartDtos.SparepartProcurementRequest;
import com.syncro.sparepart.api.SparepartDtos.SparepartRequest;
import com.syncro.sparepart.api.SparepartDtos.SparepartTaxonomyRefView;
import com.syncro.sparepart.api.SparepartDtos.SparepartView;
import com.syncro.sparepart.application.SparepartService;
import com.syncro.sparepart.application.SparepartService.SparepartCommand;
import com.syncro.sparepart.application.SparepartService.SparepartFilters;
import com.syncro.sparepart.application.SparepartService.SparepartProcurementCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spareparts")
public class SparepartController {
  private final SparepartService spareparts;

  public SparepartController(SparepartService spareparts) {
    this.spareparts = spareparts;
  }

  @Operation(operationId = "listSpareparts", summary = "List spareparts")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Spareparts returned"),
      @ApiResponse(responseCode = "400", description = "Invalid filter value"),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping
  public SparepartListResponse list(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) UUID brandId,
      @RequestParam(required = false) UUID kindId,
      @RequestParam(required = false) UUID typeId,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String machineCode,
      @RequestParam(required = false) UUID machineId,
      @PageableDefault(size = 200, sort = "code") Pageable pageable) {
    var filters = new SparepartFilters(categoryId, brandId, kindId, typeId, search, machineCode, machineId);
    var result = spareparts.list(user, filters, pageable);
    return new SparepartListResponse(result.items().stream().map(this::toDto).toList(), result.totalElements(), result.page(), result.size(), result.sort());
  }

  @Operation(operationId = "getSparepart", summary = "Get sparepart")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sparepart returned"),
      @ApiResponse(responseCode = "400", description = "Invalid sparepart id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "404", description = "Sparepart not found")
  })
  @GetMapping("/{sparepartId}")
  public SparepartView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sparepartId) {
    return toDto(spareparts.get(user, sparepartId));
  }

  @Operation(operationId = "createSparepart", summary = "Create sparepart")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Sparepart created", content = @Content(schema = @Schema(implementation = SparepartView.class))),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate code/name, or invalid taxonomy reference"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "409", description = "Sparepart data integrity conflict")
  })
  @PostMapping
  public ResponseEntity<SparepartView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody SparepartRequest request) {
    var created = toDto(spareparts.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/spareparts/" + created.id())).body(created);
  }

  @Operation(operationId = "updateSparepart", summary = "Update sparepart")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Sparepart updated"),
      @ApiResponse(responseCode = "400", description = "Validation, malformed JSON, duplicate code/name, invalid sparepart id, or invalid taxonomy reference"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart not found"),
      @ApiResponse(responseCode = "409", description = "Sparepart data integrity conflict")
  })
  @PutMapping("/{sparepartId}")
  public SparepartView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sparepartId,
      @Valid @RequestBody SparepartRequest request) {
    return toDto(spareparts.update(user, sparepartId, command(request)));
  }

  @Operation(operationId = "patchSparepartProcurement", summary =
      "Replace the procurement-readiness subset (material code, lead time) of a sparepart")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Procurement values replaced"),
      @ApiResponse(responseCode = "400", description = "Validation or duplicate material code", content = @Content),
      @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
      @ApiResponse(responseCode = "403", description = "Forbidden (role or job scope)", content = @Content),
      @ApiResponse(responseCode = "404", description = "Sparepart not found", content = @Content),
      @ApiResponse(responseCode = "409", description = "Sparepart data integrity conflict", content = @Content)
  })
  @PatchMapping("/{sparepartId}")
  public SparepartView patchProcurement(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId, @Valid @RequestBody SparepartProcurementRequest request) {
    var command = new SparepartProcurementCommand(request.materialCode(), request.leadTimeHours());
    return toDto(spareparts.patchProcurement(user, sparepartId, command));
  }

  @Operation(operationId = "deleteSparepart", summary = "Delete sparepart")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Sparepart deleted", content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid sparepart id"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden"),
      @ApiResponse(responseCode = "404", description = "Sparepart not found"),
      @ApiResponse(responseCode = "409", description = "Sparepart data integrity conflict")
  })
  @DeleteMapping("/{sparepartId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID sparepartId) {
    spareparts.delete(user, sparepartId);
    return ResponseEntity.noContent().build();
  }

  private SparepartCommand command(SparepartRequest request) {
    return new SparepartCommand(request.machineId(), request.categoryId(), request.brandId(), request.kindId(), request.typeId());
  }

  private SparepartView toDto(SparepartService.SparepartView sparepart) {
    return new SparepartView(
        sparepart.id(),
        sparepart.code(),
        toDto(sparepart.machine()),
        toDto(sparepart.category()),
        toDto(sparepart.brand()),
        toDto(sparepart.kind()),
        toDto(sparepart.type()),
        sparepart.materialCode(),
        sparepart.leadTimeHours(),
        sparepart.createdAt(),
        sparepart.updatedAt());
  }

  private SparepartMachineRefView toDto(SparepartService.SparepartMachineRefView machine) {
    return new SparepartMachineRefView(machine.id(), machine.code(), machine.name(), machine.plantId(), machine.plantCode(), machine.plantName());
  }

  private SparepartTaxonomyRefView toDto(SparepartService.SparepartTaxonomyRefView taxonomy) {
    return new SparepartTaxonomyRefView(taxonomy.id(), taxonomy.code(), taxonomy.name());
  }
}
