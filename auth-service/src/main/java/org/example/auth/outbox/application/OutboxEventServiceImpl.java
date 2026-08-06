package org.example.auth.outbox.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.common.OperationType;
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
  private final OutboxEventRepository outboxEventRepository;
  private final OutboxKafkaPublisher outboxKafkaPublisher;
  private final OutboxBackoffPolicy backoffPolicy;
  private final OutboxPublisherProperties outboxPublisherProperties;
  private final ObjectMapper objectMapper;
  private final OutboxEventMapper outboxEventMapper;

  public OutboxEventServiceImpl(
      OutboxEventRepository outboxEventRepository,
      OutboxKafkaPublisher outboxKafkaPublisher,
      OutboxBackoffPolicy backoffPolicy,
      OutboxPublisherProperties outboxPublisherProperties,
      ObjectMapper objectMapper,
      OutboxEventMapper outboxEventMapper) {
    this.outboxEventRepository = outboxEventRepository;
    this.outboxKafkaPublisher = outboxKafkaPublisher;
    this.backoffPolicy = backoffPolicy;
    this.outboxPublisherProperties = outboxPublisherProperties;
    this.objectMapper = objectMapper;
    this.outboxEventMapper = outboxEventMapper;
  }

  // TODO: improve thread safety
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
            authorisationEventEntity.getCorrelationId());

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
                int markPublished =
                    outboxEventRepository.markPublished(
                        event.getId(),
                        OffsetDateTime.now(),
                        OutboxEventStatus.PUBLISHED,
                        OutboxEventStatus.PUBLISHING);
                if (markPublished == 0) {
                  log.error("Failed to mark event id={} as published", event.getId());
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
                          OutboxEventStatus.PUBLISHING);
                  if (markFailed == 0) {
                    log.error("Failed to mark event id={} as failed", event.getId());
                  }
                } else {
                  int markRetry =
                      outboxEventRepository.markRetry(
                          event.getId(),
                          nextRetryCount,
                          backoffPolicy.nextAttempt(nextRetryCount),
                          error,
                          OutboxEventStatus.NEW,
                          OutboxEventStatus.PUBLISHING);
                  if (markRetry == 0) {
                    log.error("Failed to mark event id={} for retry", event.getId());
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
