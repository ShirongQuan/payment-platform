package org.example.fraud.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics for fraud-service, exported via Actuator's /actuator/prometheus
 * endpoint.
 *
 * <p>Counters/timers are pre-registered once at startup (see {@link Outcome}) rather than built
 * lazily per request, since fraud checks are a high-throughput, synchronous hot path called from
 * auth-service for every authorisation.
 */
@Component
public class FraudMetrics {

  public static final String FRAUD_DECISIONS_TOTAL = "fraud_decisions_total";
  public static final String FRAUD_CHECK_DURATION_SECONDS = "fraud_check_duration_seconds";
  public static final String OUTCOME_TAG = "outcome";

  /**
   * Terminal outcomes a {@code POST /fraud/check} request can produce, as observed by {@link
   * org.example.fraud.application.FraudServiceImpl}.
   */
  public enum Outcome {
    /** Risk score below the decline threshold (freshly evaluated, not a replay). */
    APPROVE("approve"),
    /** Risk score met or exceeded the decline threshold (freshly evaluated, not a replay). */
    DECLINE("decline"),
    /**
     * Duplicate (accountId, idempotencyKey) request whose content matches the original and whose
     * evaluation had already finished; the previously finalized decision was replayed as-is
     * without re-scoring. Mutually exclusive with APPROVE/DECLINE - a replay is counted here
     * instead, even though the underlying decision may itself be an approve or decline.
     */
    DUPLICATE("duplicate"),
    /** Duplicate (accountId, idempotencyKey) replay with different request content. */
    CONFLICT("conflict"),
    /** Duplicate request arrived while the original evaluation is still PENDING. */
    IN_PROGRESS("in_progress"),
    /** Any other unhandled failure while evaluating the request. */
    ERROR("error");

    private final String tagValue;

    Outcome(String tagValue) {
      this.tagValue = tagValue;
    }
  }

  private final MeterRegistry meterRegistry;

  // Outcome is a finite/low-cardinality enum, so counters and timers are pre-registered once at
  // startup and reused, rather than building/registering a Counter/Timer on every fraud check.
  private final Map<Outcome, Counter> decisionCounters = new EnumMap<>(Outcome.class);
  private final Map<Outcome, Timer> checkTimers = new EnumMap<>(Outcome.class);

  public FraudMetrics(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
    for (Outcome outcome : Outcome.values()) {
      decisionCounters.put(
          outcome,
          Counter.builder(FRAUD_DECISIONS_TOTAL)
              .description("Fraud check outcomes as returned to callers")
              .tag(OUTCOME_TAG, outcome.tagValue)
              .register(meterRegistry));
      checkTimers.put(
          outcome,
          Timer.builder(FRAUD_CHECK_DURATION_SECONDS)
              .description("Server-side latency of fraud checks, by outcome")
              // Aligned with auth-service's client-side auth_fraud_check_duration_seconds
              // buckets so server- vs. client-observed latency can be compared side by side.
              .serviceLevelObjectives(
                  Duration.ofMillis(10),
                  Duration.ofMillis(50),
                  Duration.ofMillis(100),
                  Duration.ofMillis(250),
                  Duration.ofMillis(500),
                  Duration.ofSeconds(1),
                  Duration.ofSeconds(2),
                  Duration.ofSeconds(5))
              .tag(OUTCOME_TAG, outcome.tagValue)
              .register(meterRegistry));
    }
  }

  /** Starts a latency sample for an in-flight fraud check; stop it via {@link #recordOutcome}. */
  public Timer.Sample startTimer() {
    return Timer.start(meterRegistry);
  }

  /** Increments the decision counter and records check latency for the given outcome. */
  public void recordOutcome(Outcome outcome, Timer.Sample sample) {
    decisionCounters.get(outcome).increment();
    sample.stop(checkTimers.get(outcome));
  }
}


