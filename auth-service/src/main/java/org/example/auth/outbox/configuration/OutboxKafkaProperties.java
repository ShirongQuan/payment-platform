package org.example.auth.outbox.configuration;

import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.kafka")
public record OutboxKafkaProperties(List<TopicSpec> topics) {

  public OutboxKafkaProperties {
    Objects.requireNonNull(topics, "Missing topics configuration");
  }

  public record TopicSpec(String key, String name, int partitions, int replicas) {}

  public TopicSpec topicByKey(String key) {
    Objects.requireNonNull(key, "key cannot be null");
    return topics.stream()
        .filter(t -> t.key.equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(STR."Missing outbox topic key \{key}"));
  }

  public String topicName(String key) {
    return topicByKey(key).name();
  }
}
