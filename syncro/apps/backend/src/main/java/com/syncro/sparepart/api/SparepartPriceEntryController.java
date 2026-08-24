package com.syncro.sparepart.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.api.SparepartPriceEntryDtos.SparepartPriceEntryRequest;
import com.syncro.sparepart.api.SparepartPriceEntryDtos.SparepartPriceEntryView;
import com.syncro.sparepart.application.SparepartPriceEntryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spareparts/{sparepartId}/price-entries")
public class SparepartPriceEntryController {
  private final SparepartPriceEntryService priceEntries;

  public SparepartPriceEntryController(SparepartPriceEntryService priceEntries) {
    this.priceEntries = priceEntries;
  }

  @Operation(operationId = "createSparepartPriceEntries", summary = "Append a price entry to a sparepart's history")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Price entry appended", content = @Content(schema = @Schema(implementation = SparepartPriceEntryView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or malformed JSON", content = @Content),
      @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
      @ApiResponse(responseCode = "403", description = "Forbidden (role or job scope)", content = @Content),
      @ApiResponse(responseCode = "404", description = "Sparepart not found", content = @Content),
      @ApiResponse(responseCode = "409", description = "Price entry data integrity conflict", content = @Content)
  })
  @PostMapping
  public ResponseEntity<SparepartPriceEntryView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId, @Valid @RequestBody SparepartPriceEntryRequest request) {
    var created = toDto(priceEntries.create(user, sparepartId, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/spareparts/" + sparepartId + "/price-entries/" + created.id()))
        .body(created);
  }

  @Operation(operationId = "listSparepartPriceEntries", summary = "List a sparepart's price history (newest first)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Price entries returned"),
      @ApiResponse(responseCode = "400", description = "Invalid sparepart id", content = @Content),
      @ApiResponse(responseCode = "401", description = "Authentication required", content = @Content),
      @ApiResponse(responseCode = "404", description = "Sparepart not found", content = @Content)
  })
  @GetMapping
  public List<SparepartPriceEntryView> list(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId) {
    return priceEntries.list(user, sparepartId).stream().map(this::toDto).toList();
  }

  private SparepartPriceEntryService.PriceEntryCommand command(SparepartPriceEntryRequest request) {
    return new SparepartPriceEntryService.PriceEntryCommand(request.amount(), request.currency(), request.kursToIdr());
  }

  private SparepartPriceEntryView toDto(SparepartPriceEntryService.SparepartPriceEntryView view) {
    return new SparepartPriceEntryView(
        view.id(),
        view.sparepartId(),
        view.amount(),
        view.currency(),
        view.kursToIdr(),
        view.idrAmount(),
        view.enteredBy(),
        view.enteredByName(),
        view.enteredAt());
  }
}
