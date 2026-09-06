package com.syncro.maintenance.preventive.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.syncro.TestJsonConfig;
import com.syncro.auth.application.JwtTokenService;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.JwtAuthenticationFilter;
import com.syncro.config.SecurityConfig;
import com.syncro.config.TimeConfig;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ApproveCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistCommand;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistResultView;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ChecklistView;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.InvalidStateTransitionException;
import com.syncro.maintenance.preventive.application.PreventiveChecklistService.ScheduleNotFoundException;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.AttachmentView;
import com.syncro.maintenance.preventive.application.PreventiveEvidenceService.EvidenceCommand;
import com.syncro.maintenance.preventive.application.PreventiveProgramService;
import com.syncro.maintenance.preventive.application.PreventiveReportService;
import com.syncro.maintenance.preventive.application.PreventiveScheduleService;
import com.syncro.maintenance.preventive.domain.ChecklistStatus;
import com.syncro.maintenance.preventive.domain.PreventiveChecklistItem;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest({PreventiveProgramController.class, PreventiveScheduleController.class})
@Import({SecurityConfig.class, PreventiveExceptionHandler.class, JwtAuthenticationFilter.class,
    TimeConfig.class, TestJsonConfig.class})
class PreventiveChecklistControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @MockitoBean
  private PreventiveProgramService programs;

  @MockitoBean
  private PreventiveScheduleService schedules;

  @MockitoBean
  private PreventiveChecklistService checklists;

  @MockitoBean
  private PreventiveEvidenceService evidence;

  @MockitoBean
  private PreventiveReportService reportService;

  @MockitoBean
  private JwtTokenService jwtTokenService;

  private static final UUID SCHEDULE_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
  private static final UUID MACHINE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

  @Test
  @DisplayName("11.2-API-001 P0 submit checklist returns 201")
  void submitChecklistReturnsCreated() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(checklists.submit(eq(user), eq(SCHEDULE_ID.toString()), any(ChecklistCommand.class)))
        .thenReturn(resultView());

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/checklist", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"all good\",\"items\":[{\"label\":\"lube\",\"value\":\"ok\"}]}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scheduleId").value(SCHEDULE_ID.toString()));
  }

  @Test
  @DisplayName("11.2-API-002 P0 submit checklist without items maps to 400 VALIDATION_ERROR")
  void submitChecklistMissingItems() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/checklist", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"x\",\"items\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  @DisplayName("11.2-API-003 P0 amend checklist returns 200")
  void amendChecklistReturnsOk() throws Exception {
    var user = user(ApplicationRole.TECHNICIAN);
    when(checklists.amend(eq(user), eq(SCHEDULE_ID.toString()), any(ChecklistCommand.class)))
        .thenReturn(resultView());

    mockMvc.perform(put("/api/v1/preventive-schedules/{id}/checklist", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"revised\",\"items\":[{\"label\":\"lube\",\"value\":\"ok\"}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.scheduleId").value(SCHEDULE_ID.toString()));
  }

  @Test
  @DisplayName("11.2-API-004 P0 get checklist returns derived status")
  void getChecklistReturnsStatus() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var view = new ChecklistView(ChecklistStatus.SUBMITTED, resultView());
    when(checklists.get(SCHEDULE_ID.toString())).thenReturn(view);

    mockMvc.perform(get("/api/v1/preventive-schedules/{id}/checklist", SCHEDULE_ID).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUBMITTED"));
  }

  @Test
  @DisplayName("11.2-API-005 P0 approve returns 200")
  void approveReturnsOk() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checklists.approve(eq(user), eq(SCHEDULE_ID.toString()), any(ApproveCommand.class)))
        .thenReturn(resultView());

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/approve", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"signatureObjectKey\":\"preventive/sig.png\",\"signerIdentity\":\"Leader\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.signatureObjectKey").value("preventive/sig.png"));
  }

  @Test
  @DisplayName("22.3-API-001 P0 approve wires first XFF hop + User-Agent into the command (review 22-3 P3)")
  void approveWiresRequestMetadata() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    when(checklists.approve(eq(user), eq(SCHEDULE_ID.toString()), any(ApproveCommand.class)))
        .thenReturn(resultView());

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/approve", SCHEDULE_ID)
            .with(auth(user))
            .header("X-Forwarded-For", "203.0.113.7, 10.0.0.2")
            .header("User-Agent", "JUnit-Checklist-Agent")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"signatureObjectKey\":\"preventive/sig.png\",\"signerIdentity\":\"Leader\"}"))
        .andExpect(status().isOk());

    var captor = org.mockito.ArgumentCaptor.forClass(ApproveCommand.class);
    org.mockito.Mockito.verify(checklists).approve(eq(user), eq(SCHEDULE_ID.toString()), captor.capture());
    org.assertj.core.api.Assertions.assertThat(captor.getValue().ipAddress()).isEqualTo("203.0.113.7");
    org.assertj.core.api.Assertions.assertThat(captor.getValue().userAgent()).isEqualTo("JUnit-Checklist-Agent");
    org.assertj.core.api.Assertions.assertThat(captor.getValue().signatureObjectKey()).isEqualTo("preventive/sig.png");
  }

  @Test
  @DisplayName("11.2-API-006 P0 approve without signature maps to 400 VALIDATION_ERROR")
  void approveMissingSignature() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new com.syncro.maintenance.preventive.application.PreventiveChecklistService.MissingSignatureException())
        .when(checklists).approve(eq(user), eq(SCHEDULE_ID.toString()), any(ApproveCommand.class));

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/approve", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"signatureObjectKey\":\"\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fieldErrors.signatureObjectKey").exists());
  }

  @Test
  @DisplayName("11.2-API-007 P0 approve on bad state maps to 409 INVALID_STATE_TRANSITION")
  void approveBadState() throws Exception {
    var user = user(ApplicationRole.SECTION_LEADER);
    doThrow(new InvalidStateTransitionException())
        .when(checklists).approve(eq(user), eq(SCHEDULE_ID.toString()), any(ApproveCommand.class));

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/approve", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"signatureObjectKey\":\"preventive/sig.png\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVALID_STATE_TRANSITION"));
  }

  @Test
  @DisplayName("11.2-API-008 P0 skip returns 204")
  void skipReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.MAINTENANCE_LEADER);

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/skip", SCHEDULE_ID).with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("11.2-API-009 P0 unknown schedule maps to 404 SCHEDULE_NOT_FOUND")
  void submitUnknownSchedule() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    doThrow(new ScheduleNotFoundException())
        .when(checklists).submit(eq(user), eq(SCHEDULE_ID.toString()), any(ChecklistCommand.class));

    mockMvc.perform(post("/api/v1/preventive-schedules/{id}/checklist", SCHEDULE_ID)
            .with(auth(user))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"notes\":\"x\",\"items\":[{\"label\":\"lube\",\"value\":\"ok\"}]}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
  }

  @Test
  @DisplayName("11.2-API-010 P0 upload evidence returns 201")
  void uploadEvidenceReturnsCreated() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);
    when(evidence.create(eq(user), eq(SCHEDULE_ID), any(EvidenceCommand.class))).thenReturn(attachmentView());

    var file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[] {1, 2, 3});
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/v1/preventive-schedules/{id}/evidence", SCHEDULE_ID).file(file)
            .with(auth(user)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.filename").value("photo.jpg"));
  }

  @Test
  @DisplayName("11.2-API-011 P0 delete evidence returns 204")
  void deleteEvidenceReturnsNoContent() throws Exception {
    var user = user(ApplicationRole.STAFF_MAINTENANCE);

    mockMvc.perform(delete("/api/v1/preventive-schedules/{id}/evidence/{attachmentId}", SCHEDULE_ID,
            UUID.fromString("aaaaaaaa-0000-0000-0000-000000000000"))
            .with(auth(user)))
        .andExpect(status().isNoContent());
  }

  @Test
  @DisplayName("11.2-API-012 P0 list evidence returns 200")
  void listEvidenceReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(evidence.list(SCHEDULE_ID)).thenReturn(List.of(attachmentView()));

    mockMvc.perform(get("/api/v1/preventive-schedules/{id}/evidence", SCHEDULE_ID).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].filename").value("photo.jpg"));
  }

  @Test
  @DisplayName("11.3-API-001 P0 report endpoint returns 200 with checklist and signature")
  void reportReturnsOk() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    var report = new com.syncro.maintenance.preventive.domain.PreventiveReport(SCHEDULE_ID, "Monthly lube",
        com.syncro.maintenance.preventive.domain.PreventiveCategory.MECHANICAL,
        com.syncro.maintenance.preventive.domain.ScheduleType.MONTHLY, true, MACHINE_ID, UUID.randomUUID(),
        UUID.randomUUID(), java.time.LocalDate.of(2026, 9, 15),
        com.syncro.maintenance.preventive.domain.ScheduleStatus.PERFORMED,
        java.time.Instant.parse("2026-08-27T00:00:00Z"), UUID.randomUUID(), null, List.of(), List.of(),
        "https://garage/sig", "Leader", java.time.Instant.parse("2026-08-27T01:00:00Z"), null);
    when(reportService.get(SCHEDULE_ID.toString())).thenReturn(report);

    mockMvc.perform(get("/api/v1/preventive-schedules/{id}/report", SCHEDULE_ID).with(auth(user)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.programTitle").value("Monthly lube"))
        .andExpect(jsonPath("$.signerIdentity").value("Leader"));
  }

  @Test
  @DisplayName("11.3-API-002 P0 report on unknown schedule maps to 404 SCHEDULE_NOT_FOUND")
  void reportNotFound() throws Exception {
    var user = user(ApplicationRole.AUDITOR);
    when(reportService.get(SCHEDULE_ID.toString()))
        .thenThrow(new com.syncro.maintenance.preventive.application.PreventiveReportService.ScheduleNotFoundException());

    mockMvc.perform(get("/api/v1/preventive-schedules/{id}/report", SCHEDULE_ID).with(auth(user)))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("SCHEDULE_NOT_FOUND"));
  }

  private static ChecklistResultView resultView() {
    return new ChecklistResultView(UUID.randomUUID(), SCHEDULE_ID, UUID.randomUUID(),
        Instant.parse("2026-08-27T00:00:00Z"), "all good", UUID.randomUUID(), "ok",
        Instant.parse("2026-08-27T01:00:00Z"), "preventive/sig.png", "Leader",
        List.of(new PreventiveChecklistItem(UUID.randomUUID(), UUID.randomUUID(), (short) 1, "lube", "ok",
            null, null, null)));
  }

  private static AttachmentView attachmentView() {
    return new AttachmentView(UUID.randomUUID(), SCHEDULE_ID, "photo.jpg", "image/jpeg",
        "preventive/" + SCHEDULE_ID + "/x/abc.jpg", 3, UUID.randomUUID(),
        Instant.parse("2026-08-27T00:00:00Z"), null, "https://garage/presigned");
  }

  private static AuthenticatedUser user(ApplicationRole role) {
    return new AuthenticatedUser(UUID.randomUUID().toString(), role.name().toLowerCase() + "@syncro.dev", role);
  }

  private static RequestPostProcessor auth(AuthenticatedUser user) {
    return SecurityMockMvcRequestPostProcessors.authentication(new UsernamePasswordAuthenticationToken(
        user,
        null,
        List.of(new SimpleGrantedAuthority("ROLE_" + user.applicationRole().name()))));
  }
}
