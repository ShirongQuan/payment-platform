package org.example.auth.common.exception;

public enum ErrorCode {
  ACCOUNT_NOT_FOUND("Account not found"),
  AUTHORISATION_NOT_FOUND("Authorisation not found"),
  INSUFFICIENT_FUNDS("Insufficient funds"),
  CURRENCY_MISMATCH("Currency mismatch"),
  INVALID_CURRENCY("Invalid currency"),
  IDEMPOTENCY_CONFLICT("Idempotency key conflict");

  private final String defaultMessage;

  ErrorCode(String defaultMessage) {
    this.defaultMessage = defaultMessage;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
