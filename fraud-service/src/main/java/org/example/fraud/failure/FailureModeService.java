package org.example.fraud.failure;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Toggleable chaos-injection service used to exercise resilience behavior (timeouts, circuit
 * breakers, retries) in upstream callers such as auth-service's fraud gateway.
 *
 * <p>Mode is held in a single in-memory {@link AtomicReference} (process-wide, not persisted) and
 * flipped via {@link org.example.fraud.failure.FailureModeController}; every fraud check calls
 * {@link #applyMockChaosIfNeeded()} before real evaluation logic runs.
 */
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

  /** Applies the currently configured failure mode, if any (throws or sleeps as configured). */
  public void applyMockChaosIfNeeded() {
    FailureMode current = mode.get();

    switch (current) {
      case OFF -> {}
      case ALWAYS_500 -> {
        log.warn("Injecting fraud failure mode ALWAYS_500");
        throw new ResponseStatusException(
            HttpStatus.INTERNAL_SERVER_ERROR, "Injected failure mode ALWAYS_500");
      }
      case ALWAYS_503 -> {
        log.warn("Injecting fraud failure mode ALWAYS_503");
        throw new ResponseStatusException(
            HttpStatus.SERVICE_UNAVAILABLE, "Injected failure mode ALWAYS_503");
      }
      case DELAY_2S -> {
        log.warn("Injecting fraud failure mode DELAY_2S");
        sleep(Duration.ofSeconds(2));
      }
      case RANDOM_50_PERCENT -> {
        if (ThreadLocalRandom.current().nextBoolean()) {
          log.warn("Injecting fraud failure mode RANDOM_50_PERCENT");
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
