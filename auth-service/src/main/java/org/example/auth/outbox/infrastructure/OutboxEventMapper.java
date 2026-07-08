package org.example.auth.outbox.infrastructure;

import java.util.Objects;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.OutboxEvent;

public final class OutboxEventMapper {

  private OutboxEventMapper() {}

  public static OutboxEventEntity toEntity(OutboxEvent outboxEvent) {
    Objects.requireNonNull(outboxEvent, "outboxEvent cannot be null");

    OutboxEventEntity entity = new OutboxEventEntity();
    entity.setId(outboxEvent.getId());
    entity.setAggregateType(outboxEvent.getAggregateType().name());
    entity.setAggregateId(outboxEvent.getAggregateId());
    entity.setEventType(outboxEvent.getEventType());
    entity.setPayload(outboxEvent.getPayload());
    entity.setStatus(outboxEvent.getStatus());
    entity.setRetryCount(outboxEvent.getRetryCount());
    entity.setLastError(outboxEvent.getLastError());
    entity.setCreatedAt(outboxEvent.getCreatedAt());
    entity.setNextAttemptAt(outboxEvent.getNextAttemptAt());
    entity.setClaimedAt(outboxEvent.getClaimedAt());
    entity.setClaimUntil(outboxEvent.getClaimUntil());
    entity.setPublishedAt(outboxEvent.getPublishedAt());
    entity.setIdempotencyKey(outboxEvent.getIdempotencyKey());
    entity.setCorrelationId(outboxEvent.getCorrelationId());
    return entity;
  }

  public static OutboxEvent toDomain(OutboxEventEntity entity) {
    Objects.requireNonNull(entity, "entity cannot be null");

    return new OutboxEvent(
        entity.getId(),
        AggregateType.valueOf(entity.getAggregateType()),
        entity.getAggregateId(),
        entity.getEventType(),
        entity.getPayload(),
        entity.getStatus(),
        entity.getRetryCount(),
        entity.getLastError(),
        entity.getCreatedAt(),
        entity.getNextAttemptAt(),
        entity.getClaimedAt(),
        entity.getClaimUntil(),
        entity.getPublishedAt(),
        entity.getIdempotencyKey(),
        entity.getCorrelationId());
  }
}
