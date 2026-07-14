package org.example.ledger.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.example.ledger.domain.EventMetadata;
import org.junit.jupiter.api.Test;

class KafkaHeaderReaderTest {

  private final KafkaHeaderReader kafkaHeaderReader = new KafkaHeaderReader();

  @Test
  void shouldReadHeaders() {
    UUID eventId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID aggregateId = UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f2");
    UUID correlationId = UUID.fromString("00000000-0000-0000-0000-000000000010");
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-07-14T09:00:00Z");

    RecordHeaders headers = new RecordHeaders();
    headers.add(new RecordHeader("eventId", eventId.toString().getBytes(StandardCharsets.UTF_8)));
    headers.add(new RecordHeader("aggregateType", "AUTHORISATION".getBytes(StandardCharsets.UTF_8)));
    headers.add(new RecordHeader("aggregateId", aggregateId.toString().getBytes(StandardCharsets.UTF_8)));
    headers.add(
        new RecordHeader("eventType", "AUTHORISATION_AUTHORISED".getBytes(StandardCharsets.UTF_8)));
    headers.add(new RecordHeader("occurredAt", occurredAt.toString().getBytes(StandardCharsets.UTF_8)));
    headers.add(
        new RecordHeader("correlationId", correlationId.toString().getBytes(StandardCharsets.UTF_8)));

    EventMetadata metadata = kafkaHeaderReader.read(headers);

    assertThat(metadata.eventId()).isEqualTo(eventId);
    assertThat(metadata.aggregateType()).isEqualTo("AUTHORISATION");
    assertThat(metadata.aggregateId()).isEqualTo(aggregateId);
    assertThat(metadata.eventType()).isEqualTo("AUTHORISATION_AUTHORISED");
    assertThat(metadata.occurredAt()).isEqualTo(occurredAt);
    assertThat(metadata.correlationId()).isEqualTo(correlationId);
  }

  @Test
  void shouldFailForMissingHeader() {
    RecordHeaders headers = new RecordHeaders();
    headers.add(new RecordHeader("aggregateType", "AUTHORISATION".getBytes(StandardCharsets.UTF_8)));

    assertThatThrownBy(() -> kafkaHeaderReader.read(headers))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Missing required header eventId");
  }
}
