package org.example.auth.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.example.auth.outbox.configuration.OutboxKafkaProperties;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.EventType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxKafkaPublisherTest {

  @Mock private KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate;
  @Mock private OutboxKafkaProperties outboxKafkaProperties;

  @InjectMocks private OutboxKafkaPublisher outboxKafkaPublisher;

  @Test
  void shouldResolveTopicByKeyAndSetHeaders() {
    OutboxEvent event = sampleEvent();

    @SuppressWarnings("unchecked")
    SendResult<UUID, Map<String, Object>> sendResult = mock(SendResult.class);
    CompletableFuture<SendResult<UUID, Map<String, Object>>> future =
        CompletableFuture.completedFuture(sendResult);

    when(outboxKafkaProperties.topicName("outbox-events")).thenReturn("outboxEvents");
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

    CompletableFuture<SendResult<UUID, Map<String, Object>>> returned =
        outboxKafkaPublisher.publishAsync(event);

    assertThat(returned).isSameAs(future);
    verify(outboxKafkaProperties).topicName("outbox-events");

    ArgumentCaptor<ProducerRecord<UUID, Map<String, Object>>> recordCaptor =
        ArgumentCaptor.forClass(ProducerRecord.class);
    verify(kafkaTemplate).send(recordCaptor.capture());

    ProducerRecord<UUID, Map<String, Object>> record = recordCaptor.getValue();
    assertThat(record.topic()).isEqualTo("outboxEvents");
    assertThat(record.key()).isEqualTo(event.getAggregateId());
    assertThat(record.value()).isEqualTo(event.getPayload());

    assertHeader(record, "eventId", event.getId().toString());
    assertHeader(record, "aggregateType", event.getAggregateType().name());
    assertHeader(record, "aggregateId", event.getAggregateId().toString());
    assertHeader(record, "eventType", event.getEventType().name());
    assertHeader(record, "occurredAt", event.getCreatedAt().toString());
    assertHeader(record, "correlationId", event.getCorrelationId().toString());
    assertHeader(record, "schemaVersion", "1");
  }

  @Test
  void shouldPropagateSendFailureViaReturnedFuture() {
    OutboxEvent event = sampleEvent();
    RuntimeException sendFailure = new RuntimeException("kafka unavailable");
    CompletableFuture<SendResult<UUID, Map<String, Object>>> failedFuture =
        CompletableFuture.failedFuture(sendFailure);

    when(outboxKafkaProperties.topicName("outbox-events")).thenReturn("outboxEvents");
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

    CompletableFuture<SendResult<UUID, Map<String, Object>>> returned =
        outboxKafkaPublisher.publishAsync(event);

    assertThat(returned).isSameAs(failedFuture);
    assertThatThrownBy(returned::join).hasCause(sendFailure);
    verify(outboxKafkaProperties).topicName(eq("outbox-events"));
  }

  private static OutboxEvent sampleEvent() {
    return new OutboxEvent(
        UUID.randomUUID(),
        AggregateType.AUTHORISATION,
        UUID.randomUUID(),
        EventType.AUTHORISATION_AUTHORISED,
        Map.of("amount", 10, "currencyCode", "USD"),
        OffsetDateTime.parse("2026-07-08T10:00:00Z"),
        "idem-100",
        UUID.randomUUID());
  }

  private static void assertHeader(
      ProducerRecord<UUID, Map<String, Object>> record, String key, String expectedValue) {
    Header header = record.headers().lastHeader(key);
    assertThat(header).isNotNull();
    assertThat(new String(header.value(), StandardCharsets.UTF_8)).isEqualTo(expectedValue);
  }
}

