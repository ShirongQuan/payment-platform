package org.example.auth.fraud;

import static org.example.auth.common.metrics.AuthMetrics.AUTH_FRAUD_CHECK_DURATION_SECONDS;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class HttpFraudClient implements FraudClient {

  private final ResilientFraudGateway resilientFraudGateway;
  private final MeterRegistry meterRegistry;

  // Outcome is a finite enum (approved/declined/unavailable), so all three timers are
  // pre-registered once at startup and reused, rather than building/registering a Timer on every
  // fraud check (a hot path).
  private final Map<FraudOutcome, Timer> fraudCheckTimers = new EnumMap<>(FraudOutcome.class);

  public HttpFraudClient(ResilientFraudGateway resilientFraudGateway, MeterRegistry meterRegistry) {
    this.resilientFraudGateway = resilientFraudGateway;
    this.meterRegistry = meterRegistry;
    for (FraudOutcome outcome : FraudOutcome.values()) {
      fraudCheckTimers.put(outcome, buildTimer(outcome.name().toLowerCase(Locale.ROOT)));
    }
  }

  private Timer buildTimer(String outcome) {
    return Timer.builder(AUTH_FRAUD_CHECK_DURATION_SECONDS)
        .description(
            "Latency of fraud-service checks as observed by auth-service, including resilience4j timeout/circuit-breaker overhead")
        .tag("outcome", outcome)
        // Explicit buckets aligned with the otel-collector spanmetrics buckets
        // (infra/otel/otel-collector-config.yml) so dashboards are comparable.
        .serviceLevelObjectives(
            Duration.ofMillis(10),
            Duration.ofMillis(50),
            Duration.ofMillis(100),
            Duration.ofMillis(250),
            Duration.ofMillis(500),
            Duration.ofSeconds(1),
            Duration.ofSeconds(2),
            Duration.ofSeconds(5))
        .register(meterRegistry);
  }

  @Override
  public FraudDecision check(FraudCheckRequest request) {
    Timer.Sample sample = Timer.start(meterRegistry);
    FraudOutcome outcome = FraudOutcome.UNAVAILABLE;
    // synchronous facade for the service layer
    try {
      FraudDecision decision = resilientFraudGateway.checkAsync(request).join();
      outcome = decision.outcome();
      return decision;
    } catch (Exception ex) {
      Throwable root = rootCause(ex);
      log.warn(
          "Fraud check join failed, accountId={}, idempotencyKey={}, errorType={}, errorMessage={}",
          request.accountId(),
          request.idempotencyKey(),
          root.getClass().getSimpleName(),
          root.getMessage());
      return FraudDecision.unavailable("fraud_join_exception:" + root.getClass().getSimpleName());
    } finally {
      sample.stop(fraudCheckTimers.get(outcome));
    }
  }

  private static Throwable rootCause(Throwable throwable) {
    if (throwable instanceof CompletionException completionException
        && completionException.getCause() != null) {
      return completionException.getCause();
    }
    return throwable;
  }
}
