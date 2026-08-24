package com.syncro.sparepart.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JobScopeForbiddenException;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.sparepart.application.SparepartImageService;
import com.syncro.sparepart.application.SparepartImageService.ImageNotFoundException;
import com.syncro.sparepart.application.SparepartImageService.MutationForbiddenException;
import com.syncro.sparepart.application.SparepartImageService.NotFoundException;
import com.syncro.sparepart.application.SparepartImageService.SparepartImageCommand;
import com.syncro.sparepart.application.SparepartImageService.SparepartImageView;
import com.syncro.sparepart.application.SparepartImageService.StorageException;
import com.syncro.sparepart.application.SparepartImageService.ValidationException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

@WebMvcTest(SparepartImageController.class)
@Import({SecurityConfig.class, SparepartImageExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class SparepartImageControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private SparepartImageService images;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @Test
  @DisplayName("8.4-API-001 P0 MANAGE uploads an image and receives 200 with view")
  void manageUploadsImage() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var sparepartId = UUID.randomUUID();
    var key = "spareparts/" + sparepartId + "/uuid.jpg";
    when(images.replace(eq(user), eq(sparepartId), any(SparepartImageCommand.class)))
        .thenReturn(new SparepartImageView(sparepartId, key, "https://presigned/" + key));

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", sparepartId)
            .file(new MockMultipartFile("data", "part.jpg", "image/jpeg", new byte[] {1}))
            .param("filename", "part.jpg")
            .param("contentType", "image/jpeg")
            .with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sparepartId").value(sparepartId.toString()))
        .andExpect(jsonPath("$.objectKey").value(key))
        .andExpect(jsonPath("$.presignedUrl").value("https://presigned/" + key));
  }

  @Test
  @DisplayName("8.4-API-002 P0 GET returns 200 with view when image exists")
  void getReturnsView() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    var sparepartId = UUID.randomUUID();
    var key = "spareparts/" + sparepartId + "/uuid.png";
    when(images.get(eq(user), eq(sparepartId)))
        .thenReturn(new SparepartImageView(sparepartId, key, "https://presigned/" + key));

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/image", sparepartId).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sparepartId").value(sparepartId.toString()))
        .andExpect(jsonPath("$.objectKey").value(key))
        .andExpect(jsonPath("$.presignedUrl").value("https://presigned/" + key));
  }

  @Test
  @DisplayName("8.4-API-003 P0 GET returns 404 SPAREPART_IMAGE_NOT_FOUND when no image")
  void getReturnsImageNotFound() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new ImageNotFoundException()).when(images).get(eq(user), any());

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_IMAGE_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.4-API-004 P0 DELETE returns 204")
  void deleteReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MANAGE);

    mockMvc.perform(delete("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("8.4-API-005 P0 below-LEADER job scope returns JOB_SCOPE_REQUIRED with explanation")
  void belowLeaderJobScopeReturnsExplanation() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new JobScopeForbiddenException("LEADER")).when(images).replace(eq(user), any(), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[] {1}))
            .param("filename", "part.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("JOB_SCOPE_REQUIRED"))
        .andExpect(jsonPath("$.message").value("This action requires job scope LEADER or above."));
  }

  @Test
  @DisplayName("8.4-API-006 P0 VIEWER is forbidden by the app-role gate")
  void viewerForbiddenByAppRoleGate() throws Exception {
    var user = user(ApplicationRole.VIEWER);
    doThrow(new MutationForbiddenException()).when(images).replace(eq(user), any(), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[] {1}))
            .param("filename", "part.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  @DisplayName("8.4-API-007 P0 unknown sparepart returns SPAREPART_NOT_FOUND on all endpoints")
  void unknownSparepartReturnsNotFound() throws Exception {
    var user = user(ApplicationRole.SUPER_ADMIN);
    doThrow(new NotFoundException()).when(images).replace(eq(user), any(), any());
    doThrow(new NotFoundException()).when(images).get(eq(user), any());
    doThrow(new NotFoundException()).when(images).delete(eq(user), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[] {1}))
            .param("filename", "part.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));

    mockMvc.perform(get("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));

    mockMvc.perform(delete("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID()).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SPAREPART_NOT_FOUND"));
  }

  @Test
  @DisplayName("8.4-API-008 P0 invalid content type returns VALIDATION_ERROR with fieldErrors")
  void invalidContentTypeReturnsFieldErrors() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new ValidationException(Map.of("contentType", "Content type must be one of image/jpeg, image/png, image/webp, or image/gif.")))
        .when(images).replace(eq(user), any(), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.pdf", "application/pdf", new byte[] {1}))
            .param("filename", "part.pdf")
            .param("contentType", "application/pdf")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.contentType").exists());
  }

  @Test
  @DisplayName("8.4-API-009 P0 oversize upload returns VALIDATION_ERROR with fieldErrors")
  void oversizeUploadReturnsFieldErrors() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new ValidationException(Map.of("data", "Image file exceeds the maximum allowed size.")))
        .when(images).replace(eq(user), any(), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[6 * 1024 * 1024]))
            .param("filename", "part.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.data").exists());
  }

  @Test
  @DisplayName("8.4-API-010 P0 object storage error returns 502")
  void objectStorageError() throws Exception {
    var user = user(ApplicationRole.MANAGE);
    doThrow(new StorageException(new RuntimeException("s3 down")))
        .when(images).replace(eq(user), any(), any());

    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[] {1}))
            .param("filename", "part.png")
            .param("contentType", "image/png")
            .with(auth(user)))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("OBJECT_STORAGE_ERROR"));
  }

  @Test
  @DisplayName("8.4-API-011 P0 unauthenticated request is rejected")
  void unauthenticatedRejected() throws Exception {
    mockMvc.perform(multipart("/api/v1/spareparts/{sparepartId}/image", UUID.randomUUID())
            .file(new MockMultipartFile("data", "part.png", "image/png", new byte[] {1}))
            .param("filename", "part.png")
            .param("contentType", "image/png"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  @DisplayName("8.4-API-012 P0 MaxUploadSizeExceededException maps to 400 VALIDATION_ERROR")
  void maxUploadSizeExceededMapsToValidationError() {
    var handler = new SparepartImageExceptionHandler(
        Clock.fixed(Instant.parse("2026-08-24T00:00:00Z"), ZoneOffset.UTC));
    var response = handler.oversizeUpload();
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    assertThat(response.getBody()).isNotNull();
    assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
    assertThat(response.getBody().fieldErrors()).containsKey("data");
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(),
        role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user, null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}