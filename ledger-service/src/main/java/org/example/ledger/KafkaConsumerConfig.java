package org.example.ledger;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
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
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<UUID, String> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (ConsumerRecord<?, ?> record, Exception ex) ->
                new TopicPartition("auth.events.ledger.dlt", record.partition()));

    // Retry 3 times with 2-second intervals before handing off to DLT.
    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));

    errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
    log.debug("Configured Kafka error handler, fixedBackOffMs=2000, maxRetries=3, dlt=auth.events.ledger.dlt");
    return errorHandler;
  }
}
