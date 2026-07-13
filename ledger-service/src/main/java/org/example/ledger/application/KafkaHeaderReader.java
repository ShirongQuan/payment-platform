package org.example.ledger.application;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.example.ledger.domain.EventMetadata;
import org.springframework.stereotype.Component;

@Component
public class KafkaHeaderReader {

  public EventMetadata read(Headers headers) {
    return new EventMetadata(
        UUID.fromString(header(headers, "eventId")),
        header(headers, "aggregateType"),
        UUID.fromString(header(headers, "aggregateId")),
        header(headers, "eventType"),
        OffsetDateTime.parse(header(headers, "occurredAt")),
        UUID.fromString(header(headers, "correlationId")));
  }

  private String header(Headers headers, String name) {
    Header header = headers.lastHeader(name);
    if (header == null) {
      throw new IllegalArgumentException("Missing required header " + name);
    }
    return new String(header.value(), StandardCharsets.UTF_8);
  }
}
