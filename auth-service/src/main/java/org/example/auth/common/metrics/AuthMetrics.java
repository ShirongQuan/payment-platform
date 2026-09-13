package org.example.auth.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.example.auth.outbox.domain.OutboxEventStatus;
import org.example.auth.outbox.infrastructure.OutboxEventRepository;
import org.springframework.stereotype.Component;

/**
 * Custom business metrics for auth-service, exported via Actuator's /actuator/prometheus endpoint.
 */
@Component
public class AuthMetrics {

  private final MeterRegistry meterRegistry;

  // technical metric
  public static final String AUTH_REQUESTS_TOTAL = "auth_requests_total";

  // business metric
  public static final String AUTH_AUTHORISATIONS_TOTAL = "auth_authorisations_total";
  public static final String RESULT_TAG = "result";

  // fraud check metric
  public static final String AUTH_FRAUD_CHECK_DURATION_SECONDS =
      "auth_fraud_check_duration_seconds";

  // outbox metric
  public static final String AUTH_OUTBOX_PUBLISH_LAG_SECONDS = "auth_outbox_publish_lag_seconds";
  public static final String AUTH_OUTBOX_BACKLOG = "auth_outbox_backlog";
  public static final String AUTH_OUTBOX_PUBLISH_ATTEMPTS_TOTAL =
      "auth_outbox_publish_attempts_total";

  // idempotency cache metric
  public static final String AUTH_IDEMPOTENCY_CACHE_TOTAL = "auth_idempotency_cache_total";

  // concurrency conflict metric: type=idempotency_race (duplicate request, safely resolved to an
  // idempotent replay) and type=optimistic_lock (two distinct requests racing on the same
  // account's version - benign infra contention, mapped to a 409 retry-hint, not a replay)
  public static final String AUTH_CONCURRENCY_CONFLICT_TOTAL = "auth_concurrency_conflict_total";
  public static final String OPERATION_TAG = "operation";
  public static final String TYPE_TAG = "type";

  private static final List<OutboxEventStatus> BACKLOG_STATUSES =
      List.of(OutboxEventStatus.NEW, OutboxEventStatus.PUBLISHING);

  private final Counter successCounter;
  private final Counter failureCounter;
  private final Timer outboxPublishLagTimer;

  // idempotency cache counters
  private final Counter idempotencyCacheHitCounter;
  private final Counter idempotencyCacheMissCounter;
  private final Counter idempotencyCacheErrorCounter;

  // Outbox publish attempt outcome (success/retry/failed) is a finite, low-cardinality set known
  // upfront, so pre-registered eagerly in the constructor like the other startup counters above.
  private final Counter outboxPublishSuccessCounter;
  private final Counter outboxPublishRetryCounter;
  private final Counter outboxPublishFailedCounter;

  // Authorisation outcome (status, reason) is finite/low-cardinality, but throughput is high, so
  // counters are built/registered once per distinct combination and cached here rather than
  // re-built via Counter.builder(...).register(...) on every call.
  private final Map<AuthorisationOutcomeKey, Counter> authorisationOutcomeCounters =
      new ConcurrentHashMap<>();

  // Concurrency conflict counters (idempotency_race only for now), pre-registered per operation
  // (finite, known set) so all three series exist with a value of 0 from t=0.
  private final Counter authoriseIdempotencyRaceCounter;
  private final Counter captureIdempotencyRaceCounter;
  private final Counter reverseIdempotencyRaceCounter;

  // Concurrency conflict counters (optimistic_lock: same account, different idempotency key),
  // pre-registered per operation for the same reason as above.
  private final Counter authoriseOptimisticLockCounter;
  private final Counter captureOptimisticLockCounter;
  private final Counter reverseOptimisticLockCounter;

  public AuthMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
    this.meterRegistry = meterRegistry;
    // Pre-register both tag values at startup so both series exist with a value of 0
    // from t=0 (avoids "missing series" gaps in Grafana before the first failure/success occurs).
    this.successCounter =
        Counter.builder(AUTH_REQUESTS_TOTAL)
            .description("Total number of authorisation requests processed")
            .tag(RESULT_TAG, "success")
            .register(meterRegistry);
    this.failureCounter =
        Counter.builder(AUTH_REQUESTS_TOTAL)
            .description("Total number of authorisation requests processed")
            .tag(RESULT_TAG, "failure")
            .register(meterRegistry);

    // Explicit buckets so histogram_quantile(...) works in Prometheus. Range chosen around the
    // outbox scheduler's delay-ms (10s) and claim-lease (30s) from application.yml, so normal
    // publish lag (dominated by scheduler polling interval) and lease-expiry-driven reclaims are
    // both visible.
    this.outboxPublishLagTimer =
        Timer.builder(AUTH_OUTBOX_PUBLISH_LAG_SECONDS)
            .description("Time between outbox event creation and successful publish to Kafka")
            .serviceLevelObjectives(
                Duration.ofMillis(100),
                Duration.ofSeconds(1),
                Duration.ofSeconds(5),
                Duration.ofSeconds(10),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30),
                Duration.ofSeconds(60),
                Duration.ofSeconds(120))
            .register(meterRegistry);

    // Gauge: Micrometer calls this function lazily on every Prometheus scrape (no manual
    // increment/set needed) - it always reflects live DB state at scrape time.
    Gauge.builder(
            AUTH_OUTBOX_BACKLOG,
            outboxEventRepository,
            repo -> (double) repo.countByStatusIn(BACKLOG_STATUSES))
        .description(
            "Number of outbox events not yet successfully published (status NEW or PUBLISHING)")
        .register(meterRegistry);

    // Pre-register all three outcomes at startup so hit/miss/error series all exist from t=0.
    this.idempotencyCacheHitCounter =
        Counter.builder(AUTH_IDEMPOTENCY_CACHE_TOTAL)
            .description("Idempotency cache (Redis) lookup outcomes")
            .tag(RESULT_TAG, "hit")
            .register(meterRegistry);
    this.idempotencyCacheMissCounter =
        Counter.builder(AUTH_IDEMPOTENCY_CACHE_TOTAL)
            .description("Idempotency cache (Redis) lookup outcomes")
            .tag(RESULT_TAG, "miss")
            .register(meterRegistry);
    this.idempotencyCacheErrorCounter =
        Counter.builder(AUTH_IDEMPOTENCY_CACHE_TOTAL)
            .description("Idempotency cache (Redis) lookup outcomes")
            .tag(RESULT_TAG, "error")
            .register(meterRegistry);

    // Pre-register all three outcomes so success/retry/failed series all exist from t=0. Without
    // this, a run with only successful publishes would never surface a "failed"/"retry" series at
    // all, making it impossible to tell "zero failures" apart from "metric never emitted".
    this.outboxPublishSuccessCounter =
        Counter.builder(AUTH_OUTBOX_PUBLISH_ATTEMPTS_TOTAL)
            .description("Outbox publish attempt outcomes (success/retry/failed)")
            .tag(RESULT_TAG, "success")
            .register(meterRegistry);
    this.outboxPublishRetryCounter =
        Counter.builder(AUTH_OUTBOX_PUBLISH_ATTEMPTS_TOTAL)
            .description("Outbox publish attempt outcomes (success/retry/failed)")
            .tag(RESULT_TAG, "retry")
            .register(meterRegistry);
    this.outboxPublishFailedCounter =
        Counter.builder(AUTH_OUTBOX_PUBLISH_ATTEMPTS_TOTAL)
            .description("Outbox publish attempt outcomes (success/retry/failed)")
            .tag(RESULT_TAG, "failed")
            .register(meterRegistry);

    // Pre-register all three operations so authorise/capture/reverse series all exist from t=0.
    this.authoriseIdempotencyRaceCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "authorise")
            .tag(TYPE_TAG, "idempotency_race")
            .register(meterRegistry);
    this.captureIdempotencyRaceCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "capture")
            .tag(TYPE_TAG, "idempotency_race")
            .register(meterRegistry);
    this.reverseIdempotencyRaceCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "reverse")
            .tag(TYPE_TAG, "idempotency_race")
            .register(meterRegistry);

    this.authoriseOptimisticLockCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "authorise")
            .tag(TYPE_TAG, "optimistic_lock")
            .register(meterRegistry);
    this.captureOptimisticLockCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "capture")
            .tag(TYPE_TAG, "optimistic_lock")
            .register(meterRegistry);
    this.reverseOptimisticLockCounter =
        Counter.builder(AUTH_CONCURRENCY_CONFLICT_TOTAL)
            .description(
                "Concurrency conflicts detected and safely resolved during authorisation lifecycle operations")
            .tag(OPERATION_TAG, "reverse")
            .tag(TYPE_TAG, "optimistic_lock")
            .register(meterRegistry);
  }

  public void incrementSuccess() {
    successCounter.increment();
  }

  public void incrementFailure() {
    failureCounter.increment();
  }

  public void incrementAuthorisationOutcome(String status, String reason) {
    authorisationOutcomeCounters
        .computeIfAbsent(
            new AuthorisationOutcomeKey(status, reason),
            key ->
                Counter.builder(AUTH_AUTHORISATIONS_TOTAL)
                    .description("Authorisation outcomes by status and reason")
                    .tag("status", key.status())
                    .tag("reason", key.reason())
                    .register(meterRegistry))
        .increment();
  }

  /** Cache key for {@link #authorisationOutcomeCounters}; status/reason are both bounded enums. */
  private record AuthorisationOutcomeKey(String status, String reason) {}

  //  Note: unlike the fraud-check timer, this one has no extra tags (no per-event-type breakdown
  // needed unless you want it — see optional variant below), so it's safe to pre-register once as a
  // field, rather than building it lazily per call.
  /** Records the lag between outbox event creation and successful Kafka publish. */
  public void recordOutboxPublishLag(Duration lag) {
    outboxPublishLagTimer.record(lag);
  }

  public void incrementIdempotencyCacheHit() {
    idempotencyCacheHitCounter.increment();
  }

  public void incrementIdempotencyCacheMiss() {
    idempotencyCacheMissCounter.increment();
  }

  public void incrementIdempotencyCacheError() {
    idempotencyCacheErrorCounter.increment();
  }

  /** Records that an outbox event was successfully published to Kafka on this claim attempt. */
  public void incrementOutboxPublishSuccess() {
    outboxPublishSuccessCounter.increment();
  }

  /** Records that an outbox event failed to publish but will be retried (backoff not exhausted). */
  public void incrementOutboxPublishRetry() {
    outboxPublishRetryCounter.increment();
  }

  /** Records that an outbox event exhausted retries and was moved to the terminal FAILED state. */
  public void incrementOutboxPublishFailed() {
    outboxPublishFailedCounter.increment();
  }

  /** Records a concurrent duplicate (same idempotency key) request race resolved during authorise. */
  public void incrementAuthoriseIdempotencyRaceConflict() {
    authoriseIdempotencyRaceCounter.increment();
  }

  /** Records a concurrent duplicate (same idempotency key) request race resolved during capture. */
  public void incrementCaptureIdempotencyRaceConflict() {
    captureIdempotencyRaceCounter.increment();
  }

  /** Records a concurrent duplicate (same idempotency key) request race resolved during reverse. */
  public void incrementReverseIdempotencyRaceConflict() {
    reverseIdempotencyRaceCounter.increment();
  }

  /** Records a same-account optimistic-lock conflict (different idempotency keys) during authorise. */
  public void incrementAuthoriseOptimisticLockConflict() {
    authoriseOptimisticLockCounter.increment();
  }

  /** Records a same-account optimistic-lock conflict (different idempotency keys) during capture. */
  public void incrementCaptureOptimisticLockConflict() {
    captureOptimisticLockCounter.increment();
  }

  /** Records a same-account optimistic-lock conflict (different idempotency keys) during reverse. */
  public void incrementReverseOptimisticLockConflict() {
    reverseOptimisticLockCounter.increment();
  }
}
