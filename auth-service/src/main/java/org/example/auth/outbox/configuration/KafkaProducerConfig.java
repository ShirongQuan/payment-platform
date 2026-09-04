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
    KafkaTemplate<UUID, Map<String, Object>> kafkaTemplate =
        new KafkaTemplate<>(outboxEventProducerFactory);
    // spring.kafka.template.observation-enabled only applies to Spring Boot's auto-configured
    // KafkaTemplate bean. Since this KafkaTemplate is constructed manually, observation must be
    // enabled explicitly here, otherwise the outbox publish is never wrapped in a producer span
    // and no traceparent header is injected into the record - causing ledger-service's consumer
    // to start a brand-new, disconnected trace instead of continuing the auth-service trace.
    kafkaTemplate.setObservationEnabled(true);
    return kafkaTemplate;
  }
}
