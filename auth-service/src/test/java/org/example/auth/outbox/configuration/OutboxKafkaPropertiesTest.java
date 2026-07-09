package org.example.auth.outbox.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;
import org.junit.jupiter.api.Test;

class OutboxKafkaPropertiesTest {

  @Test
  void shouldResolveTopicByKey() {
    OutboxKafkaProperties properties =
        new OutboxKafkaProperties(
            List.of(
                new OutboxKafkaProperties.TopicSpec("outbox-events", "outboxEvents", 3, 1),
                new OutboxKafkaProperties.TopicSpec("outbox-dlq", "outboxEvents.dlq", 3, 1)));

    assertThat(properties.topicName("outbox-events")).isEqualTo("outboxEvents");
    assertThat(properties.topicName("outbox-dlq")).isEqualTo("outboxEvents.dlq");
  }

  @Test
  void shouldThrowWhenTopicKeyMissing() {
    OutboxKafkaProperties properties =
        new OutboxKafkaProperties(
            List.of(new OutboxKafkaProperties.TopicSpec("outbox-events", "outboxEvents", 3, 1)));

    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> properties.topicByKey("missing"))
        .withMessageContaining("Missing outbox topic key");
  }

  @Test
  void shouldThrowWhenTopicsListMissing() {
    assertThatExceptionOfType(NullPointerException.class)
        .isThrownBy(() -> new OutboxKafkaProperties(null))
        .withMessageContaining("Missing topics configuration");
  }
}

