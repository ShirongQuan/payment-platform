package org.example.ledger.infrastructure.messaging;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.ledger.application.command.LedgerEventProcessor;
import org.example.ledger.domain.EventMetadata;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class LedgerKafkaConsumer {
  private final KafkaHeaderReader kafkaHeaderReader;
  private final LedgerEventProcessor ledgerEventProcessor;

  public LedgerKafkaConsumer(
      KafkaHeaderReader kafkaHeaderReader, LedgerEventProcessor ledgerEventProcessor) {
    this.kafkaHeaderReader = kafkaHeaderReader;
    this.ledgerEventProcessor = ledgerEventProcessor;
  }

  @KafkaListener(topics = "auth.events", groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(ConsumerRecord<UUID, String> record) {

    EventMetadata eventMetadata = kafkaHeaderReader.read(record.headers());
    String rawPayload = record.value();
    log.debug("On message, eventType: {}", eventMetadata.eventType());
    log.debug("Raw message {}", rawPayload);
    log.debug("eventMetaData {}", eventMetadata.toString());

    ledgerEventProcessor.process(eventMetadata, rawPayload);
  }
}
