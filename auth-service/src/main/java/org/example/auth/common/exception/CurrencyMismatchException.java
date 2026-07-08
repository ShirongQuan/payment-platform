package org.example.auth.common.exception;

import lombok.Getter;

/**
 * Thrown when a deposit or operation uses a currency different from the account's currency. Maps to
 * HTTP 400.
 */
@Getter
public class CurrencyMismatchException extends RuntimeException implements CodedException {

  private final String expected;
  private final String provided;

  public CurrencyMismatchException(String expected, String provided) {
    super(String.format("Currency mismatch, expected: %s, provided: %s", expected, provided));
    this.expected = expected;
    this.provided = provided;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.CURRENCY_MISMATCH;
  }
}
