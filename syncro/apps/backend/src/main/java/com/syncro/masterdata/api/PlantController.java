package com.syncro.masterdata.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.masterdata.api.PlantDtos.PlantListResponse;
import com.syncro.masterdata.api.PlantDtos.PlantRequest;
import com.syncro.masterdata.api.PlantDtos.PlantView;
import com.syncro.masterdata.application.PlantService;
import com.syncro.masterdata.application.PlantService.CreatePlantCommand;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/plants")
public class PlantController {
  private final PlantService plants;

  public PlantController(PlantService plants) {
    this.plants = plants;
  }

  @GetMapping
  public PlantListResponse list(@AuthenticationPrincipal AuthenticatedUser user) {
    return new PlantListResponse(plants.list(user).stream().map(this::toDto).toList());
  }

  @GetMapping("/{plantId}")
  public PlantView get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId) {
    return toDto(plants.get(user, plantId));
  }

  @PostMapping
  public ResponseEntity<PlantView> create(@AuthenticationPrincipal AuthenticatedUser user,
      @Valid @RequestBody PlantRequest request) {
    var created = toDto(plants.create(user, command(request)));
    return ResponseEntity.created(URI.create("/api/v1/plants/" + created.id())).body(created);
  }

  @PutMapping("/{plantId}")
  public PlantView update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId,
      @Valid @RequestBody PlantRequest request) {
    return toDto(plants.update(user, plantId, command(request)));
  }

  @DeleteMapping("/{plantId}")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID plantId) {
    plants.delete(user, plantId);
    return ResponseEntity.noContent().build();
  }

  private CreatePlantCommand command(PlantRequest request) {
    return new CreatePlantCommand(request.code(), request.name());
  }

  private PlantView toDto(PlantService.PlantView plant) {
    return new PlantView(plant.id(), plant.code(), plant.name(), plant.createdAt(), plant.updatedAt());
  }
}
