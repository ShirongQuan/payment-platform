package org.example.ledger.infrastructure.messaging;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.example.ledger.domain.EventMetadata;
import org.springframework.stereotype.Component;

/** Reads required event metadata headers from Kafka records into typed domain metadata. */
@Slf4j
@Component
public class KafkaHeaderReader {

  public EventMetadata read(Headers headers) {
    EventMetadata metadata =
        new EventMetadata(
        UUID.fromString(header(headers, "eventId")),
        header(headers, "aggregateType"),
        UUID.fromString(header(headers, "aggregateId")),
        header(headers, "eventType"),
        OffsetDateTime.parse(header(headers, "occurredAt")),
        UUID.fromString(header(headers, "correlationId")));
    log.debug(
        "Parsed Kafka headers into metadata, eventId={}, eventType={}, aggregateId={}",
        metadata.eventId(),
        metadata.eventType(),
        metadata.aggregateId());
    return metadata;
  }

  private String header(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    if (header == null) {
      log.warn("Missing required Kafka header: {}", name);
      throw new IllegalArgumentException("Missing required header " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
