package com.syncro.auth.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.api.AuthDtos.LoginAuditListResponse;
import com.syncro.auth.api.AuthDtos.LoginAuditView;
import com.syncro.auth.api.AuthDtos.PhoneChallengeView;
import com.syncro.auth.application.AuthLoginAuditService;
import com.syncro.auth.application.AuthLoginAuditService.AuditReadForbiddenException;
import com.syncro.auth.application.AuthLoginAuditService.LoginAuditNotFoundException;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.application.PhoneVerificationService;
import com.syncro.auth.application.PhoneVerificationService.ChallengeConsumedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExhaustedException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeExpiredException;
import com.syncro.auth.application.PhoneVerificationService.ChallengeNotFoundException;
import com.syncro.auth.application.PhoneVerificationService.InvalidOtpException;
import com.syncro.auth.application.PhoneVerificationService.PhoneChallengeForbiddenException;
import com.syncro.auth.application.PhoneVerificationService.ResendTooEarlyException;
import com.syncro.auth.application.PlantScopeService;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Story 22-2 contract coverage: the audit/challenge endpoints answer with the house
 * error envelope (code/message/timestamp/traceId) for 401/403/404/409 and the happy
 * shapes for 200/201. Services are mocked — gates and lifecycle are unit-tested in
 * the service tests; this locks the HTTP surface.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, AuthExceptionHandler.class, JwtAuthenticationFilter.class, TimeConfig.class,
    TestJsonConfig.class})
class AuthAuditControllerTest {

  private static final UUID AUDIT_ID = UUID.fromString("7b7c6d5e-1111-2222-3333-444455556666");
  private static final UUID CHALLENGE_ID = UUID.fromString("8c8d9e0f-1111-2222-3333-444455556666");
  private static final UUID USER_ID = UUID.fromString("9d9e0f10-1111-2222-3333-444455556666");

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private AuthLoginAuditService loginAudits;

  @MockitoBean
  private PhoneVerificationService phoneChallenges;

  @MockitoBean
  private com.syncro.auth.application.AuthService authService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  @MockitoBean
  private PlantScopeService plantScopes;

  // -- unauthenticated (401) -------------------------------------------------------

  @Test
  void unauthenticatedAuditListReturnsAuthenticationRequired() throws Exception {
    mockMvc.perform(get("/api/v1/auth/login-audits"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  @Test
  void unauthenticatedChallengeIssueReturnsAuthenticationRequired() throws Exception {
    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"0812345678\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));
  }

  // -- audit reads (200 / 403 / 404) -----------------------------------------------

  @Test
  void superAdminListReturnsPagedNewestFirst() throws Exception {
    when(loginAudits.list(any(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20)))
        .thenReturn(new LoginAuditListResponse(List.of(auditView(true, null)), 1, 1, 0, 20, "occurredAt,id:desc"));

    mockMvc.perform(get("/api/v1/auth/login-audits")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].wasSuccess").value(true))
        .andExpect(jsonPath("$.items[0].failureReason").value(org.hamcrest.Matchers.nullValue()))
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.totalPages").value(1))
        .andExpect(jsonPath("$.sort").value("occurredAt,id:desc"))
        .andExpect(jsonPath("$.items[0].password").doesNotExist());
  }

  @Test
  void auditorDetailReturnsView() throws Exception {
    when(loginAudits.get(any(), eq(AUDIT_ID))).thenReturn(auditView(false, "INVALID_CREDENTIALS"));

    mockMvc.perform(get("/api/v1/auth/login-audits/" + AUDIT_ID)
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.AUDITOR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(AUDIT_ID.toString()))
        .andExpect(jsonPath("$.failureReason").value("INVALID_CREDENTIALS"));
  }

  @Test
  void technicianAuditReadIsForbidden() throws Exception {
    doThrow(new AuditReadForbiddenException()).when(loginAudits)
        .list(any(), isNull(), isNull(), isNull(), isNull(), eq(0), eq(20));

    mockMvc.perform(get("/api/v1/auth/login-audits")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.TECHNICIAN))))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.message").value("You do not have permission to access this resource."))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void unknownAuditDetailIsNotFound() throws Exception {
    doThrow(new LoginAuditNotFoundException()).when(loginAudits).get(any(), eq(AUDIT_ID));

    mockMvc.perform(get("/api/v1/auth/login-audits/" + AUDIT_ID)
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("LOGIN_AUDIT_NOT_FOUND"));
  }

  // -- challenge lifecycle (201 / 400 / 403 / 404 / 409) ----------------------------

  @Test
  void superAdminIssueReturns201WithIdAndExpiryOnly() throws Exception {
    when(phoneChallenges.issue(any(), eq(USER_ID), eq("0812345678")))
        .thenReturn(challengeView(null));

    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"0812345678\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(CHALLENGE_ID.toString()))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty())
        .andExpect(jsonPath("$.otp").doesNotExist())
        .andExpect(jsonPath("$.otpHash").doesNotExist());
  }

  @Test
  void issueWithBlankPhoneIsValidationError() throws Exception {
    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void issueWithSeparatorOnlyPhoneIsValidationError() throws Exception {
    // Review 22-2 P5: the pattern must require a digit after the optional '+'.
    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"-------\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"        \"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void auditorChallengeMutationIsForbidden() throws Exception {
    doThrow(new PhoneChallengeForbiddenException()).when(phoneChallenges).issue(any(), any(), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.AUDITOR)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"userId\":\"" + USER_ID + "\",\"phoneNumber\":\"0812345678\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void verifyUnknownChallengeIsNotFound() throws Exception {
    doThrow(new ChallengeNotFoundException()).when(phoneChallenges).verify(any(), eq(CHALLENGE_ID), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"123456\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CHALLENGE_NOT_FOUND"));
  }

  @Test
  void verifyWrongOtpIsConflict() throws Exception {
    doThrow(new InvalidOtpException()).when(phoneChallenges).verify(any(), eq(CHALLENGE_ID), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"000000\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_OTP"))
        .andExpect(jsonPath("$.timestamp").isNotEmpty())
        .andExpect(jsonPath("$.traceId").isNotEmpty());
  }

  @Test
  void verifyExpiredChallengeIsConflict() throws Exception {
    doThrow(new ChallengeExpiredException()).when(phoneChallenges).verify(any(), eq(CHALLENGE_ID), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"123456\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHALLENGE_EXPIRED"));
  }

  @Test
  void verifyExhaustedChallengeIsConflict() throws Exception {
    doThrow(new ChallengeExhaustedException()).when(phoneChallenges).verify(any(), eq(CHALLENGE_ID), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"123456\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHALLENGE_EXHAUSTED"));
  }

  @Test
  void verifyConsumedChallengeIsConflict() throws Exception {
    doThrow(new ChallengeConsumedException()).when(phoneChallenges).verify(any(), eq(CHALLENGE_ID), any());

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"123456\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CHALLENGE_CONSUMED"));
  }

  @Test
  void resendTooEarlyIsConflict() throws Exception {
    doThrow(new ResendTooEarlyException()).when(phoneChallenges).resend(any(), eq(CHALLENGE_ID));

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/resend")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESEND_TOO_EARLY"));
  }

  @Test
  void resendAfterWindowReturns201() throws Exception {
    when(phoneChallenges.resend(any(), eq(CHALLENGE_ID))).thenReturn(challengeView(null));

    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/resend")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN))))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(CHALLENGE_ID.toString()));
  }

  @Test
  void malformedOtpIsValidationError() throws Exception {
    mockMvc.perform(post("/api/v1/auth/phone-challenges/" + CHALLENGE_ID + "/verify")
        .with(SecurityMockMvcRequestPostProcessors.authentication(authenticationFor(ApplicationRole.SUPER_ADMIN)))
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"otp\":\"abc\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static UsernamePasswordAuthenticationToken authenticationFor(ApplicationRole role) {
    var user = new AuthenticatedUser(USER_ID.toString(), role.name().toLowerCase() + "@syncro.dev", role);
    return new UsernamePasswordAuthenticationToken(
        user, null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
  }

  private static LoginAuditView auditView(boolean success, String failureReason) {
    return new LoginAuditView(AUDIT_ID, USER_ID, "tech@syncro.dev", "10.0.0.1", "JUnit-Agent", success,
        failureReason, Instant.parse("2026-09-04T12:00:00Z"));
  }

  private static PhoneChallengeView challengeView(Instant consumedAt) {
    return new PhoneChallengeView(CHALLENGE_ID, USER_ID, "0812345678",
        Instant.parse("2026-09-05T00:15:00Z"), 0, 5,
        Instant.parse("2026-09-05T00:01:00Z"), consumedAt, Instant.parse("2026-09-05T00:00:00Z"));
  }
}
