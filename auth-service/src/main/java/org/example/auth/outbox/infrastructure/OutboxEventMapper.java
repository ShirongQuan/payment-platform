package org.example.auth.outbox.infrastructure;

import java.util.Objects;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface OutboxEventMapper {

  @Mapping(target = "aggregateType", expression = "java(outboxEvent.getAggregateType().name())")
  @Mapping(target = "version", ignore = true)
  OutboxEventEntity toEntityInternal(OutboxEvent outboxEvent);

  default OutboxEventEntity toEntity(OutboxEvent outboxEvent) {
    Objects.requireNonNull(outboxEvent, "outboxEvent cannot be null");
    return toEntityInternal(outboxEvent);
  }

  default OutboxEvent toDomain(OutboxEventEntity entity) {
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
        entity.getCorrelationId(),
        entity.getTraceParent());
  }
}
