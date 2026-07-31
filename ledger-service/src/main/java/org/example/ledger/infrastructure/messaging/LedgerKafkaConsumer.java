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
/** Kafka listener that converts headers/payload into domain metadata and routes events for processing. */
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
    log.debug(
        "Received Kafka record, topic={}, partition={}, offset={}, key={}",
        record.topic(),
        record.partition(),
        record.offset(),
        record.key());
    if (record.value() == null || record.value().isBlank()) {
      log.warn(
          "Received empty Kafka payload, topic={}, partition={}, offset={}",
          record.topic(),
          record.partition(),
          record.offset());
    }

    try {
      EventMetadata eventMetadata = kafkaHeaderReader.read(record.headers());
      String rawPayload = record.value();
      log.debug("On message, eventType={}", eventMetadata.eventType());
      log.debug("Raw message {}", rawPayload);
      log.debug("eventMetaData {}", eventMetadata);

      ledgerEventProcessor.process(eventMetadata, rawPayload);
      log.debug("Finished processing Kafka record, eventId={}", eventMetadata.eventId());
    } catch (RuntimeException e) {
      log.error(
          "Kafka message processing failed, topic={}, partition={}, offset={}, key={}",
          record.topic(),
          record.partition(),
          record.offset(),
          record.key(),
          e);
      throw e;
    }
  }
}
