package org.example.authservice.common.exception;

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
