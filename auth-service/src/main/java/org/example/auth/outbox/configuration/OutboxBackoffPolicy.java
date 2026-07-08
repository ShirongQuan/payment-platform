package org.example.auth.outbox.configuration;

import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;

@Component
public class OutboxBackoffPolicy {

  public OffsetDateTime nextAttempt(int nextRetryCount) {
    int seconds =
        switch (nextRetryCount) {
          case 1 -> 10;
          case 2 -> 30;
          case 3 -> 60;
          default -> 300;
        };
    return OffsetDateTime.now().plusSeconds(seconds);
  }

  public boolean shouldFail(int nextRetryCount) {
    return nextRetryCount >= 5;
  }
}
