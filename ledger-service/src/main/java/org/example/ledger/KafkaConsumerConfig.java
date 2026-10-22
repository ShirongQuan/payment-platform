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

  // Payload/contract errors (e.g. invalid JSON, missing headers, unknown enum values) are
  // deterministic and will never succeed on retry, so they're sent straight to the DLT instead
  // of wasting retry attempts. Kept as a single source of truth so the error handler
  // registration and the "is this non-retryable?" log check below can't drift apart.
  private static final Class<? extends Exception> NON_RETRYABLE_EXCEPTION =
      IllegalArgumentException.class;

  @Bean
  public DefaultErrorHandler kafkaErrorHandler(
      KafkaTemplate<UUID, String> kafkaTemplate, LedgerMetrics ledgerMetrics) {
    // Route failed records to the same partition number on the ".dlt" topic so ordering
    // per-partition is loosely preserved and the failure can be traced back to its origin.
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (ConsumerRecord<?, ?> record, Exception ex) -> {
              Throwable rootCause = rootCause(ex);
              String exceptionClass = rootCause.getClass().getSimpleName();
              if (NON_RETRYABLE_EXCEPTION.isInstance(rootCause)) {
                log.warn(
                    "Non-retryable exception, sending record directly to DLT without retrying,"
                        + " topic={}, partition={}, offset={}, key={}, dlt={}, exceptionClass={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    DLT_TOPIC,
                    exceptionClass,
                    rootCause);
              } else {
                log.warn(
                    "Retries exhausted, sending record to DLT, topic={}, partition={}, offset={},"
                        + " key={}, dlt={}, exceptionClass={}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    DLT_TOPIC,
                    exceptionClass,
                    rootCause);
              }
              ledgerMetrics.incrementDltPublished(record.topic(), DLT_TOPIC, exceptionClass);
              return new TopicPartition(DLT_TOPIC, record.partition());
            });

    // Retry 3 times with 2-second intervals before handing off to DLT.
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
    errorHandler.addNotRetryableExceptions(NON_RETRYABLE_EXCEPTION);

    // Logs every retry attempt (before the record is finally recovered/sent to the DLT), so it's
    // clear from the logs whether a failure is being retried or was recovered straight away.
    errorHandler.setRetryListeners(
        (record, ex, deliveryAttempt) ->
            log.warn(
                "Retrying Kafka record after failure, deliveryAttempt={}, topic={}, partition={},"
                    + " offset={}, key={}, exceptionClass={}",
                deliveryAttempt,
                record.topic(),
                record.partition(),
                record.offset(),
                record.key(),
                rootCause(ex).getClass().getSimpleName()));

    log.debug(
        "Configured Kafka error handler, fixedBackOffMs=2000, maxRetries=3, dlt={},"
            + " notRetryableExceptions={}",
        DLT_TOPIC,
        NON_RETRYABLE_EXCEPTION.getSimpleName());
    return errorHandler;
  }

  /**
   * Spring wraps the listener's real exception in a {@code ListenerExecutionFailedException}
   * before it reaches the recoverer/retry listener, so unwrap to the deepest cause to get a
   * meaningful, low-cardinality exception type (e.g. {@code IllegalArgumentException} rather than
   * always {@code ListenerExecutionFailedException}).
   */
  private Throwable rootCause(Throwable ex) {
    Throwable cause = ex;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }
}
