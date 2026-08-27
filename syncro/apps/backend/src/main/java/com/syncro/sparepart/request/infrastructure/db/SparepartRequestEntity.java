package com.syncro.sparepart.request.infrastructure.db;

import com.syncro.sparepart.request.domain.SparepartRequestStatus;
import com.syncro.sparepart.request.domain.SparepartRequestType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted {@code sparepart_requests} row (story 12-1, FR-140/FR-143/FR-144).
 * material_code is a snapshot for storekeeper display; the authoritative code lives on
 * spareparts.material_code. status is limited to REQUESTED/PENDING_COMPLETION in 12-1;
 * story 12-2 extends the CHECK with the full state machine.
 */
@Entity
@Table(name = "sparepart_requests")
public class SparepartRequestEntity {

  @Id
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "request_type", nullable = false, length = 20)
  private SparepartRequestType requestType;

  @Column(name = "work_order_id", length = 50)
  private String workOrderId;

  @Column(name = "machine_id")
  private UUID machineId;

  @Column(name = "sparepart_id")
  private UUID sparepartId;

  @Column(name = "material_code", length = 64)
  private String materialCode;

  @Column(nullable = false)
  private short quantity;

  @Column(name = "est_price_id")
  private UUID estPriceId;

  @Column(name = "est_unit_price", precision = 18, scale = 2)
  private BigDecimal estUnitPrice;

  @Column(name = "purchase_reference_url", length = 2048)
  private String purchaseReferenceUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private SparepartRequestStatus status;

  @Column(name = "requested_by", nullable = false)
  private UUID requestedBy;

  @Column(name = "requested_at", nullable = false)
  private Instant requestedAt;

  @Column(columnDefinition = "text")
  private String notes;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected SparepartRequestEntity() {
  }

  public SparepartRequestEntity(UUID id, SparepartRequestType requestType, String workOrderId, UUID machineId,
      UUID sparepartId, String materialCode, short quantity, UUID estPriceId, BigDecimal estUnitPrice,
      String purchaseReferenceUrl, SparepartRequestStatus status, UUID requestedBy, Instant requestedAt,
      String notes, Instant createdAt, Instant updatedAt) {
    this.id = id;
    this.requestType = requestType;
    this.workOrderId = workOrderId;
    this.machineId = machineId;
    this.sparepartId = sparepartId;
    this.materialCode = materialCode;
    this.quantity = quantity;
    this.estPriceId = estPriceId;
    this.estUnitPrice = estUnitPrice;
    this.purchaseReferenceUrl = purchaseReferenceUrl;
    this.status = status;
    this.requestedBy = requestedBy;
    this.requestedAt = requestedAt;
    this.notes = notes;
    this.createdAt = createdAt;
    this.updatedAt = updatedAt;
  }

  public UUID getId() { return id; }
  public SparepartRequestType getRequestType() { return requestType; }
  public String getWorkOrderId() { return workOrderId; }
  public UUID getMachineId() { return machineId; }
  public UUID getSparepartId() { return sparepartId; }
  public String getMaterialCode() { return materialCode; }
  public short getQuantity() { return quantity; }
  public UUID getEstPriceId() { return estPriceId; }
  public BigDecimal getEstUnitPrice() { return estUnitPrice; }
  public String getPurchaseReferenceUrl() { return purchaseReferenceUrl; }
  public SparepartRequestStatus getStatus() { return status; }
  public UUID getRequestedBy() { return requestedBy; }
  public Instant getRequestedAt() { return requestedAt; }
  public String getNotes() { return notes; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
