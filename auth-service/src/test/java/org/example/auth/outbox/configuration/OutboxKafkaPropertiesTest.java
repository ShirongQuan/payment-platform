package org.example.auth.outbox.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OutboxKafkaPropertiesTest {

  @Test
  void shouldResolveTopicName() {
    OutboxKafkaProperties properties = new OutboxKafkaProperties("topicName");
    assertThat(properties.name()).isEqualTo("topicName");
  }
}
