package com.syncro.sparepart.infrastructure;

import com.syncro.auth.infrastructure.AuthUserEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "sparepart_price_entries")
public class SparepartPriceEntryEntity {
  @Id
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "sparepart_id", nullable = false)
  private SparepartEntity sparepart;

  @Column(nullable = false, precision = 18, scale = 2)
  private BigDecimal amount;

  // CHAR JDBC type matches the migration's fixed-width char(3) ISO-4217 column (bpchar in
  // Postgres); Hibernate would otherwise expect varchar and fail schema validation.
  @JdbcTypeCode(java.sql.Types.CHAR)
  @Column(name = "currency", nullable = false, length = 3)
  private String currency;

  @Column(name = "kurs_to_idr", precision = 18, scale = 6)
  private BigDecimal kursToIdr;

  @Column(name = "idr_amount", nullable = false, precision = 18, scale = 2)
  private BigDecimal idrAmount;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "entered_by", nullable = false)
  private AuthUserEntity enteredBy;

  @Column(name = "entered_at", nullable = false)
  private Instant enteredAt;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  protected SparepartPriceEntryEntity() {
  }

  public SparepartPriceEntryEntity(UUID id, SparepartEntity sparepart, BigDecimal amount, String currency,
      BigDecimal kursToIdr, BigDecimal idrAmount, AuthUserEntity enteredBy, Instant enteredAt) {
    this.id = id;
    this.sparepart = sparepart;
    this.amount = amount;
    this.currency = currency;
    this.kursToIdr = kursToIdr;
    this.idrAmount = idrAmount;
    this.enteredBy = enteredBy;
    this.enteredAt = enteredAt;
  }

  public UUID getId() { return id; }
  public SparepartEntity getSparepart() { return sparepart; }
  public BigDecimal getAmount() { return amount; }
  public String getCurrency() { return currency; }
  public BigDecimal getKursToIdr() { return kursToIdr; }
  public BigDecimal getIdrAmount() { return idrAmount; }
  public AuthUserEntity getEnteredBy() { return enteredBy; }
  public Instant getEnteredAt() { return enteredAt; }
  public long getVersion() { return version; }
}
