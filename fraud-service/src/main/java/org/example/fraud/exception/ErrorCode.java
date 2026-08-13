package org.example.fraud.exception;

public enum ErrorCode {
  IDEMPOTENCY_CONFLICT("Idempotency key conflict");

  private final String defaultMessage;

  ErrorCode(String defaultMessage) {
    this.defaultMessage = defaultMessage;
  }

  public String getDefaultMessage() {
    return defaultMessage;
  }
}
