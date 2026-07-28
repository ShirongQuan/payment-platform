package org.example.auth.outbox.application;

import java.time.OffsetDateTime;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.outbox.configuration.OutboxBackoffPolicy;
import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.AuthorisationCreatedPayload;
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
      Authorisation authorisation, AuthorisationEventEntity authorisationEventEntity) {
    AuthorisationCreatedPayload payload =
        new AuthorisationCreatedPayload(
            authorisation.getId(),
            authorisation.getAccountId(),
            authorisation.getAmount(),
            authorisation.getCurrencyCode(),
            authorisation.getStatus(),
            authorisation.getCreatedAt(),
            authorisation.getMerchantReference());

    OutboxEvent outboxEvent =
        new OutboxEvent(
            authorisationEventEntity.getEventId(),
            AggregateType.AUTHORISATION,
            authorisation.getId(),
            authorisationEventEntity.getEventType(),
            objectMapper.convertValue(payload, new TypeReference<>() {}),
            OffsetDateTime.now(),
            authorisationEventEntity.getIdempotencyKey(),
            authorisationEventEntity.getCorrelationId());

    outboxEventRepository.save(outboxEventMapper.toEntity(outboxEvent));
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

    int claimed =
        outboxEventRepository.claimNewEvent(
            event.getId(),
            claimedAt,
            claimUntil,
            OutboxEventStatus.PUBLISHING,
            OutboxEventStatus.NEW);
    if (claimed != 1) {
      return;
    }

    outboxKafkaPublisher
        .publishAsync(outboxEventMapper.toDomain(event))
        .whenComplete(
            (result, throwable) -> {
              if (throwable == null) {
                int markPublished =
                    outboxEventRepository.markPublished(
                        event.getId(), OffsetDateTime.now(), OutboxEventStatus.PUBLISHED);
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
                          event.getId(), nextRetryCount, error, OutboxEventStatus.FAILED);
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
                          OutboxEventStatus.NEW);
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
