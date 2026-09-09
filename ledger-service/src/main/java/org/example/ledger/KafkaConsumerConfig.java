package org.example.ledger;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.example.ledger.common.metrics.LedgerMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Configures Kafka listener error handling with bounded retries and dead-letter routing.
 *
 * <p>Retryable failures are retried with a fixed delay. Non-retryable failures are published
 * directly to the DLT.
 */
@Slf4j
@Configuration
public class KafkaConsumerConfig {

  private static final String DLT_TOPIC = "auth.events.ledger.dlt";

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<UUID, String> kafkaTemplate, LedgerMetrics ledgerMetrics) {
    // Route failed records to the same partition number on the ".dlt" topic so ordering
    // per-partition is loosely preserved and the failure can be traced back to its origin.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (ConsumerRecord<?, ?> record, Exception ex) -> {
              ledgerMetrics.incrementDltPublished(
                  record.topic(), DLT_TOPIC, rootCauseSimpleName(ex));
              return new TopicPartition(DLT_TOPIC, record.partition());
            });

    // Retry 3 times with 2-second intervals before handing off to DLT.
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));

    // Payload/contract errors (e.g. invalid JSON, missing headers) are deterministic and will
    // never succeed on retry, so send them straight to the DLT instead of wasting retry attempts.
    errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
    log.debug(
        "Configured Kafka error handler, fixedBackOffMs=2000, maxRetries=3, dlt={}", DLT_TOPIC);
    return errorHandler;
  }

  /**
   * Spring wraps the listener's real exception in a {@code ListenerExecutionFailedException}
   * before it reaches the recoverer, so unwrap to the deepest cause to get a meaningful,
   * low-cardinality label for the {@code exceptionClass} metric tag (e.g. {@code
   * IllegalArgumentException} rather than always {@code ListenerExecutionFailedException}).
   */
  private String rootCauseSimpleName(Throwable ex) {
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause.getClass().getSimpleName();
  }
}
