package org.example.authservice.common.exception;

import lombok.Getter;

/** Thrown when a supplied currency code is not a valid ISO 4217 code. Maps to HTTP 400. */
@Getter
public class InvalidCurrencyException extends RuntimeException implements CodedException {

  private final String currencyCode;

  public InvalidCurrencyException(String currencyCode) {
    super(String.format("Invalid currencyCode %s", currencyCode));
    this.currencyCode = currencyCode;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.INVALID_CURRENCY;
  }
}
