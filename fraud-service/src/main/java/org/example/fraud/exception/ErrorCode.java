package org.example.fraud.exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
  IDEMPOTENCY_CONFLICT("Idempotency key conflict"),
  FRAUD_EVALUATION_IN_PROGRESS("Fraud evaluation in progress");

  private final String defaultMessage;

  ErrorCode(String defaultMessage) {
    this.defaultMessage = defaultMessage;
  }

}
