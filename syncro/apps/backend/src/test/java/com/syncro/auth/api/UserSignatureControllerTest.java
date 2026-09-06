package com.syncro.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.api.AuthDtos.UserSignatureView;
import com.syncro.auth.application.AuthLoginAuditService;
import com.syncro.auth.application.AuthService;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PhoneVerificationService;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.application.UserSignatureService;
import com.syncro.auth.application.UserSignatureService.SignatureForbiddenException;
import com.syncro.auth.application.UserSignatureService.SignatureNotFoundException;
import com.syncro.auth.application.UserSignatureService.StorageException;
import com.syncro.auth.application.UserSignatureService.StoreResult;
import com.syncro.auth.application.UserSignatureService.UnsupportedContentTypeException;
import com.syncro.auth.application.UserSignatureService.ValidationException;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 22-3 contract coverage: the user-signature endpoints answer with the house error
 * envelope (code/message/timestamp/traceId) for 401/403/404/400/415/502 and the happy
 * shapes for 200/201 — including the multipart upload. The service is mocked; gates and
 * storage behavior are unit-tested in UserSignatureServiceTest.
 */
@WebMvcTest(UserSignatureController.class)
@Import({SecurityConfig.class, AuthExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class,
    TestJsonConfig.class})
class UserSignatureControllerTest {

  private static final UUID USER_ID = UUID.fromString("9d9e0f10-1111-2222-3333-444455556666");
  private static final UUID SIG_ID = UUID.fromString("7b7c6d5e-1111-2222-3333-444455556666");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private UserSignatureService userSignatures;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  // -- unauthenticated (401) -------------------------------------------------------

  @Test
  void unauthenticatedUploadReturnsAuthenticationRequired() throws Exception {
    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "sig.png", "image/png", new byte[]{1}))
            .param("contentType", "image/png"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void unauthenticatedReadReturnsAuthenticationRequired() throws Exception {
    mockMvc.perform(get("/api/v1/auth/user-signatures/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // -- upload (201 / 200 / 400 / 415 / 502) ----------------------------------------

  @Test
  void firstUploadReturns201WithReferenceAndNoImageBytes() throws Exception {
    when(userSignatures.store(any(), eq(USER_ID), eq("image/png"), any()))
        .thenReturn(new StoreResult(view(), true));

    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "sig.png", "image/png", "bytes".getBytes(StandardCharsets.UTF_8)))
            .param("contentType", "image/png")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(SIG_ID.toString()))
        .andExpect(jsonPath("$.sha256").value("a".repeat(64)))
        .andExpect(jsonPath("$.presignedUrl").isNotEmpty())
        .andExpect(jsonPath("$.data").doesNotExist());
  }

  @Test
  void reUploadReturns200() throws Exception {
    when(userSignatures.store(any(), eq(USER_ID), eq("image/png"), any()))
        .thenReturn(new StoreResult(view(), false));

    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "sig.png", "image/png", "bytes".getBytes(StandardCharsets.UTF_8)))
            .param("contentType", "image/png")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isOk());
  }

  @Test
  void emptyImageIsValidationError() throws Exception {
    when(userSignatures.store(any(), any(), any(), any()))
        .thenThrow(new ValidationException(Map.of("data", "Signature image must not be empty.")));

    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "sig.png", "image/png", new byte[0]))
            .param("contentType", "image/png")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.data").value("Signature image must not be empty."))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void missingPartIsValidationError() throws Exception {
    // Review 22-3 P8: a multipart request missing the required part answers with the
    // house envelope, not Spring's default 500.
    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .param("contentType", "image/png")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.data").exists());
  }

  @Test
  void nonImageContentTypeIs415() throws Exception {
    when(userSignatures.store(any(), any(), any(), any())).thenThrow(new UnsupportedContentTypeException());

    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "doc.pdf", "application/pdf", "bytes".getBytes(StandardCharsets.UTF_8)))
            .param("contentType", "application/pdf")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
  }

  @Test
  void storageFailureIs502() throws Exception {
    when(userSignatures.store(any(), any(), any(), any()))
        .thenThrow(new StorageException(new RuntimeException("garage down")));

    mockMvc.perform(multipart("/api/v1/auth/user-signatures")
            .file(new MockMultipartFile("data", "sig.png", "image/png", "bytes".getBytes(StandardCharsets.UTF_8)))
            .param("contentType", "image/png")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value("OBJECT_STORAGE_ERROR"));
  }

  // -- reads (200 / 403 / 404) ------------------------------------------------------

  @Test
  void ownerReadReturnsView() throws Exception {
    when(userSignatures.get(any(), eq(USER_ID))).thenReturn(view());

    mockMvc.perform(get("/api/v1/auth/user-signatures/me")
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectKey").isNotEmpty())
        .andExpect(jsonPath("$.image").doesNotExist());
  }

  @Test
  void superAdminReadOfAnotherUserReturnsView() throws Exception {
    // Review 22-3 P13: the SUPER_ADMIN GET /{userId} happy path.
    when(userSignatures.get(any(), eq(USER_ID))).thenReturn(view());

    mockMvc.perform(get("/api/v1/auth/user-signatures/" + USER_ID)
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(SIG_ID.toString()))
        .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
        .andExpect(jsonPath("$.sha256").value("a".repeat(64)));
  }

  @Test
  void foreignReadIsForbidden() throws Exception {
    doThrow(new SignatureForbiddenException()).when(userSignatures).get(any(), eq(USER_ID));

    mockMvc.perform(get("/api/v1/auth/user-signatures/" + USER_ID)
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void absentSignatureIsNotFound() throws Exception {
    doThrow(new SignatureNotFoundException()).when(userSignatures).get(any(), eq(USER_ID));

    mockMvc.perform(get("/api/v1/auth/user-signatures/" + USER_ID)
            .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SIGNATURE_NOT_FOUND"));
  }

  private static UsernamePasswordAuthenticationToken authenticationFor(ApplicationRole role) {
    var user = new AuthenticatedUser(USER_ID.toString(), role.name().toLowerCase() + "@syncro.dev", role);
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
  }

  private static UserSignatureView view() {
    return new UserSignatureView(SIG_ID, USER_ID, "syncro-spareparts", "user-signatures/" + USER_ID + "/x.png",
        "image/png", "a".repeat(64), Instant.parse("2026-09-06T00:00:00Z"),
        Instant.parse("2026-09-06T00:00:00Z"), "https://garage/presigned");
  }
}
