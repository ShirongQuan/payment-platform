package org.example.auth.outbox.application;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.example.auth.outbox.configuration.OutboxKafkaProperties;
import org.example.auth.outbox.domain.OutboxEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class OutboxKafkaPublisher {
  private final KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate;
  private final OutboxKafkaProperties outboxKafkaProperties;

  public OutboxKafkaPublisher(
      KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate,
      OutboxKafkaProperties outboxKafkaProperties) {
    this.kafkaTemplate = kafkaTemplate;
    this.outboxKafkaProperties = outboxKafkaProperties;
  }

  public CompletableFuture<SendResult<UUID, Map<String, Object>>> publishAsync(OutboxEvent event) {
    log.debug("Publishing event to Kafka, id={} ", event.getId());
    UUID key = event.getAggregateId();

    ProducerRecord<UUID, Map<String, Object>> record =
        new ProducerRecord<>(
            outboxKafkaProperties.topicName("outbox-events"), key, event.getPayload());

    record
        .headers()
        .add(
            new RecordHeader("eventId", event.getId().toString().getBytes(StandardCharsets.UTF_8)));
    record
        .headers()
        .add(
            new RecordHeader(
                "aggregateType", event.getAggregateType().name().getBytes(StandardCharsets.UTF_8)));
    record
        .headers()
        .add(
            new RecordHeader(
                "aggregateId", event.getAggregateId().toString().getBytes(StandardCharsets.UTF_8)));
    record
        .headers()
        .add(
            new RecordHeader(
                "eventType", event.getEventType().name().getBytes(StandardCharsets.UTF_8)));
    record
        .headers()
        .add(
            new RecordHeader(
                "occurredAt", event.getCreatedAt().toString().getBytes(StandardCharsets.UTF_8)));
    record
        .headers()
        .add(
            new RecordHeader(
                "correlationId",
                event.getCorrelationId().toString().getBytes(StandardCharsets.UTF_8)));
    record.headers().add(new RecordHeader("schemaVersion", "1".getBytes(StandardCharsets.UTF_8)));

    return (kafkaTemplate.send(record));
  }
}
