package org.example.ledger.infrastructure.messaging;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.ledger.application.command.LedgerEventProcessor;
import org.example.ledger.common.metrics.LedgerMetrics;
import org.example.ledger.domain.EventMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LedgerKafkaConsumerTest {

  @Mock private KafkaHeaderReader kafkaHeaderReader;
  @Mock private LedgerEventProcessor ledgerEventProcessor;
  @Mock private LedgerMetrics ledgerMetrics;

  @InjectMocks private LedgerKafkaConsumer ledgerKafkaConsumer;

  @Test
  void shouldProcessMessage() throws Exception {
    UUID key = UUID.fromString("a5b63e7c-1a37-4798-aa4c-e518d72675f2");
    String rawPayload = "{\"status\":\"AUTHORISED\"}";
    ConsumerRecord<UUID, String> record =
        new ConsumerRecord<>("auth.events", 0, 0L, key, rawPayload);

    EventMetadata metadata =
        new EventMetadata(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            "AUTHORISATION",
            key,
            "AUTHORISATION_AUTHORISED",
            OffsetDateTime.parse("2026-07-14T10:00:00Z"),
            UUID.fromString("00000000-0000-0000-0000-000000000010"));

    when(kafkaHeaderReader.read(record.headers())).thenReturn(metadata);

    ledgerKafkaConsumer.onMessage(record);

    verify(ledgerMetrics).incrementReceived("auth.events");
    verify(kafkaHeaderReader).read(record.headers());
    verify(ledgerEventProcessor).process(metadata, rawPayload);
  }
}
