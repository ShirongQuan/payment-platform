package org.example.auth.outbox.application;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.example.auth.outbox.configuration.OutboxKafkaProperties;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.shared.correlation.CorrelationIdConstants;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Slf4j
@Service
/** Builds Kafka records from outbox events and attaches event metadata headers for consumers. */
public class OutboxKafkaPublisher {

  /** Single-key carrier used to extract a {@code SpanContext} from the stored traceparent. */
  private static final TextMapGetter<String> TRACEPARENT_GETTER =
      new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(String carrier) {
          return List.of("traceparent");
        }

        @Override
        public String get(String carrier, String key) {
          return "traceparent".equals(key) ? carrier : null;
        }
      };

  private final KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate;
  private final OutboxKafkaProperties outboxKafkaProperties;
  private final OpenTelemetry openTelemetry;
  private final Tracer tracer;

  public OutboxKafkaPublisher(
      KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate,
      OutboxKafkaProperties outboxKafkaProperties,
      OpenTelemetry openTelemetry) {
    this.kafkaTemplate = kafkaTemplate;
    this.outboxKafkaProperties = outboxKafkaProperties;
    this.openTelemetry = openTelemetry;
    this.tracer = openTelemetry.getTracer("auth-service.outbox");
  }

  public CompletableFuture<SendResult<UUID, Map<String, Object>>> publishAsync(OutboxEvent event) {
    log.debug(
        "Publishing outbox event to Kafka, eventId={}, eventType={}, aggregateId={}",
        event.getId(),
        event.getEventType(),
        event.getAggregateId());
    UUID key = event.getAggregateId();

    ProducerRecord<UUID, Map<String, Object>> record =
        new ProducerRecord<>(outboxKafkaProperties.name(), key, event.getPayload());

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
                CorrelationIdConstants.CORRELATION_ID_MDC_KEY,
                event.getCorrelationId().toString().getBytes(StandardCharsets.UTF_8)));
    record.headers().add(new RecordHeader("schemaVersion", "1".getBytes(StandardCharsets.UTF_8)));
    log.debug(
        "Prepared Kafka record headers for outbox event, eventId={}, headersCount={}",
        event.getId(),
        record.headers().toArray().length);

    log.debug("Dispatching Kafka send for outbox event, eventId={}", event.getId());

    // The outbox row was written during the original request's transaction, but this publish
    // runs later on an unrelated @Scheduled thread with no in-memory trace context of its own.
    // Rather than making the (possibly long-elapsed) original span a parent - which would inflate
    // its reported duration - we link the two: a fresh producer span for this publish, linked
    // back to the request span that originally created the event.
    Span span = buildPublishSpan(event);
    CompletableFuture<SendResult<UUID, Map<String, Object>>> future;
    try (Scope scope = span.makeCurrent()) {
      future = kafkaTemplate.send(record);
    }
    return future.whenComplete(
        (result, throwable) -> {
          if (throwable != null) {
            span.recordException(throwable);
            span.setStatus(StatusCode.ERROR);
          }
          span.end();
        });
  }

  private Span buildPublishSpan(OutboxEvent event) {
    var spanBuilder =
        tracer
            .spanBuilder("outbox.kafka.publish")
            .setSpanKind(SpanKind.PRODUCER)
            .setAttribute(
                AttributeKey.stringKey("messaging.destination.name"), outboxKafkaProperties.name())
            .setAttribute(AttributeKey.stringKey("outbox.event_id"), event.getId().toString())
            .setAttribute(AttributeKey.stringKey("outbox.event_type"), event.getEventType().name());

    SpanContext originatingSpanContext = extractOriginatingSpanContext(event.getTraceParent());
    if (originatingSpanContext.isValid()) {
      spanBuilder.addLink(originatingSpanContext);
    }
    return spanBuilder.startSpan();
  }

  /**
   * Rehydrates the {@link SpanContext} of the request that originally created this outbox event,
   * from its persisted {@code traceparent}, so it can be attached as a span Link. Returns an
   * invalid {@link SpanContext} (link is skipped) if none was captured.
   */
  private SpanContext extractOriginatingSpanContext(String traceParent) {
    if (traceParent == null || traceParent.isBlank()) {
      return SpanContext.getInvalid();
    }
    Context extracted =
        openTelemetry
            .getPropagators()
            .getTextMapPropagator()
            .extract(Context.root(), traceParent, TRACEPARENT_GETTER);
    return Span.fromContext(extracted).getSpanContext();
  }
}
