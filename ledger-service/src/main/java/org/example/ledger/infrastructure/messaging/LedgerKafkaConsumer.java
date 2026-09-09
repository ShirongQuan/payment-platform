package org.example.ledger.infrastructure.messaging;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.example.ledger.application.command.LedgerEventProcessor;
import org.example.ledger.common.metrics.LedgerMetrics;
import org.example.ledger.domain.EventMetadata;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Kafka listener that converts headers/payload into domain metadata and routes events for processing. */
@Slf4j
@Component
public class LedgerKafkaConsumer {
  private final KafkaHeaderReader kafkaHeaderReader;
  private final LedgerEventProcessor ledgerEventProcessor;
  private final LedgerMetrics ledgerMetrics;

  public LedgerKafkaConsumer(
      KafkaHeaderReader kafkaHeaderReader,
      LedgerEventProcessor ledgerEventProcessor,
      LedgerMetrics ledgerMetrics) {
    this.kafkaHeaderReader = kafkaHeaderReader;
    this.ledgerEventProcessor = ledgerEventProcessor;
    this.ledgerMetrics = ledgerMetrics;
  }

  /**
   * Consumes a single record from the {@code auth.events} topic.
   *
   * <p>Converts Kafka headers into typed {@link EventMetadata}, then hands off the metadata and
   * raw JSON payload to the {@link LedgerEventProcessor} for routing/persistence. Any exception
   * thrown here is rethrown so that the configured {@code DefaultErrorHandler} can retry the
   * record and, if retries are exhausted, publish it to the dead-letter topic.
   */
  @KafkaListener(topics = "auth.events", groupId = "${spring.kafka.consumer.group-id}")
  public void onMessage(ConsumerRecord<UUID, String> record) {
    ledgerMetrics.incrementReceived(record.topic());
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
      // Extract event metadata (eventId, eventType, correlationId, etc.) from headers.
      EventMetadata eventMetadata = kafkaHeaderReader.read(record.headers());
      String rawPayload = record.value();
      log.debug("On message, eventType={}", eventMetadata.eventType());
      log.debug("Raw message {}", rawPayload);
      log.debug("eventMetaData {}", eventMetadata);

      // Delegate to the processor, which routes to the type-specific handler.
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
