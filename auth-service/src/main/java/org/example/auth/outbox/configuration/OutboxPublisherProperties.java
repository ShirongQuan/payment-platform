package org.example.auth.outbox.configuration;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.publisher")
public record OutboxPublisherProperties(int batchSize, Duration delayMs, Duration claimLease) {

  public OutboxPublisherProperties {
    if (batchSize <= 0) {
      batchSize = 10;
    }
    delayMs = Objects.requireNonNullElse(delayMs, Duration.ofSeconds(1));
    claimLease = Objects.requireNonNullElse(claimLease, Duration.ofSeconds(30));
  }
}
