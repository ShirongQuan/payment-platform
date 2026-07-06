package org.example.authservice.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEventEntity {
  @Id
  @Column(name = "event_id", nullable = false, updatable = false)
  private UUID id;

  @Column(name = "aggregate_type", nullable = false, updatable = false)
  private String aggregateType;

  @Column(name = "aggregate_id", nullable = false, updatable = false)
  private UUID aggregateId;

  @Column(name = "event_type", nullable = false, updatable = false)
  @Enumerated(EnumType.STRING)
  private EventType eventType;

  @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  private Map<String, Object> payload;

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  private OutboxEventStatus status;

  @Column(name = "retry_count", nullable = false)
  private int retryCount;

  @Column(name = "last_error", nullable = true)
  private String lastError;

  @Column(name = "created_at", nullable = false, updatable = false)
  private ZonedDateTime createdAt;

  @Column(name = "published_at", nullable = true)
  private ZonedDateTime publishedAt;

  @Column(name = "idempotency_key", nullable = false)
  private String idempotencyKey;

  // TODO: set to not null
  @Column(name = "correlation_id", nullable = true)
  private UUID correlationId;
}
