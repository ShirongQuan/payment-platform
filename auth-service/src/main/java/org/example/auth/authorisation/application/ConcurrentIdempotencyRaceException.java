package org.example.auth.authorisation.application;

/**
 * Signals that a concurrent request won the unique (account_id, idempotency_key) insert race.
 *
 * <p>This exception is intentionally unchecked so Spring marks the transaction for rollback.
 */
public class ConcurrentIdempotencyRaceException extends RuntimeException {
  public ConcurrentIdempotencyRaceException(Throwable cause) {
    super("Concurrent idempotency insert race", cause);
  }
}
