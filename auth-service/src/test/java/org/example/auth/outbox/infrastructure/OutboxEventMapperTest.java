package org.example.auth.outbox.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.EventType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

class OutboxEventMapperTest {

  private final OutboxEventMapper mapper = Mappers.getMapper(OutboxEventMapper.class);

  @Test
  void shouldMapDomainToEntityAndBack() {
    UUID id = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-15T09:00:00Z");

    OutboxEvent event =
        new OutboxEvent(
            id,
            AggregateType.AUTHORISATION,
            aggregateId,
            EventType.AUTHORISATION_AUTHORISED,
            Map.of("amount", 10, "currencyCode", "GBP"),
            OutboxEventStatus.NEW,
            1,
            "retry",
            createdAt,
            createdAt.plusSeconds(30),
            createdAt,
            createdAt.plusSeconds(30),
            null,
            "idem-1",
            correlationId,
            "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01");

    OutboxEventEntity entity = mapper.toEntity(event);
    OutboxEvent mappedBack = mapper.toDomain(entity);

    assertThat(entity.getAggregateType()).isEqualTo("AUTHORISATION");
    assertThat(entity.getEventType()).isEqualTo(EventType.AUTHORISATION_AUTHORISED);
    assertThat(mappedBack.getAggregateType()).isEqualTo(AggregateType.AUTHORISATION);
    assertThat(mappedBack.getEventType()).isEqualTo(EventType.AUTHORISATION_AUTHORISED);
    assertThat(mappedBack.getPayload()).isEqualTo(event.getPayload());
    assertThat(mappedBack.getIdempotencyKey()).isEqualTo(event.getIdempotencyKey());
    assertThat(mappedBack.getCorrelationId()).isEqualTo(event.getCorrelationId());
    assertThat(mappedBack.getTraceParent()).isEqualTo(event.getTraceParent());
  }

  @Test
  void shouldFailFastForNullInput() {
    assertThatThrownBy(() -> mapper.toEntity(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("outboxEvent cannot be null");
    assertThatThrownBy(() -> mapper.toDomain(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("entity cannot be null");
  }
}

