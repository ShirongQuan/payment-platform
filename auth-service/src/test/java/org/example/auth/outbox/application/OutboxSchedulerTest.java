package org.example.auth.outbox.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class OutboxSchedulerTest {

  @Mock private OutboxEventService outboxEventService;
  @Mock private OutboxPublisherProperties outboxPublisherProperties;

  @InjectMocks private OutboxScheduler outboxScheduler;

  @Test
  void shouldCallPublishNextBatchUsingConfiguredBatchSize() {
    when(outboxPublisherProperties.batchSize()).thenReturn(25);

    outboxScheduler.publishOutboxEvents();

    verify(outboxEventService).publishNextBatch(25);
  }

  @Test
  void shouldCatchAndSwallowPublishingExceptions() {
    when(outboxPublisherProperties.batchSize()).thenReturn(10);
    doThrow(new RuntimeException("boom")).when(outboxEventService).publishNextBatch(10);

    assertThatCode(() -> outboxScheduler.publishOutboxEvents()).doesNotThrowAnyException();

    verify(outboxEventService).publishNextBatch(10);
  }
}

