package org.example.ledger;

import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConsumerConfig {
  @Bean
  public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<UUID, String> kafkaTemplate) {
    DeadLetterPublishingRecoverer recoverer =
        new DeadLetterPublishingRecoverer(
            kafkaTemplate,
            (ConsumerRecord<?, ?> record, Exception ex) ->
                new TopicPartition("(auth.events.ledger.dlt", record.partition()));

    DefaultErrorHandler errorHandler =
        new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));

    errorHandler.addNotRetryableExceptions(IllegalArgumentException.class);
    return errorHandler;
  }
}
