package org.example.fraud.failure;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Slf4j
public class FailureModeService {

  private final AtomicReference<FailureMode> mode = new AtomicReference<>(FailureMode.OFF);

  public FailureMode currentMode() {
    return mode.get();
  }

  public FailureMode setMode(FailureMode newMode) {
    mode.set(newMode == null ? FailureMode.OFF : newMode);
    log.warn("Fraud failure mode changed to {}", mode.get());
    return mode.get();
  }

  public void applyMockChaosIfNeeded() {
    FailureMode current = mode.get();

    switch (current) {
      case OFF -> {}
      case ALWAYS_500 ->
          throw new ResponseStatusException(
              HttpStatus.INTERNAL_SERVER_ERROR, "Injected failure mode ALWAYS_500");
      case ALWAYS_503 ->
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, "Injected failure mode ALWAYS_503");
      case DELAY_2S -> sleep(Duration.ofSeconds(2));
      case RANDOM_50_PERCENT -> {
        if (ThreadLocalRandom.current().nextBoolean()) {
          throw new ResponseStatusException(
              HttpStatus.SERVICE_UNAVAILABLE, "Injected failure mode RANDOM_50_PERCENT");
        }
      }
    }
  }

  private void sleep(Duration d) {
    try {
      Thread.sleep(d.toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ResponseStatusException(
          HttpStatus.INTERNAL_SERVER_ERROR, "Interrupted during artificial delay");
    }
  }
}
