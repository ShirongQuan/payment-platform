package org.example.auth.outbox.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxPublisherPropertiesTest {

  @Test
  void shouldApplyDefaultsWhenValuesMissingOrInvalid() {
    OutboxPublisherProperties properties = new OutboxPublisherProperties(0, null, null);

    assertThat(properties.batchSize()).isEqualTo(10);
    assertThat(properties.delayMs()).isEqualTo(Duration.ofSeconds(1));
    assertThat(properties.claimLease()).isEqualTo(Duration.ofSeconds(30));
  }

  @Test
  void shouldKeepProvidedValuesWhenValid() {
    OutboxPublisherProperties properties =
        new OutboxPublisherProperties(20, Duration.ofSeconds(5), Duration.ofMinutes(2));

    assertThat(properties.batchSize()).isEqualTo(20);
    assertThat(properties.delayMs()).isEqualTo(Duration.ofSeconds(5));
    assertThat(properties.claimLease()).isEqualTo(Duration.ofMinutes(2));
  }
}

