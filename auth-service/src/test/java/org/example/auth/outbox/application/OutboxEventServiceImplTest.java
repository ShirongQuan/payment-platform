package org.example.auth.outbox.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.example.auth.authorisation.domain.Authorisation;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.outbox.configuration.OutboxBackoffPolicy;
import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.AuthorisationCreatedPayload;
import org.example.auth.outbox.domain.EventType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.example.auth.outbox.infrastructure.OutboxEventEntity;
import org.example.auth.outbox.infrastructure.OutboxEventMapper;
import org.example.auth.outbox.infrastructure.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.support.SendResult;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class OutboxEventServiceImplTest {

  @Mock private OutboxEventRepository outboxEventRepository;

  @Mock private OutboxKafkaPublisher outboxKafkaPublisher;

  @Mock private OutboxBackoffPolicy backoffPolicy;

  @Mock private OutboxPublisherProperties outboxPublisherProperties;

  @Mock private ObjectMapper objectMapper;

  @Spy private OutboxEventMapper outboxEventMapper = Mappers.getMapper(OutboxEventMapper.class);

  @InjectMocks private OutboxEventServiceImpl service;

  @Test
  void shouldEnqueueAuthorisation() {
    UUID accountId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID correctionId = UUID.randomUUID();
    String idempotencyKey = "key";

    Authorisation authorisation =
        new Authorisation(
            accountId, BigDecimal.TEN, "GBP", "reference", AuthorisationStatus.AUTHORISED);

    AuthorisationEventEntity authorisationEventEntity =
        new AuthorisationEventEntity(
            eventId,
            authorisation.getId(),
            accountId,
            EventType.AUTHORISATION_AUTHORISED,
            idempotencyKey,
            BigDecimal.TEN,
            "GBP",
            "",
            correctionId,
            OffsetDateTime.now());

    when(objectMapper.convertValue(
            any(AuthorisationCreatedPayload.class), any(TypeReference.class)))
        .thenReturn(new HashMap<String, Object>());

    service.enqueueAuthorisation(authorisation, authorisationEventEntity);

    ArgumentCaptor<OutboxEventEntity> savedEventCaptor = ArgumentCaptor.forClass(OutboxEventEntity.class);
    verify(outboxEventRepository, times(1)).save(savedEventCaptor.capture());

    OutboxEventEntity savedEntity = savedEventCaptor.getValue();
    assertEquals(eventId, savedEntity.getId());
    assertEquals(authorisationEventEntity.getEventType(), savedEntity.getEventType());
    assertEquals(idempotencyKey, savedEntity.getIdempotencyKey());
    assertEquals(correctionId, savedEntity.getCorrelationId());
  }

  @Test
  void shouldPublishNextBatchAndUpdateEvent() {
    when(outboxPublisherProperties.claimLease()).thenReturn(java.time.Duration.ofSeconds(30));

    when(outboxEventRepository.reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING)))
        .thenReturn(1);

    OutboxEventEntity entity1 = mockOutboxEventEntity(OutboxEventStatus.NEW);
    OutboxEventEntity entity2 = mockOutboxEventEntity(OutboxEventStatus.NEW);

    when(outboxEventRepository.findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class)))
        .thenReturn(List.of(entity1, entity2));

    when(outboxEventRepository.claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHING),
            eq(OutboxEventStatus.NEW)))
        .thenReturn(1);

    when(outboxEventRepository.markPublished(
            any(UUID.class), any(OffsetDateTime.class), eq(OutboxEventStatus.PUBLISHED)))
        .thenReturn(1);

    SendResult<UUID, Map<String, Object>> result = mock(SendResult.class);
    CompletableFuture<SendResult<UUID, Map<String, Object>>> future =
        CompletableFuture.completedFuture(result);
    when(outboxKafkaPublisher.publishAsync(any(OutboxEvent.class))).thenReturn(future);

    service.publishNextBatch(50);

    verify(outboxEventRepository, times(1))
        .reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING));
    verify(outboxEventRepository, times(1))
        .findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class));
    verify(outboxEventRepository, times(2))
        .claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class));
    verify(outboxKafkaPublisher, times(2)).publishAsync(any(OutboxEvent.class));
    verify(outboxEventRepository, times(2))
        .markPublished(any(UUID.class), any(OffsetDateTime.class), eq(OutboxEventStatus.PUBLISHED));
  }

  @Test
  void shouldNotProceedWithPublishingIfNoEventsToPublish() {
    when(outboxEventRepository.reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING)))
        .thenReturn(0);
    when(outboxEventRepository.findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class)))
        .thenReturn(List.of());

    service.publishNextBatch(10);

    verify(outboxEventRepository, times(1))
        .reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING));
    verify(outboxEventRepository, times(1))
        .findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class));
    verify(outboxEventRepository, times(0))
        .claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class));
    verify(outboxKafkaPublisher, times(0)).publishAsync(any(OutboxEvent.class));
  }

  @Test
  void shouldNotProceedWithPublishingIfClaimFails() {
    when(outboxPublisherProperties.claimLease()).thenReturn(java.time.Duration.ofSeconds(30));
    when(outboxEventRepository.reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING)))
        .thenReturn(0);

    OutboxEventEntity event = mock(OutboxEventEntity.class);
    when(event.getId()).thenReturn(UUID.randomUUID());
    when(outboxEventRepository.findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class)))
        .thenReturn(List.of(event));

    when(outboxEventRepository.claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHING),
            eq(OutboxEventStatus.NEW)))
        .thenReturn(0);

    service.publishNextBatch(10);

    verify(outboxKafkaPublisher, times(0)).publishAsync(any(OutboxEvent.class));
    verify(outboxEventRepository, times(0))
        .markPublished(any(UUID.class), any(OffsetDateTime.class), any(OutboxEventStatus.class));
    verify(outboxEventRepository, times(0))
        .markRetry(
            any(UUID.class),
            any(Integer.class),
            any(OffsetDateTime.class),
            any(String.class),
            any(OutboxEventStatus.class));
    verify(outboxEventRepository, times(0))
        .markFailed(
            any(UUID.class), any(Integer.class), any(String.class), any(OutboxEventStatus.class));
  }

  @Test
  void shouldMarkEventRetryWhenPublishFail() {
    when(outboxPublisherProperties.claimLease()).thenReturn(java.time.Duration.ofSeconds(30));
    when(outboxEventRepository.reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING)))
        .thenReturn(0);

    OutboxEventEntity event = mockOutboxEventEntity(OutboxEventStatus.NEW);
    when(outboxEventRepository.findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class)))
        .thenReturn(List.of(event));
    when(outboxEventRepository.claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHING),
            eq(OutboxEventStatus.NEW)))
        .thenReturn(1);

    when(outboxKafkaPublisher.publishAsync(any(OutboxEvent.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("publish failed")));

    when(backoffPolicy.shouldFail(1)).thenReturn(false);
    when(backoffPolicy.nextAttempt(1)).thenReturn(OffsetDateTime.now().plusSeconds(5));

    service.publishNextBatch(10);

    verify(outboxEventRepository, times(1))
        .markRetry(
            eq(event.getId()),
            eq(1),
            any(OffsetDateTime.class),
            any(String.class),
            eq(OutboxEventStatus.NEW));
    verify(outboxEventRepository, times(0))
        .markFailed(
            any(UUID.class), any(Integer.class), any(String.class), any(OutboxEventStatus.class));
    verify(outboxEventRepository, times(0))
        .markPublished(any(UUID.class), any(OffsetDateTime.class), any(OutboxEventStatus.class));
  }

  @Test
  void shouldMarkEventFailWhenPublishFailAndRetryLimitReached() {
    when(outboxPublisherProperties.claimLease()).thenReturn(java.time.Duration.ofSeconds(30));
    when(outboxEventRepository.reclaimStalePublishing(
            any(OffsetDateTime.class), eq(OutboxEventStatus.NEW), eq(OutboxEventStatus.PUBLISHING)))
        .thenReturn(0);

    OutboxEventEntity event = mockOutboxEventEntity(OutboxEventStatus.NEW);
    when(outboxEventRepository.findNextBatch(
            eq(OutboxEventStatus.NEW), any(OffsetDateTime.class), any(PageRequest.class)))
        .thenReturn(List.of(event));
    when(outboxEventRepository.claimNewEvent(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHING),
            eq(OutboxEventStatus.NEW)))
        .thenReturn(1);

    when(outboxKafkaPublisher.publishAsync(any(OutboxEvent.class)))
        .thenReturn(CompletableFuture.failedFuture(new RuntimeException("publish failed")));

    when(backoffPolicy.shouldFail(1)).thenReturn(true);

    service.publishNextBatch(10);

    verify(outboxEventRepository, times(1))
        .markFailed(eq(event.getId()), eq(1), any(String.class), eq(OutboxEventStatus.FAILED));
    verify(outboxEventRepository, times(0))
        .markRetry(
            any(UUID.class),
            any(Integer.class),
            any(OffsetDateTime.class),
            any(String.class),
            any(OutboxEventStatus.class));
    verify(outboxEventRepository, times(0))
        .markPublished(any(UUID.class), any(OffsetDateTime.class), any(OutboxEventStatus.class));
  }

  private OutboxEventEntity mockOutboxEventEntity(OutboxEventStatus status) {
    OutboxEventEntity entity = mock(OutboxEventEntity.class);
    UUID id = UUID.randomUUID();
    when(entity.getId()).thenReturn(id);
    when(entity.getCreatedAt()).thenReturn(OffsetDateTime.now().minusSeconds(2));
    when(entity.getEventType()).thenReturn(EventType.AUTHORISATION_AUTHORISED);
    when(entity.getPayload()).thenReturn(new HashMap<String, Object>());
    when(entity.getAggregateType()).thenReturn(AggregateType.AUTHORISATION.toString());
    when(entity.getAggregateId()).thenReturn(UUID.randomUUID());
    when(entity.getCorrelationId()).thenReturn(UUID.randomUUID());
    when(entity.getIdempotencyKey()).thenReturn("key " + id);
    when(entity.getRetryCount()).thenReturn(0);
    when(entity.getNextAttemptAt()).thenReturn(OffsetDateTime.now());
    when(entity.getStatus()).thenReturn(status);

    switch (status) {
      case NEW -> {
        when(entity.getRetryCount()).thenReturn(0);
        when(entity.getLastError()).thenReturn("");
        when(entity.getClaimedAt()).thenReturn(null);
        when(entity.getClaimUntil()).thenReturn(null);
        when(entity.getPublishedAt()).thenReturn(null);
      }
      case PUBLISHING -> {
        when(entity.getRetryCount()).thenReturn(0);
        when(entity.getLastError()).thenReturn("");
        when(entity.getClaimedAt()).thenReturn(OffsetDateTime.now().minusSeconds(2));
        when(entity.getClaimUntil()).thenReturn(OffsetDateTime.now().plusSeconds(2));
        when(entity.getPublishedAt()).thenReturn(null);
      }
      case PUBLISHED -> {
        when(entity.getRetryCount()).thenReturn(0);
        when(entity.getLastError()).thenReturn("");
        when(entity.getClaimedAt()).thenReturn(null);
        when(entity.getClaimUntil()).thenReturn(null);
        when(entity.getPublishedAt()).thenReturn(OffsetDateTime.now().minusSeconds(2));
      }
      case FAILED -> {
        when(entity.getRetryCount()).thenReturn(5);
        when(entity.getLastError()).thenReturn("Error");
        when(entity.getClaimedAt()).thenReturn(null);
        when(entity.getClaimUntil()).thenReturn(null);
        when(entity.getPublishedAt()).thenReturn(null);
      }
    }
    return entity;
  }
}
