package org.example.fraud.exception;

public class IdempotencyConflictException extends RuntimeException implements CodedException {
  public IdempotencyConflictException() {
    super(
        "Idempotency key conflict: existing has different currency, amount, ip or merchant reference");
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.IDEMPOTENCY_CONFLICT;
  }
}
