package com.syncro.settings.api;

import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.settings.api.SettingsDtos.LogoView;
import com.syncro.settings.application.LogoService;
import com.syncro.settings.application.LogoService.LogoCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Company settings (story 14-3, FR-175). Logo upload is SUPER_ADMIN-only (role gate here,
 * before the service); reads are any-authenticated so print pages can embed the logo
 * without special privileges.
 */
@RestController
@RequestMapping("/api/v1/settings")
public class SettingsController {

  private final LogoService logos;

  public SettingsController(LogoService logos) {
    this.logos = logos;
  }

  /** Returns the current logo presigned URL, or null when no logo is configured. */
  @Operation(operationId = "getCompanyLogo", summary = "Get the company logo presigned URL (null when not configured)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Logo view returned",
          content = @Content(schema = @Schema(implementation = LogoView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required")
  })
  @GetMapping("/logo")
  public LogoView getLogo() {
    var view = logos.get();
    return new LogoView(view.objectKey(), view.presignedUrl());
  }

  /**
   * Uploads (or replaces) the company logo. SUPER_ADMIN only. Multipart parts mirror the
   * evidence contract: {@code filename}/{@code contentType} text parts, {@code data} file
   * bytes.
   */
  @Operation(operationId = "uploadCompanyLogo", summary = "Upload or replace the company logo (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Logo stored; presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = LogoView.class))),
      @ApiResponse(responseCode = "400", description = "Validation or multipart error"),
      @ApiResponse(responseCode = "401", description = "Authentication required"),
      @ApiResponse(responseCode = "403", description = "Forbidden (SUPER_ADMIN only)"),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed")
  })
  @PutMapping(value = "/logo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<LogoView> uploadLogo(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam("filename") String filename,
      @RequestParam("contentType") String contentType,
      @RequestPart("data") MultipartFile data) {
    if (user.applicationRole() != ApplicationRole.SUPER_ADMIN) {
      return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
    }
    try {
      var view = logos.replace(new LogoCommand(filename, contentType, data.getBytes()));
      return ResponseEntity.ok(new LogoView(view.objectKey(), view.presignedUrl()));
    } catch (IOException exception) {
      // Multipart body read failure — wrap so the exception handler maps it (never a raw 500).
      throw new LogoBodyReadException(exception);
    }
  }

  /** Wraps a multipart body read failure so the settings handler can map it to 400. */
  public static class LogoBodyReadException extends RuntimeException {
    public LogoBodyReadException(Throwable cause) {
      super(cause);
    }
  }
}