package org.example.auth.idempotency;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "idempotency")
public record IdempotencyProperties(Long ttlHours) {

  public IdempotencyProperties {
    ttlHours = Objects.requireNonNullElse(ttlHours, 24L);
    if (ttlHours <= 0) {
      throw new IllegalArgumentException("idempotency.ttl-hours must be greater than 0");
    }
  }

  public Duration ttl() {
    return Duration.ofHours(ttlHours);
  }
}

