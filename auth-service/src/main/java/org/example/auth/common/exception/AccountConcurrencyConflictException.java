package org.example.auth.common.exception;

/**
 * Thrown when a concurrent request racing on the same account's optimistic-lock {@code version}
 * loses (an {@link org.springframework.orm.ObjectOptimisticLockingFailureException}), for two
 * genuinely different, legitimate requests (different idempotency keys) contending on the same
 * account balance. Unlike {@link
 * org.example.auth.authorisation.application.ConcurrentIdempotencyRaceException} (a duplicate
 * request race, safely resolved to an idempotent replay), this is benign infra-level write
 * contention between two distinct requests with no safe replay to fall back to, so it is mapped
 * to HTTP 409 with a retry hint rather than silently retried server-side.
 *
 * <p>Known gap (tracked separately): automatic server-side retry (e.g. via a Resilience4j {@code
 * @Retry} decorator, mirroring {@code ResilientFraudGateway}) would resolve most of these
 * transparently before ever reaching the client; for now the client is expected to retry.
 */
public class AccountConcurrencyConflictException extends RuntimeException implements CodedException {
  public AccountConcurrencyConflictException(Throwable cause) {
    super(
        "Concurrent request on the same account conflicted with another in-flight request; please retry",
        cause);
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.ACCOUNT_CONCURRENCY_CONFLICT;
  }
}

