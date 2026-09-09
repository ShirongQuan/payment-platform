package org.example.auth.outbox.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.example.auth.authorisation.domain.AuthorisationEventReason;
import org.example.auth.authorisation.domain.AuthorisationStatus;
import org.example.auth.authorisation.infrastructure.AuthorisationEntity;
import org.example.auth.authorisation.infrastructure.AuthorisationEventEntity;
import org.example.auth.common.OperationType;
import org.example.auth.outbox.configuration.OutboxBackoffPolicy;
import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.example.auth.outbox.domain.AggregateType;
import org.example.auth.outbox.domain.AuthorisationAuthorisedPayload;
import org.example.auth.outbox.domain.EventType;
import org.example.auth.outbox.domain.OutboxEvent;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.example.auth.outbox.infrastructure.OutboxEventEntity;
import org.example.auth.outbox.infrastructure.OutboxEventMapper;
import org.example.auth.outbox.infrastructure.OutboxEventRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
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

  @Mock private OpenTelemetry openTelemetry;

  @InjectMocks private OutboxEventServiceImpl service;

  @Test
  void shouldEnqueueAuthorisation() {
    UUID accountId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID correctionId = UUID.randomUUID();
    String idempotencyKey = "key";

    when(openTelemetry.getPropagators()).thenReturn(ContextPropagators.noop());

    AuthorisationEntity authorisationEntity =
        new AuthorisationEntity(
            UUID.randomUUID(),
            0L,
            accountId,
            BigDecimal.TEN,
            "GBP",
            "reference",
            AuthorisationStatus.AUTHORISED,
            OffsetDateTime.now(),
            OffsetDateTime.now());

    AuthorisationEventEntity authorisationEventEntity =
        new AuthorisationEventEntity(
            eventId,
            authorisationEntity.getId(),
            accountId,
            EventType.AUTHORISATION_AUTHORISED,
            idempotencyKey,
            BigDecimal.TEN,
            "GBP",
            AuthorisationEventReason.NONE,
            correctionId,
            OffsetDateTime.now());

    when(objectMapper.convertValue(
            any(AuthorisationAuthorisedPayload.class), any(TypeReference.class)))
        .thenReturn(new HashMap<String, Object>());

    service.enqueueAuthorisation(
        authorisationEntity, authorisationEventEntity, OperationType.AUTHORISE);

    ArgumentCaptor<OutboxEventEntity> savedEventCaptor =
        ArgumentCaptor.forClass(OutboxEventEntity.class);
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
            any(UUID.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class)))
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
        .markPublished(
            any(UUID.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class));
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
        .markPublished(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markRetry(
            any(UUID.class),
            any(Integer.class),
            any(OffsetDateTime.class),
            any(String.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markFailed(
            any(UUID.class),
            any(Integer.class),
            any(String.class),
            any(),
            any(),
            any(OffsetDateTime.class));
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
            eq(OutboxEventStatus.NEW),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markFailed(
            any(UUID.class),
            any(Integer.class),
            any(String.class),
            any(),
            any(),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markPublished(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class),
            any(OffsetDateTime.class));
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
        .markFailed(
            eq(event.getId()),
            eq(1),
            any(String.class),
            eq(OutboxEventStatus.FAILED),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markRetry(
            any(UUID.class),
            any(Integer.class),
            any(OffsetDateTime.class),
            any(String.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class),
            any(OffsetDateTime.class));
    verify(outboxEventRepository, times(0))
        .markPublished(
            any(UUID.class),
            any(OffsetDateTime.class),
            any(OutboxEventStatus.class),
            any(OutboxEventStatus.class),
            any(OffsetDateTime.class));
  }

  @Test
  void shouldNotThrow_whenMarkPublishedFailsBecauseClaimWasFencedOut() {
    // Simulates a stale completion arriving after this claim's lease already expired and the
    // row was reclaimed/re-claimed by a newer attempt (different claimedAt fencing token).
    //
    // Step by step:
    // 1. claimLease/reclaimStalePublishing stubbed as normal (no expired claims this cycle) so
    //    the test stays focused on the one scenario below.
    // 2. One NEW event is returned by findNextBatch and successfully claimed (claimNewEvent=1),
    //    so processEvent proceeds to actually publish it.
    // 3. publishAsync succeeds (completed future) -> the "success" branch of the whenComplete
    //    callback runs, which calls markPublished(...).
    // 4. markPublished is stubbed to return 0, modeling the fencing check failing: by the time
    //    this completion runs, the row's claimedAt no longer matches this attempt's claimedAt
    //    because another poller/thread already reclaimed and re-claimed the same row.
    // 5. publishNextBatch(10) must complete without throwing/propagating any exception - a
    //    fenced-out (0 rows updated) markPublished is an expected, benign outcome (logged as a
    //    warning), not an error that should crash the batch.
    // 6. Verify markPublished was still called exactly once for this event, proving the flow
    //    reached and executed the fencing-checked update and handled its "0 rows" result safely.
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
        .thenReturn(1); // this attempt wins the claim

    // Kafka send succeeds -> whenComplete's success branch runs and calls markPublished(...).
    SendResult<UUID, Map<String, Object>> result = mock(SendResult.class);
    when(outboxKafkaPublisher.publishAsync(any(OutboxEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(result));

    // Fencing check fails: another (newer) claim owns the row now, so 0 rows are updated.
    when(outboxEventRepository.markPublished(
            any(UUID.class),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class)))
        .thenReturn(0);

    // Must not throw despite markPublished reporting 0 rows updated.
    service.publishNextBatch(10);

    // markPublished was still attempted exactly once; its "0 rows" result was handled safely.
    verify(outboxEventRepository, times(1))
        .markPublished(
            eq(event.getId()),
            any(OffsetDateTime.class),
            eq(OutboxEventStatus.PUBLISHED),
            eq(OutboxEventStatus.PUBLISHING),
            any(OffsetDateTime.class));
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
