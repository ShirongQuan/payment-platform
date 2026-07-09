package org.example.auth.outbox.configuration;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

// create Kafka topics only when property set to true, e.g.: in local environment
@Configuration
@ConditionalOnProperty(name = "outbox.kafka.manage-topics", havingValue = "true")
public class KafkaTopicConfig {

  @Bean
  public KafkaAdmin.NewTopics outboxEventsTopic(OutboxKafkaProperties properties) {

    NewTopic[] topics =
        properties.topics().stream()
            .map(
                t ->
                    TopicBuilder.name(t.name())
                        .partitions(t.partitions())
                        .replicas(t.replicas())
                        .build())
            .toArray(NewTopic[]::new);

    return new KafkaAdmin.NewTopics(topics);
  }
}
