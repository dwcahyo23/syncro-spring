package com.syncro.sparepart.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.sparepart.api.SparepartImageDtos.SparepartImageView;
import com.syncro.sparepart.application.SparepartImageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/spareparts/{sparepartId}/image")
public class SparepartImageController {
  private final SparepartImageService images;

  public SparepartImageController(SparepartImageService images) {
    this.images = images;
  }

  /**
   * Multipart parts mirror the API contract names: {@code filename} and {@code contentType} are
   * text parts, {@code data} carries the file bytes. Keeping these exact names means the
   * generated client exposes the same field names as the story's DTO.
   */
  @Operation(operationId = "createSparepartImage", summary = "Upload or replace a sparepart's image")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Image stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = SparepartImageView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (role or job scope)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Sparepart not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<SparepartImageView> replace(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId, @RequestParam("filename") String filename,
      @RequestParam("contentType") String contentType, @RequestPart("data") MultipartFile data)
      throws IOException {
    var view = images.replace(user, sparepartId,
        new SparepartImageService.SparepartImageCommand(filename, contentType, data.getBytes()));
    return ResponseEntity.ok(toDto(view));
  }

  @Operation(operationId = "getSparepartImage", summary = "Get a sparepart's image presigned URL")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = SparepartImageView.class))),
      @ApiResponse(responseCode = "400", description = "Invalid sparepart id",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Sparepart or image not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping
  public ResponseEntity<SparepartImageView> get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId) {
    return ResponseEntity.ok(toDto(images.get(user, sparepartId)));
  }

  @Operation(operationId = "deleteSparepartImage", summary = "Remove a sparepart's image")
  @ApiResponses({
      @ApiResponse(responseCode = "204", description = "Image removed (or absent, no-op)",
          content = @Content),
      @ApiResponse(responseCode = "400", description = "Invalid sparepart id",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (role or job scope)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "Sparepart not found",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @DeleteMapping
  public ResponseEntity<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID sparepartId) {
    images.delete(user, sparepartId);
    return ResponseEntity.noContent().build();
  }

  private SparepartImageView toDto(SparepartImageService.SparepartImageView view) {
    return new SparepartImageView(view.sparepartId(), view.objectKey(), view.presignedUrl());
  }
}