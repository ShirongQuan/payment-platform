package org.example.auth.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
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
import org.example.shared.correlation.CorrelationIdConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class OutboxKafkaPublisherTest {

  @Mock private KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate;
  @Mock private OutboxKafkaProperties outboxKafkaProperties;

  private final OpenTelemetry openTelemetry = GlobalOpenTelemetry.get();

  private OutboxKafkaPublisher outboxKafkaPublisher;

  @BeforeEach
  void setUp() {
    outboxKafkaPublisher =
        new OutboxKafkaPublisher(kafkaTemplate, outboxKafkaProperties, openTelemetry);
  }

  @Test
  void shouldResolveTopicByKeyAndSetHeaders() {
    OutboxEvent event = sampleEvent();

    @SuppressWarnings("unchecked")
    SendResult<UUID, Map<String, Object>> sendResult = mock(SendResult.class);
    CompletableFuture<SendResult<UUID, Map<String, Object>>> future =
        CompletableFuture.completedFuture(sendResult);

    when(outboxKafkaProperties.name()).thenReturn("outboxEvents");
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(future);

    CompletableFuture<SendResult<UUID, Map<String, Object>>> returned =
        outboxKafkaPublisher.publishAsync(event);

    // The publisher wraps the KafkaTemplate's future with a whenComplete (to end the tracing
    // span), so `returned` is a distinct-but-equivalent stage rather than the same object.
    assertThat(returned.join()).isSameAs(sendResult);
    // Called twice: once to resolve the topic name for the ProducerRecord, once as a span
    // attribute when building the linked tracing span.
    verify(outboxKafkaProperties, times(2)).name();

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
    assertHeader(record, CorrelationIdConstants.CORRELATION_ID_MDC_KEY, event.getCorrelationId().toString());
    assertHeader(record, "schemaVersion", "1");
  }

  @Test
  void shouldPropagateSendFailureViaReturnedFuture() {
    OutboxEvent event = sampleEvent();
    RuntimeException sendFailure = new RuntimeException("kafka unavailable");
    CompletableFuture<SendResult<UUID, Map<String, Object>>> failedFuture =
        CompletableFuture.failedFuture(sendFailure);

    when(outboxKafkaProperties.name()).thenReturn("outboxEvents");
    when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(failedFuture);

    CompletableFuture<SendResult<UUID, Map<String, Object>>> returned =
        outboxKafkaPublisher.publishAsync(event);

    assertThatThrownBy(returned::join).hasCause(sendFailure);
    verify(outboxKafkaProperties, times(2)).name();
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


