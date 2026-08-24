package org.example.auth.common.exception;

/**
 * Thrown when a request reuses an idempotency key that was already used for a request with
 * different business parameters (amount/currency/merchant reference/etc). Maps to HTTP 409.
 */
public class IdempotencyConflictException extends RuntimeException implements CodedException {
  public IdempotencyConflictException() {
    super(
        "Idempotency key conflict: existing authorisation for this account has different amount, currencyCode, or merchantReference");
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.IDEMPOTENCY_CONFLICT;
  }
}
