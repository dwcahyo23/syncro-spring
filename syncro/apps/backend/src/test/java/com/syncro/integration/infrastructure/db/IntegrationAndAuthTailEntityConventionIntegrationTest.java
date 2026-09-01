package com.syncro.integration.infrastructure.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.syncro.AbstractPostgresIntegrationTest;
import com.syncro.auth.domain.ApplicationRole;
import com.syncro.auth.infrastructure.AuthLoginAuditEntity;
import com.syncro.auth.infrastructure.AuthLoginAuditRepository;
import com.syncro.auth.infrastructure.AuthUserEntity;
import com.syncro.auth.infrastructure.AuthUserRepository;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeEntity;
import com.syncro.auth.infrastructure.PhoneVerificationChallengeRepository;
import com.syncro.auth.infrastructure.PlantEntity;
import com.syncro.auth.infrastructure.PlantRepository;
import com.syncro.auth.infrastructure.UserSignatureEntity;
import com.syncro.auth.infrastructure.UserSignatureRepository;
import com.syncro.inventory.domain.InventoryReservationStatus;
import com.syncro.inventory.domain.InventoryTransferStatus;
import com.syncro.inventory.infrastructure.db.InventoryLocationEntity;
import com.syncro.inventory.infrastructure.db.InventoryLocationRepository;
import com.syncro.inventory.infrastructure.db.InventoryReservationEntity;
import com.syncro.inventory.infrastructure.db.InventoryReservationRepository;
import com.syncro.inventory.infrastructure.db.InventoryTransferEntity;
import com.syncro.inventory.infrastructure.db.InventoryTransferRepository;
import com.syncro.machine.domain.MachineStatus;
import com.syncro.machine.infrastructure.MachineEntity;
import com.syncro.machine.infrastructure.MachineRepository;
import com.syncro.maintenance.infrastructure.db.WorkOrderEntity;
import com.syncro.maintenance.infrastructure.db.WorkOrderRepository;
import com.syncro.maintenance.domain.workorder.WorkOrderStatus;
import com.syncro.masterdata.infrastructure.MachineGroupEntity;
import com.syncro.masterdata.infrastructure.MachineGroupRepository;
import com.syncro.notification.infrastructure.WhatsAppMessageLogEntity;
import com.syncro.notification.infrastructure.WhatsAppMessageLogRepository;
import com.syncro.integration.domain.WebhookDeliveryStatus;
import com.syncro.integration.domain.WebhookDirection;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Story 15-2 convention proof for the remaining small new-module tables: the
 * {@code com.syncro.integration.infrastructure.db} webhook pair plus the inventory
 * transfer/reservation, auth tail (user_signatures, auth_login_audits,
 * phone_verification_challenges) and WhatsApp message log entities. The spec's "5
 * classes" grouping under-counts its own Code Map packages; this class closes the
 * per-package round-trip requirement in one place. Validates against the V1 schema
 * via context boot like the other EntityConventionIntegrationTest classes.
 */
class IntegrationAndAuthTailEntityConventionIntegrationTest extends AbstractPostgresIntegrationTest {

  private static final Instant T0 = Instant.parse("2026-08-31T08:00:00Z");
  private static final Instant T1 = Instant.parse("2026-08-31T09:00:00Z");

  @Autowired
  private PlantRepository plants;
  @Autowired
  private com.syncro.sparepart.infrastructure.SparepartTaxonomyRepository taxonomy;
  @Autowired
  private com.syncro.sparepart.infrastructure.SparepartRepository spareparts;
  @Autowired
  private MachineGroupRepository machineGroups;
  @Autowired
  private MachineRepository machines;
  @Autowired
  private AuthUserRepository users;
  @Autowired
  private WorkOrderRepository workOrders;
  @Autowired
  private InventoryLocationRepository inventoryLocations;
  @Autowired
  private InventoryTransferRepository transfers;
  @Autowired
  private InventoryReservationRepository reservations;
  @Autowired
  private WebhookConfigRepository webhookConfigs;
  @Autowired
  private WebhookDeliveryLogRepository deliveryLogs;
  @Autowired
  private UserSignatureRepository userSignatures;
  @Autowired
  private AuthLoginAuditRepository loginAudits;
  @Autowired
  private PhoneVerificationChallengeRepository phoneChallenges;
  @Autowired
  private WhatsAppMessageLogRepository whatsappLogs;

  private record Fixture(PlantEntity plant, MachineEntity machine, AuthUserEntity user) {
  }

  private Fixture fixture() {
    var plant = plants.saveAndFlush(new PlantEntity(
        UUID.randomUUID(), "T" + UUID.randomUUID().toString().substring(0, 8), "Plant", T0, T0));
    var group = machineGroups.saveAndFlush(new MachineGroupEntity(
        UUID.randomUUID(), plant, "Group", T0, T0));
    var machine = machines.saveAndFlush(new MachineEntity(
        UUID.randomUUID(), plant, group, "MC-" + UUID.randomUUID().toString().substring(0, 8),
        "Machine", MachineStatus.ACTIVE, null, null, null, null, T0, T0));
    var user = users.saveAndFlush(new AuthUserEntity(
        UUID.randomUUID(), "tail-" + UUID.randomUUID() + "@syncro.test", "hash",
        ApplicationRole.STOREKEEPER, true, T0, T0));
    return new Fixture(plant, machine, user);
  }

  private WorkOrderEntity workOrder(Fixture fx) {
    var wo = new WorkOrderEntity("WO-" + UUID.randomUUID().toString().substring(0, 8),
        "INTERNAL", null, WorkOrderStatus.OPEN, null, fx.machine().getId(), "tail test", 0L,
        null, null, null, T0, T0);
    return workOrders.saveAndFlush(wo);
  }

  /**
   * inventory_transfers/reservations.sparepart_id is a real spareparts FK in V1 —
   * persists a minimal sparepart row (BOM columns optional) and returns its id.
   */
  private UUID persistedSparepartId(Fixture fx) {
    var category = taxonomy.saveAndFlush(
        new com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity(
            UUID.randomUUID(), com.syncro.sparepart.domain.SparepartTaxonomyDimension.CATEGORY,
            "CAT-" + UUID.randomUUID().toString().substring(0, 8), "Category", T0, T0));
    var brand = taxonomy.saveAndFlush(
        new com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity(
            UUID.randomUUID(), com.syncro.sparepart.domain.SparepartTaxonomyDimension.BRAND,
            "BR-" + UUID.randomUUID().toString().substring(0, 8), "Brand", category, T0, T0));
    var kind = taxonomy.saveAndFlush(
        new com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity(
            UUID.randomUUID(), com.syncro.sparepart.domain.SparepartTaxonomyDimension.KIND,
            "KD-" + UUID.randomUUID().toString().substring(0, 8), "Kind", category, T0, T0));
    var type = taxonomy.saveAndFlush(
        new com.syncro.sparepart.infrastructure.SparepartTaxonomyEntity(
            UUID.randomUUID(), com.syncro.sparepart.domain.SparepartTaxonomyDimension.TYPE,
            "TP-" + UUID.randomUUID().toString().substring(0, 8), "Type", category, T0, T0));
    var sparepart = spareparts.saveAndFlush(
        new com.syncro.sparepart.infrastructure.SparepartEntity(
            UUID.randomUUID(), "SP-" + UUID.randomUUID().toString().substring(0, 8), "Bearing",
            fx.machine(), category, brand, kind, type, T0, T0));
    return sparepart.getId();
  }

  @Test
  void inventoryTransferRoundTripWithEnumReviewFlow() {
    var fx = fixture();
    var sparepartId = persistedSparepartId(fx);
    var source = inventoryLocations.saveAndFlush(new InventoryLocationEntity(
        UUID.randomUUID(), fx.plant().getId(), "LOC-A-" + UUID.randomUUID().toString().substring(0, 6),
        "Gudang A", null, true, T0, T0));
    var destination = inventoryLocations.saveAndFlush(new InventoryLocationEntity(
        UUID.randomUUID(), fx.plant().getId(), "LOC-B-" + UUID.randomUUID().toString().substring(0, 6),
        "Gudang B", null, true, T0, T0));

    var transfer = transfers.saveAndFlush(new InventoryTransferEntity(
        UUID.randomUUID(), sparepartId, source.getId(), destination.getId(),
        new BigDecimal("12.50"), InventoryTransferStatus.PENDING_APPROVAL, fx.user().getId(),
        null, null, null, T0, T0));

    transfer.review(InventoryTransferStatus.APPROVED, fx.user().getId(), null, T1, T1);
    transfers.saveAndFlush(transfer);

    var reloaded = transfers.findById(transfer.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(InventoryTransferStatus.APPROVED);
    assertThat(reloaded.getQuantity()).isEqualByComparingTo(new BigDecimal("12.5"));
    assertThat(reloaded.getSourceLocationId()).isEqualTo(source.getId());
    assertThat(reloaded.getDestinationLocationId()).isEqualTo(destination.getId());
    assertThat(reloaded.getReviewedBy()).isEqualTo(fx.user().getId());
    assertThat(reloaded.getReviewedAt()).isEqualTo(T1);
    assertThat(reloaded.getRejectionReason()).isNull();
  }

  @Test
  void inventoryReservationRoundTripWithConsumptionAndNulls() {
    var fx = fixture();
    var sparepartId = persistedSparepartId(fx);
    var location = inventoryLocations.saveAndFlush(new InventoryLocationEntity(
        UUID.randomUUID(), fx.plant().getId(), "LOC-C-" + UUID.randomUUID().toString().substring(0, 6),
        "Gudang C", null, true, T0, T0));

    var reservation = reservations.saveAndFlush(new InventoryReservationEntity(
        UUID.randomUUID(), sparepartId, location.getId(), new BigDecimal("5.00"),
        new BigDecimal("5.00"), InventoryReservationStatus.ACTIVE, "WORK_ORDER", null,
        fx.user().getId(), null, null, T1, T0, T0));

    reservation.consume(new BigDecimal("2.00"), fx.user().getId(), T1);
    reservations.saveAndFlush(reservation);

    var reloaded = reservations.findById(reservation.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(InventoryReservationStatus.ACTIVE);
    assertThat(reloaded.getQuantity()).isEqualByComparingTo(new BigDecimal("5"));
    assertThat(reloaded.getRemainingQuantity()).isEqualByComparingTo(new BigDecimal("3"));
    assertThat(reloaded.getConsumedBy()).isEqualTo(fx.user().getId());
    assertThat(reloaded.getCancelledBy()).isNull();
    assertThat(reloaded.getReferenceType()).isEqualTo("WORK_ORDER");
    assertThat(reloaded.getReferenceId()).isNull();
    assertThat(reloaded.getExpiresAt()).isEqualTo(T1);

    reservation.cancel(fx.user().getId(), T1);
    reservations.saveAndFlush(reservation);
    assertThat(reservations.findById(reservation.getId()).orElseThrow().getStatus())
        .isEqualTo(InventoryReservationStatus.CANCELLED);
  }

  @Test
  void webhookConfigAndDeliveryLogRoundTripWithJsonbAndRetries() {
    var fx = fixture();
    var suffix = UUID.randomUUID().toString().substring(0, 8);
    var config = webhookConfigs.saveAndFlush(new WebhookConfigEntity(
        UUID.randomUUID(), "hook-" + suffix, WebhookDirection.OUTBOUND,
        List.of("workorder.created", "workorder.closed"), "https://example.test/hook",
        "hmac-secret-value", true, fx.user().getId(), T0, T0));

    var reloadedConfig = webhookConfigs.findById(config.getId()).orElseThrow();
    assertThat(reloadedConfig.getDirection()).isEqualTo(WebhookDirection.OUTBOUND);
    assertThat(reloadedConfig.getEventTypes()).containsExactly("workorder.created",
        "workorder.closed");
    assertThat(webhookConfigs.findByName("hook-" + suffix)).contains(reloadedConfig);

    var log = deliveryLogs.saveAndFlush(new WebhookDeliveryLogEntity(
        UUID.randomUUID(), config.getId(), "workorder.created",
        Map.of("workOrderId", "WO-1", "status", "OPEN"), null, null, null,
        WebhookDeliveryStatus.PENDING, 0, null, T0, T0));

    log.recordAttempt(WebhookDeliveryStatus.FAILED, 500, "boom", 250, T1, T1);
    deliveryLogs.saveAndFlush(log);
    log.recordAttempt(WebhookDeliveryStatus.DELIVERED, 200, "ok", 80, null, T1);
    deliveryLogs.saveAndFlush(log);

    var reloadedLog = deliveryLogs.findById(log.getId()).orElseThrow();
    assertThat(reloadedLog.getStatus()).isEqualTo(WebhookDeliveryStatus.DELIVERED);
    assertThat(reloadedLog.getAttemptCount()).isEqualTo(2);
    assertThat(reloadedLog.getResponseCode()).isEqualTo(200);
    assertThat(reloadedLog.getPayload()).containsEntry("workOrderId", "WO-1");
    assertThat(reloadedLog.getNextRetryAt()).isNull();
  }

  @Test
  void userSignatureLoginAuditAndPhoneChallengeRoundTrip() {
    var fx = fixture();

    var signature = userSignatures.saveAndFlush(new UserSignatureEntity(
        UUID.randomUUID(), fx.user().getId(), "signatures", "keys/" + fx.user().getId(),
        "image/png", 0, null, T0, T0));
    var reloadedSignature = userSignatures.findById(signature.getId()).orElseThrow();
    assertThat(reloadedSignature.getBucket()).isEqualTo("signatures");
    assertThat(reloadedSignature.getSignatureFailedAttempts()).isZero();
    assertThat(reloadedSignature.getSignatureBlockedUntil()).isNull();
    assertThat(userSignatures.findByUserId(fx.user().getId())).contains(reloadedSignature);

    var audit = loginAudits.saveAndFlush(new AuthLoginAuditEntity(
        UUID.randomUUID(), fx.user().getId(), "tail-user@syncro.test", "10.0.0.9",
        "junit-agent", false, "BAD_CREDENTIALS", T0));
    var reloadedAudit = loginAudits.findById(audit.getId()).orElseThrow();
    assertThat(reloadedAudit.wasSuccess()).isFalse();
    assertThat(reloadedAudit.getFailureReason()).isEqualTo("BAD_CREDENTIALS");
    assertThat(reloadedAudit.getOccurredAt()).isEqualTo(T0);

    var successAudit = loginAudits.saveAndFlush(new AuthLoginAuditEntity(
        UUID.randomUUID(), null, "anonymous-attempt", null, null, true, null, T0));
    var reloadedSuccess = loginAudits.findById(successAudit.getId()).orElseThrow();
    assertThat(reloadedSuccess.getUserId()).isNull();
    assertThat(reloadedSuccess.getIpAddress()).isNull();
    assertThat(reloadedSuccess.wasSuccess()).isTrue();

    var challenge = phoneChallenges.saveAndFlush(new PhoneVerificationChallengeEntity(
        UUID.randomUUID(), fx.user().getId(), "+628123456789", "bcrypt-otp-hash",
        T1, 0, 5, T0, null, T0));
    challenge.registerFailedAttempt();
    phoneChallenges.saveAndFlush(challenge);
    challenge.consume(T1);
    phoneChallenges.saveAndFlush(challenge);

    var reloadedChallenge = phoneChallenges.findById(challenge.getId()).orElseThrow();
    assertThat(reloadedChallenge.getAttemptCount()).isEqualTo(1);
    assertThat(reloadedChallenge.getMaxAttempts()).isEqualTo(5);
    assertThat(reloadedChallenge.getConsumedAt()).isEqualTo(T1);
    assertThat(reloadedChallenge.getOtpHash()).isEqualTo("bcrypt-otp-hash");
  }

  @Test
  void whatsappMessageLogRoundTripWithUniqueWahaMessageId() {
    var fx = fixture();
    var wo = workOrder(fx);
    var wahaMessageId = "waha-" + UUID.randomUUID();

    var log = whatsappLogs.saveAndFlush(new WhatsAppMessageLogEntity(
        UUID.randomUUID(), wahaMessageId, "syncro-main", "628123456789@s.whatsapp.net",
        "+628123456789", "Mesin down", null,
        Map.of("event", "message", "waha", Map.of("payload", true)), wo.getId(),
        fx.user().getId(), T0));

    var reloaded = whatsappLogs.findByWahaMessageId(wahaMessageId).orElseThrow();
    assertThat(reloaded.getWahaMessageId()).isEqualTo(wahaMessageId);
    assertThat(reloaded.getWorkOrderId()).isEqualTo(wo.getId());
    assertThat(reloaded.getUserId()).isEqualTo(fx.user().getId());
    assertThat(reloaded.getPayload()).containsEntry("event", "message");
    assertThat(reloaded.getReceivedAt()).isEqualTo(T0);
    assertThat(reloaded.getText()).isEqualTo("Mesin down");
    assertThat(reloaded.getAttachmentUrl()).isNull();

    var duplicate = new WhatsAppMessageLogEntity(UUID.randomUUID(), wahaMessageId, "s", "c",
        null, null, null, null, null, null, T0);
    assertThatThrownBy(() -> whatsappLogs.saveAndFlush(duplicate))
        .isInstanceOf(RuntimeException.class);
  }
}
