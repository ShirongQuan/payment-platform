package org.example.auth.outbox.configuration;

import java.util.Map;
import java.util.UUID;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

@Configuration
public class KafkaProducerConfig {

  @Bean
  public ProducerFactory<UUID, Map<String, Object>> outboxEventProducerFactory(
      KafkaProperties kafkaProperties) {
    return new DefaultKafkaProducerFactory<>(kafkaProperties.buildProducerProperties());
  }

  @Bean
  KafkaTemplate<UUID, Map<String, Object>> outboxEventKafkaTemplate(
      ProducerFactory<UUID, Map<String, Object>> outboxEventProducerFactory) {
    return new KafkaTemplate<>(outboxEventProducerFactory);
  }
}
