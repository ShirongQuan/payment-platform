package org.example.auth.outbox.application;

import lombok.extern.slf4j.Slf4j;
import org.example.auth.outbox.configuration.OutboxPublisherProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class OutboxScheduler {

  private final OutboxEventService outboxEventService;
  private final OutboxPublisherProperties outboxPublisherProperties;

  public OutboxScheduler(
      OutboxEventService outboxEventService, OutboxPublisherProperties outboxPublisherProperties) {
    this.outboxEventService = outboxEventService;
    this.outboxPublisherProperties = outboxPublisherProperties;
  }

  @Scheduled(fixedDelayString = "${outbox.publisher.delay-ms:1000ms}")
  public void publishOutboxEvents() {
    try {
      outboxEventService.publishNextBatch(outboxPublisherProperties.batchSize());
    } catch (Exception e) {
      log.error("Outbox events batch publish failed", e);
    }
  }
}
