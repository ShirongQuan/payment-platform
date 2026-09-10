package org.example.auth.outbox.application;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapSetter;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.common.OperationType;
import org.example.auth.common.metrics.AuthMetrics;
import org.example.auth.outbox.configuration.OutboxBackoffPolicy;
import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.AuthorisationAuthorisedPayload;
import org.example.auth.outbox.domain.AuthorisationCapturedPayload;
import org.example.auth.outbox.domain.AuthorisationReversedPayload;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.example.auth.outbox.infrastructure.OutboxEventEntity;
import org.example.auth.outbox.infrastructure.OutboxEventMapper;
import org.example.auth.outbox.infrastructure.OutboxEventRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
/**
 * Outbox orchestrator for reliable event publication.
 *
 * <p>Persists business events in the outbox table inside the caller transaction and asynchronously
 * publishes them to Kafka with claim-lease based coordination and retry/fail transitions.
 */
public class OutboxEventServiceImpl implements OutboxEventService {

  /**
   * Lightweight single-slot carrier used to inject the {@code traceparent} header without
   * allocating a {@code Map} on every outbox write. The propagator only ever sets the
   * {@code traceparent} key, so a single-element array is sufficient.
   */
  private static final TextMapSetter<String[]> TRACEPARENT_SETTER =
      (carrier, key, value) -> {
        if (carrier != null && "traceparent".equals(key)) {
          carrier[0] = value;
        }
      };

  private final OutboxEventRepository outboxEventRepository;
  private final OutboxKafkaPublisher outboxKafkaPublisher;
  private final OutboxBackoffPolicy backoffPolicy;
  private final OutboxPublisherProperties outboxPublisherProperties;
  private final ObjectMapper objectMapper;
  private final OutboxEventMapper outboxEventMapper;
  private final OpenTelemetry openTelemetry;
  private final AuthMetrics authMetrics;

  public OutboxEventServiceImpl(
      OutboxEventRepository outboxEventRepository,
      OutboxKafkaPublisher outboxKafkaPublisher,
      OutboxBackoffPolicy backoffPolicy,
      OutboxPublisherProperties outboxPublisherProperties,
      ObjectMapper objectMapper,
      OutboxEventMapper outboxEventMapper,
      OpenTelemetry openTelemetry,
      AuthMetrics authMetrics) {
    this.outboxEventRepository = outboxEventRepository;
    this.outboxKafkaPublisher = outboxKafkaPublisher;
    this.backoffPolicy = backoffPolicy;
    this.outboxPublisherProperties = outboxPublisherProperties;
    this.objectMapper = objectMapper;
    this.outboxEventMapper = outboxEventMapper;
    this.openTelemetry = openTelemetry;
    this.authMetrics = authMetrics;
  }

  /**
   * Captures the W3C {@code traceparent} of the currently active span (e.g. the auth request
   * handling this authorisation) so it can be persisted with the outbox row and later used by the
   * publisher to link the Kafka producer span back to this request's trace, even though publishing
   * happens asynchronously on a separate {@code @Scheduled} thread. Returns {@code null} if no span
   * is active.
   */
  private String captureTraceParent() {
    String[] carrier = new String[1];
    openTelemetry
        .getPropagators()
        .getTextMapPropagator()
        .inject(Context.current(), carrier, TRACEPARENT_SETTER);
    return carrier[0];
  }

  // Enforce being called inside a transaction.
  @Transactional(propagation = Propagation.MANDATORY)
  @Override
  public void enqueueAuthorisation(
      AuthorisationEntity authorisationEntity,
      AuthorisationEventEntity authorisationEventEntity,
      OperationType operationType) {
    log.debug(
        "Enqueueing authorisation outbox event, authorisationId={}, eventId={}, operationType={}",
        authorisationEntity.getId(),
        authorisationEventEntity.getEventId(),
        operationType);

    Map<String, Object> payloadMap =
        switch (operationType) {
          case AUTHORISE -> {
            AuthorisationAuthorisedPayload payload =
                new AuthorisationAuthorisedPayload(
                    authorisationEntity.getId(),
                    authorisationEntity.getAccountId(),
                    authorisationEntity.getAmount(),
                    authorisationEntity.getCurrencyCode(),
                    authorisationEventEntity.getIdempotencyKey(),
                    authorisationEntity.getStatus(),
                    authorisationEntity.getCreatedAt(),
                    authorisationEntity.getMerchantReference());
            yield objectMapper.convertValue(payload, new TypeReference<>() {});
          }
          case CAPTURE -> {
            AuthorisationCapturedPayload payload =
                new AuthorisationCapturedPayload(
                    authorisationEntity.getId(),
                    authorisationEntity.getAccountId(),
                    authorisationEntity.getAmount(),
                    authorisationEntity.getCurrencyCode(),
                    authorisationEventEntity.getIdempotencyKey(),
                    authorisationEntity.getStatus(),
                    authorisationEventEntity.getCreatedAt());
            yield objectMapper.convertValue(payload, new TypeReference<>() {});
          }
          case REVERSE -> {
            AuthorisationReversedPayload payload =
                new AuthorisationReversedPayload(
                    authorisationEntity.getId(),
                    authorisationEntity.getAccountId(),
                    authorisationEntity.getAmount(),
                    authorisationEntity.getCurrencyCode(),
                    authorisationEventEntity.getIdempotencyKey(),
                    authorisationEntity.getStatus(),
                    authorisationEventEntity.getCreatedAt(),
                    authorisationEventEntity.getReasonCode());
            yield objectMapper.convertValue(payload, new TypeReference<>() {});
          }
        };

    OutboxEvent outboxEvent =
        new OutboxEvent(
            authorisationEventEntity.getEventId(),
            AggregateType.AUTHORISATION,
            authorisationEntity.getId(),
            authorisationEventEntity.getEventType(),
            payloadMap,
            OffsetDateTime.now(),
            authorisationEventEntity.getIdempotencyKey(),
            authorisationEventEntity.getCorrelationId(),
            captureTraceParent());

    outboxEventRepository.save(outboxEventMapper.toEntity(outboxEvent));
    log.debug(
        "Saved outbox event, eventId={}, eventType={}, status={}",
        outboxEvent.getId(),
        outboxEvent.getEventType(),
        outboxEvent.getStatus());
  }

  @Override
  public void publishNextBatch(int batchSize) {
    log.debug("Publishing next batch");
    OffsetDateTime now = OffsetDateTime.now();
    int reclaimed =
        outboxEventRepository.reclaimStalePublishing(
            now, OutboxEventStatus.NEW, OutboxEventStatus.PUBLISHING);
    if (reclaimed > 0) {
      log.info("Reclaimed {} stale outbox publishing claims", reclaimed);
    }
    List<OutboxEventEntity> events =
        outboxEventRepository.findNextBatch(
            OutboxEventStatus.NEW, now, PageRequest.of(0, batchSize));

    for (OutboxEventEntity event : events) {
      processEvent(event);
    }
  }

  private void processEvent(OutboxEventEntity event) {
    OffsetDateTime claimedAt = OffsetDateTime.now();
    OffsetDateTime claimUntil = claimedAt.plus(outboxPublisherProperties.claimLease());
    log.debug(
        "Attempting to claim outbox event, eventId={}, claimUntil={}", event.getId(), claimUntil);

    int claimed =
        outboxEventRepository.claimNewEvent(
            event.getId(),
            claimedAt,
            claimUntil,
            OutboxEventStatus.PUBLISHING,
            OutboxEventStatus.NEW);
    if (claimed != 1) {
      log.debug("Skipping publish because claim failed, eventId={}", event.getId());
      return;
    }

    outboxKafkaPublisher
        .publishAsync(outboxEventMapper.toDomain(event))
        .whenComplete(
            (result, throwable) -> {
              if (throwable == null) {
                log.debug("Published outbox event successfully, eventId={}", event.getId());
                OffsetDateTime publishedAt = OffsetDateTime.now();
                int markPublished =
                    outboxEventRepository.markPublished(
                        event.getId(),
                        publishedAt,
                        OutboxEventStatus.PUBLISHED,
                        OutboxEventStatus.PUBLISHING,
                        claimedAt);
                if (markPublished == 0) {
                  // Expected when this claim's lease already expired and the row was reclaimed
                  // and re-published by another attempt before this (stale) completion arrived.
                  log.warn(
                      "Outbox event id={} was not marked published by this claim (claimedAt={});"
                          + " likely reclaimed by a newer attempt after lease expiry",
                      event.getId(),
                      claimedAt);
                } else {
                  // Only record lag for the claim attempt that actually won the race and persisted
                  // PUBLISHED, so a stale/reclaimed completion doesn't double count.
                  Duration lag = Duration.between(event.getCreatedAt(), publishedAt);
                  authMetrics.recordOutboxPublishLag(lag);
                  authMetrics.incrementOutboxPublishSuccess();
                }
              } else {
                Throwable cause = (throwable.getCause() != null) ? throwable.getCause() : throwable;

                int nextRetryCount = event.getRetryCount() + 1;
                String error = truncate(cause.getMessage(), 100);

                if (backoffPolicy.shouldFail(nextRetryCount)) {
                  int markFailed =
                      outboxEventRepository.markFailed(
                          event.getId(),
                          nextRetryCount,
                          error,
                          OutboxEventStatus.FAILED,
                          OutboxEventStatus.PUBLISHING,
                          claimedAt);
                  if (markFailed == 0) {
                    log.warn(
                        "Outbox event id={} was not marked failed by this claim (claimedAt={});"
                            + " likely reclaimed by a newer attempt after lease expiry",
                        event.getId(),
                        claimedAt);
                  } else {
                    authMetrics.incrementOutboxPublishFailed();
                  }
                } else {
                  int markRetry =
                      outboxEventRepository.markRetry(
                          event.getId(),
                          nextRetryCount,
                          backoffPolicy.nextAttempt(nextRetryCount),
                          error,
                          OutboxEventStatus.NEW,
                          OutboxEventStatus.PUBLISHING,
                          claimedAt);
                  if (markRetry == 0) {
                    log.warn(
                        "Outbox event id={} was not marked for retry by this claim (claimedAt={});"
                            + " likely reclaimed by a newer attempt after lease expiry",
                        event.getId(),
                        claimedAt);
                  } else {
                    authMetrics.incrementOutboxPublishRetry();
                  }
                }
              }
            });
  }

  private String truncate(String value, int max) {
    if (value == null) return null;
    return value.length() <= max ? value : value.substring(0, max);
  }
}
