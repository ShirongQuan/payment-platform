package org.example.auth.fraud;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Warms up the auth-service -&gt; fraud-service call path at startup, to avoid the very first
 * real authorisation request racing a cold JVM/connection-pool state against the aggressive
 * {@code resilience4j.timelimiter.instances.fraudService.timeoutDuration} (250ms) budget.
 *
 * <p>Without this, the first fraud check after a (re)start commonly spends 150-250ms just on:
 * spinning up the {@code fraudMdcExecutor}'s pool threads (never pre-started by default), JIT
 * compiling/class-loading the {@link RestClient}/{@code SimpleClientHttpRequestFactory}/Resilience4j
 * proxy machinery, and establishing the first TCP connection to fraud-service - on top of
 * fraud-service's own cold-start latency (first Hibernate/Redis queries, first Tomcat request).
 * That easily blows through 250ms and produces a spurious {@code FRAUD_SERVICE_UNAVAILABLE}
 * decline for what would otherwise be a perfectly healthy call.
 *
 * <p>The warm-up call deliberately goes straight through {@link #fraudRestClient} to fraud-service's
 * {@code /actuator/health} endpoint - NOT through {@link ResilientFraudGateway#checkAsync}, so it
 * doesn't count as a call/failure against the {@code fraudService} circuit breaker's sliding
 * window. Hitting {@code /actuator/health} (with {@code show-details: always} on fraud-service)
 * has the added benefit of also warming fraud-service's own DB and Redis health-check connections.
 *
 * <p>Because service startup order isn't guaranteed (e.g. docker-compose/local dev may bring
 * auth-service up before fraud-service is accepting connections), a single warm-up attempt isn't
 * enough - if it fires before fraud-service is listening, it fails once and never runs again,
 * leaving the connection pool cold for whenever the first real request eventually arrives. The
 * warm-up therefore retries with a fixed delay until it succeeds or a bounded number of attempts
 * is exhausted.
 */
@Slf4j
@Component
public class FraudServiceWarmup {

  private static final int MAX_ATTEMPTS = 20;
  private static final Duration RETRY_DELAY = Duration.ofSeconds(3);

  private final RestClient fraudRestClient;
  private final ThreadPoolTaskExecutor fraudMdcThreadPoolTaskExecutor;

  public FraudServiceWarmup(
      RestClient fraudRestClient,
      @Qualifier("fraudMdcThreadPoolTaskExecutor")
          ThreadPoolTaskExecutor fraudMdcThreadPoolTaskExecutor) {
    this.fraudRestClient = fraudRestClient;
    this.fraudMdcThreadPoolTaskExecutor = fraudMdcThreadPoolTaskExecutor;
  }

  /**
   * Fires once the application context is fully started (including Tomcat listening on its
   * port), off the main startup thread, so a slow/unavailable fraud-service never delays auth-
   * service's own readiness.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void warmUp() {
    // Spring calls ThreadPoolTaskExecutor#initialize() during normal bean creation, before this
    // listener runs, so the underlying java.util.concurrent.ThreadPoolExecutor is guaranteed to
    // exist by now.
    fraudMdcThreadPoolTaskExecutor.getThreadPoolExecutor().prestartAllCoreThreads();

    fraudMdcThreadPoolTaskExecutor.execute(this::warmUpWithRetry);
  }

  /**
   * Retries the warm-up call with a fixed delay (up to {@link #MAX_ATTEMPTS} attempts, ~1 minute
   * total budget) until fraud-service answers. Runs entirely on a pooled thread, so blocking
   * sleeps here never affect request-handling threads or auth-service's own startup/readiness.
   */
  private void warmUpWithRetry() {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        fraudRestClient.get().uri("/actuator/health").retrieve().toBodilessEntity();
        log.info("Fraud service warm-up call succeeded, attempt={}", attempt);
        return;
      } catch (Exception ex) {
        // Best-effort only - fraud-service may simply not be up yet (e.g. parallel startup in
        // docker-compose/local dev). Real requests still go through the normal
        // resilience4j-guarded path and will retry/fallback as usual regardless of whether the
        // warm-up ever succeeds.
        log.warn(
            "Fraud service warm-up call failed, attempt={}/{}, errorType={}, errorMessage={}",
            attempt,
            MAX_ATTEMPTS,
            ex.getClass().getSimpleName(),
            ex.getMessage());
      }

      if (attempt < MAX_ATTEMPTS) {
        try {
          Thread.sleep(RETRY_DELAY.toMillis());
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          log.warn("Fraud service warm-up retry loop interrupted, attempt={}", attempt);
          return;
        }
      }
    }

    log.warn(
        "Fraud service warm-up exhausted all {} attempts without success; the first real request"
            + " may still pay the cold-start cost",
        MAX_ATTEMPTS);
  }
}

