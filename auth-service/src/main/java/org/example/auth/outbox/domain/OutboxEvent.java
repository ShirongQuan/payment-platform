package org.example.auth.outbox.domain;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;

@Getter
public class OutboxEvent {
  private final UUID id;
  private final AggregateType aggregateType;
  private final UUID aggregateId;
  private final EventType eventType;
  private Map<String, Object> payload;
  private OutboxEventStatus status;
  private int retryCount;
  private String lastError;
  private final OffsetDateTime createdAt;
  private OffsetDateTime nextAttemptAt;
  private OffsetDateTime claimedAt;
  private OffsetDateTime claimUntil;
  private OffsetDateTime publishedAt;
  private final String idempotencyKey;
  private final UUID correlationId;

  public OutboxEvent(
      UUID id,
      AggregateType aggregateType,
      UUID aggregateId,
      EventType eventType,
      Map<String, Object> payload,
      OutboxEventStatus status,
      int retryCount,
      String lastError,
      OffsetDateTime createdAt,
      OffsetDateTime nextAttemptAt,
      OffsetDateTime claimedAt,
      OffsetDateTime claimUntil,
      OffsetDateTime publishedAt,
      String idempotencyKey,
      UUID correlationId) {
    this.id = Objects.requireNonNull(id, "id cannot be null");
    this.aggregateType = Objects.requireNonNull(aggregateType, "aggregateType cannot be null");
    this.aggregateId = Objects.requireNonNull(aggregateId, "aggregateId cannot be null");
    this.eventType = Objects.requireNonNull(eventType, "eventType cannot be null");
    this.payload = Objects.requireNonNull(payload, "payload cannot be null");
    this.status = Objects.requireNonNull(status, "status cannot be null");
    if (retryCount < 0) {
      throw new IllegalArgumentException("retryCount cannot be negative");
    }
    this.retryCount = retryCount;
    this.lastError = lastError;
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt cannot be null");
    this.nextAttemptAt = Objects.requireNonNull(nextAttemptAt, "nextAttemptAt cannot be null");
    this.claimedAt = claimedAt;
    this.claimUntil = claimUntil;
    this.publishedAt = publishedAt;
    Objects.requireNonNull(idempotencyKey, "idempotencyKey cannot be null");
    if (idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("idempotencyKey cannot be blank");
    }
    this.idempotencyKey = idempotencyKey;
    this.correlationId = Objects.requireNonNull(correlationId, "correlationId cannot be null");
  }

  public OutboxEvent(
      UUID id,
      AggregateType aggregateType,
      UUID aggregateId,
      EventType eventType,
      Map<String, Object> payload,
      OffsetDateTime createdAt,
      String idempotencyKey,
      UUID correlationId) {
    this(
        id,
        aggregateType,
        aggregateId,
        eventType,
        payload,
        OutboxEventStatus.NEW,
        0,
        null,
        createdAt,
        createdAt,
        null,
        null,
        null,
        idempotencyKey,
        correlationId);
  }
}
