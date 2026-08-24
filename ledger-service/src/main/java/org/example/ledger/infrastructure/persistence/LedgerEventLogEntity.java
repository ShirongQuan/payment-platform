package org.example.ledger.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
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

/**
 * Raw, append-only audit log of every authorisation event received, keyed by event id.
 *
 * <p>Unlike {@link LedgerEntryEntity} (a business-level projection), this table stores the
 * unmodified event payload/metadata for traceability and replay/debugging purposes.
 */
@Entity
@Table(name = "ledger_event_log")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class LedgerEventLogEntity {

  @Id
  @Column(name = "event_id", nullable = false)
  private UUID eventId;

  @Column(name = "aggregate_type", nullable = false, length = 20)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, length = 30)
  private String eventType;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
  private Map<String, Object> payload;

  @Column(name = "correlation_id")
  private UUID correlationId;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  /** Time this service received/processed the event, distinct from when it occurred upstream. */
  @Column(name = "received_at", nullable = false)
  private OffsetDateTime receivedAt;
}
