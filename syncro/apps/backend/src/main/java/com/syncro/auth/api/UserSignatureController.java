package com.syncro.auth.api;

import com.syncro.auth.api.AuthDtos.UserSignatureView;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.UserSignatureService;
import com.syncro.auth.application.UserSignatureService.InvalidSignaturePasswordException;
import com.syncro.auth.application.UserSignatureService.SignatureBlockedException;
import com.syncro.auth.application.UserSignatureService.SignaturePasswordOutcome;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * User signature storage API (story 22-3, FR-132/133/175). Upload is multipart (mirrors the
 * {@code SparepartImageController} part naming); reads return the stored reference plus a
 * short-TTL presigned URL — never the image bytes. The service owns the owner-or-SUPER_ADMIN
 * gates; rego {@code user_signature_paths} is the coarse parity gate.
 */
@RestController
@RequestMapping("/api/v1/auth/user-signatures")
public class UserSignatureController {

  private final UserSignatureService signatures;

  public UserSignatureController(UserSignatureService signatures) {
    this.signatures = signatures;
  }

  @Operation(operationId = "storeUserSignature",
      summary = "Upload or replace the authenticated user's signature image "
          + "(multipart; password-verified — 3 wrong attempts block saves for 15 minutes)")
  @ApiResponses({
      @ApiResponse(responseCode = "201", description = "Signature stored; reference + presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = UserSignatureView.class))),
      @ApiResponse(responseCode = "400", description = "Empty image or multipart error",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "401", description = "Authentication required, or password does not match",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "415", description = "Non-image content type",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "423", description = "Signature saves blocked after too many failed passwords",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "502", description = "Object storage operation failed",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<UserSignatureView> store(@AuthenticationPrincipal AuthenticatedUser user,
      @RequestParam("contentType") String contentType, @RequestParam("password") String password,
      @RequestPart("data") MultipartFile data)
      throws IOException {
    var userId = UUID.fromString(user.id());
    // The counter commit must happen in its own service transaction BEFORE the throw
    // below rolls the request-scoped work back (the store() @Transactional never
    // started yet). Wrong password never reveals whether a signature exists.
    var outcome = signatures.checkSignaturePassword(userId, password);
    if (outcome == SignaturePasswordOutcome.BLOCKED) {
      throw new SignatureBlockedException();
    }
    if (outcome == SignaturePasswordOutcome.INVALID) {
      throw new InvalidSignaturePasswordException();
    }
    var result = signatures.store(user, userId, contentType, data.getBytes());
    // 201 first upload, 200 replacement (the story 22-3 I/O matrix allows either).
    return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
        .body(result.view());
  }

  @Operation(operationId = "getMyUserSignature", summary = "Read the authenticated user's stored signature")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Signature reference + presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = UserSignatureView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "No signature stored",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping("/me")
  public UserSignatureView me(@AuthenticationPrincipal AuthenticatedUser user) {
    return signatures.get(user, UUID.fromString(user.id()));
  }

  @Operation(operationId = "getUserSignature",
      summary = "Read another user's stored signature (SUPER_ADMIN)")
  @ApiResponses({
      @ApiResponse(responseCode = "200", description = "Signature reference + presigned URL returned",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
              schema = @Schema(implementation = UserSignatureView.class))),
      @ApiResponse(responseCode = "401", description = "Authentication required",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "403", description = "Forbidden (not SUPER_ADMIN)",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE)),
      @ApiResponse(responseCode = "404", description = "No signature stored",
          content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE))
  })
  @GetMapping("/{userId}")
  public UserSignatureView get(@AuthenticationPrincipal AuthenticatedUser user,
      @PathVariable UUID userId) {
    return signatures.get(user, userId);
  }
}
