package org.example.ledger.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ledger_entries")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LedgerEntryEntity {

  @Id
  @Column(name = "entry_id", nullable = false)
  private UUID entryId;

  @Column(name = "event_id", nullable = false, unique = true)
  private UUID eventId;

  @Column(name = "aggregate_type", nullable = false, length = 20)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "account_id")
  private UUID accountId;

  @Column(name = "authorisation_id")
  private UUID authorisationId;

  @Column(name = "event_type", nullable = false, length = 50)
  private String eventType;

  @Column(name = "entry_status", length = 20)
  private String entryStatus;

  @Column(name = "amount")
  private BigDecimal amount;

  @Column(name = "currency_code", length = 3)
  private String currencyCode;

  @Column(name = "merchant_reference", length = 20)
  private String merchantReference;

  @Column(name = "idempotency_key", length = 30)
  private String idempotencyKey;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", columnDefinition = "jsonb")
  private Map<String, Object> payload;
}
