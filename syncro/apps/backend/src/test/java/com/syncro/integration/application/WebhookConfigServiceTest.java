package com.syncro.integration.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.syncro.audit.application.AuditLogWriter;
import com.syncro.audit.application.AuditRecord;
import com.syncro.audit.domain.AuditAction;
import com.syncro.audit.domain.AuditEntityType;
import com.syncro.auth.application.JwtTokenService.AuthenticatedUser;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.integration.api.IntegrationDtos.CreateWebhookConfigRequest;
import com.syncro.integration.api.IntegrationDtos.UpdateWebhookConfigRequest;
import com.syncro.integration.domain.WebhookDirection;
import com.syncro.integration.infrastructure.db.WebhookConfigEntity;
import com.syncro.integration.infrastructure.db.WebhookConfigRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Story 22-1 unit coverage for the SUPER_ADMIN config CRUD: role gate, OUTBOUND
 * validation, masked projections, duplicate-name handling, and audit rows that carry
 * the masked secret in BOTH previous and new values.
 */
@ExtendWith(MockitoExtension.class)
class WebhookConfigServiceTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-09-06T08:00:00Z");
  private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
  private static final UUID CONFIG_ID = UUID.fromString("11111111-2222-3333-4444-555566667777");
  private static final UUID ACTOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

  @Mock
  private WebhookConfigRepository configs;
  @Mock
  private AuditLogWriter auditLog;

  private WebhookConfigService service;
  private AuthenticatedUser admin;

  @BeforeEach
  void setUp() {
    service = new WebhookConfigService(configs, auditLog, FIXED_CLOCK);
    admin = new AuthenticatedUser(ACTOR_ID.toString(), "admin@syncro.dev", ApplicationRole.SUPER_ADMIN);
  }

  private static WebhookConfigEntity entity(String secret) {
    return new WebhookConfigEntity(CONFIG_ID, "erp-hook", WebhookDirection.OUTBOUND,
        List.of("CLOSED"), "https://erp.test/hook", secret, true, ACTOR_ID, FIXED_NOW, FIXED_NOW);
  }

  private static CreateWebhookConfigRequest createRequest(String endpoint, String secret,
      List<String> eventTypes) {
    return new CreateWebhookConfigRequest("erp-hook", WebhookDirection.OUTBOUND, eventTypes,
        endpoint, secret, true);
  }

  // --- role gate -------------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-001 P0 non-admin create is rejected by the service gate")
  void managerCreateForbidden() {
    var manager = new AuthenticatedUser(ACTOR_ID.toString(), "mgr@syncro.dev",
        ApplicationRole.MANAGER_MAINTENANCE);
    assertThatThrownBy(() -> service.create(manager, createRequest("https://x.test", "s3cret", List.of("CLOSED"))))
        .isInstanceOf(WebhookConfigService.WebhookForbiddenException.class);
    verify(configs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-SVC-002 P1 non-admin list/get/update are all rejected")
  void nonAdminReadsAndUpdatesForbidden() {
    var auditor = new AuthenticatedUser(ACTOR_ID.toString(), "aud@syncro.dev", ApplicationRole.AUDITOR);
    assertThatThrownBy(() -> service.list(auditor))
        .isInstanceOf(WebhookConfigService.WebhookForbiddenException.class);
    assertThatThrownBy(() -> service.get(auditor, CONFIG_ID))
        .isInstanceOf(WebhookConfigService.WebhookForbiddenException.class);
    assertThatThrownBy(() -> service.update(auditor, CONFIG_ID,
        new UpdateWebhookConfigRequest(null, null, null, false)))
        .isInstanceOf(WebhookConfigService.WebhookForbiddenException.class);
  }

  // --- validation ------------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-003 P0 OUTBOUND create without endpoint/secret/eventTypes → 400 field errors")
  void outboundCompletenessValidated() {
    var exception = org.junit.jupiter.api.Assertions.assertThrows(
        WebhookConfigService.WebhookValidationException.class,
        () -> service.create(admin, createRequest("  ", "   ", List.of())));
    assertThat(exception.getFieldErrors())
        .containsKeys("endpointUrl", "hmacSecret", "eventTypes");
    verify(configs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-SVC-004 P1 blank event-type entries are rejected")
  void blankEventTypeEntryRejected() {
    assertThatThrownBy(() -> service.create(admin,
        createRequest("https://x.test", "secret", List.of("CLOSED", "  "))))
        .isInstanceOf(WebhookConfigService.WebhookValidationException.class);
  }

  @Test
  @DisplayName("22.1-SVC-005 P1 blank name is rejected")
  void blankNameRejected() {
    assertThatThrownBy(() -> service.create(admin, new CreateWebhookConfigRequest("   ",
        WebhookDirection.OUTBOUND, List.of("CLOSED"), "https://x.test", "secret", true)))
        .isInstanceOf(WebhookConfigService.WebhookValidationException.class);
  }

  // --- masking ---------------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-006 P0 create response masks the secret to its last 4 characters")
  void createMasksSecret() {
    when(configs.findByName("erp-hook")).thenReturn(Optional.empty());
    when(configs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.create(admin, createRequest("https://erp.test/hook", "topsecretvalue",
        List.of("CLOSED")));

    assertThat(view.hmacSecretMasked()).isEqualTo("****alue");
    assertThat(view.endpointUrl()).isEqualTo("https://erp.test/hook");
    assertThat(view.eventTypes()).containsExactly("CLOSED");
  }

  @Test
  @DisplayName("22.1-SVC-007 P1 short and null secrets mask without leaking material")
  void shortAndNullSecretsMask() {
    assertThat(WebhookConfigService.maskSecret("abc")).isEqualTo("****");
    assertThat(WebhookConfigService.maskSecret("abcd")).isEqualTo("****");
    assertThat(WebhookConfigService.maskSecret("abcde")).isEqualTo("****bcde");
    assertThat(WebhookConfigService.maskSecret(null)).isNull();
  }

  // --- audit -----------------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-008 P0 create writes a WEBHOOK_CONFIG audit row with masked secret")
  void createAuditsWithMaskedSecret() {
    when(configs.findByName("erp-hook")).thenReturn(Optional.empty());
    when(configs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(admin, createRequest("https://erp.test/hook", "topsecretvalue", List.of("CLOSED")));

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(AuthenticatedUser.class), captor.capture());
    var record = captor.getValue();
    assertThat(record.action()).isEqualTo(AuditAction.CREATE);
    assertThat(record.entityType()).isEqualTo(AuditEntityType.WEBHOOK_CONFIG);
    assertThat(record.previousValue()).isNull();
    assertThat(record.newValue()).containsEntry("hmacSecret", "****alue");
    assertThat(record.newValue()).doesNotContainValue("topsecretvalue");
  }

  @Test
  @DisplayName("22.1-SVC-009 P0 update audits previous AND new values, both masked")
  void updateAuditsMaskedPrevAndNew() {
    var stored = entity("oldsecretvalue");
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.of(stored));
    when(configs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(admin, CONFIG_ID, new UpdateWebhookConfigRequest(
        List.of("CLOSED", "DONE"), null, "brandnewsecret", false));

    assertThat(view.hmacSecretMasked()).isEqualTo("****cret");
    assertThat(view.active()).isFalse();

    var captor = ArgumentCaptor.forClass(AuditRecord.class);
    verify(auditLog).record(any(AuthenticatedUser.class), captor.capture());
    var record = captor.getValue();
    assertThat(record.action()).isEqualTo(AuditAction.UPDATE);
    assertThat(record.previousValue()).containsEntry("hmacSecret", "****alue");
    assertThat(record.newValue()).containsEntry("hmacSecret", "****cret");
    assertThat(record.previousValue()).doesNotContainValue("oldsecretvalue");
    assertThat(record.newValue()).doesNotContainValue("brandnewsecret");
  }

  @Test
  @DisplayName("22.1-SVC-010 P1 update with all-null fields keeps stored values (toggle-only PATCH)")
  void updateNullMergeKeepsValues() {
    var stored = entity("keepme1234");
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.of(stored));
    when(configs.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

    var view = service.update(admin, CONFIG_ID, new UpdateWebhookConfigRequest(null, null, null, false));

    assertThat(view.eventTypes()).containsExactly("CLOSED");
    assertThat(view.endpointUrl()).isEqualTo("https://erp.test/hook");
    assertThat(view.hmacSecretMasked()).isEqualTo("****1234");
    assertThat(view.active()).isFalse();
  }

  @Test
  @DisplayName("22.1-SVC-011 P1 update that empties an OUTBOUND config is rejected")
  void updateCannotEmptyOutbound() {
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.of(entity("keepme1234")));

    assertThatThrownBy(() -> service.update(admin, CONFIG_ID,
        new UpdateWebhookConfigRequest(List.of(), "  ", null, null)))
        .isInstanceOf(WebhookConfigService.WebhookValidationException.class);
    verify(configs, never()).saveAndFlush(any());
  }

  // --- duplicate name ----------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-012 P0 duplicate name (pre-check) → DUPLICATE_WEBHOOK_NAME")
  void duplicateNamePreCheck() {
    when(configs.findByName("erp-hook")).thenReturn(Optional.of(entity("whatever")));

    assertThatThrownBy(() -> service.create(admin, createRequest("https://x.test", "secret",
        List.of("CLOSED"))))
        .isInstanceOf(WebhookConfigService.DuplicateWebhookNameException.class);
    verify(configs, never()).saveAndFlush(any());
  }

  @Test
  @DisplayName("22.1-SVC-013 P1 duplicate name race (constraint) maps to the same error")
  void duplicateNameRaceBackstop() {
    when(configs.findByName("erp-hook")).thenReturn(Optional.empty());
    when(configs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
        "violates unique constraint \"uq_webhook_configs_name\""));

    assertThatThrownBy(() -> service.create(admin, createRequest("https://x.test", "secret",
        List.of("CLOSED"))))
        .isInstanceOf(WebhookConfigService.DuplicateWebhookNameException.class);
  }

  @Test
  @DisplayName("22.1-SVC-014 P2 unrelated integrity violations propagate unchanged")
  void unrelatedIntegrityViolationPropagates() {
    when(configs.findByName("erp-hook")).thenReturn(Optional.empty());
    when(configs.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("other"));

    assertThatThrownBy(() -> service.create(admin, createRequest("https://x.test", "secret",
        List.of("CLOSED"))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  // --- not found ---------------------------------------------------------------

  @Test
  @DisplayName("22.1-SVC-015 P1 unknown config id → WEBHOOK_CONFIG_NOT_FOUND")
  void unknownConfigNotFound() {
    when(configs.findById(CONFIG_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(admin, CONFIG_ID))
        .isInstanceOf(WebhookConfigService.WebhookConfigNotFoundException.class);
  }
}
