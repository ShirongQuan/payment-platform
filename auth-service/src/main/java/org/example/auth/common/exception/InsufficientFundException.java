package org.example.auth.common.exception;

import java.math.BigDecimal;
import java.util.Locale;
import lombok.Getter;
import org.example.auth.common.OperationType;

/**
 * Thrown when a reserve or debit operation exceeds the account's available balance. Maps to HTTP
 * 400.
 */
@Getter
public class InsufficientFundException extends RuntimeException implements CodedException {

  private final String operation;
  private final BigDecimal availableAmount;
  private final BigDecimal requestedAmount;

  public InsufficientFundException(
      OperationType operation, BigDecimal availableAmount, BigDecimal requestedAmount) {
    super(
        String.format(
            "Insufficient funds. Cannot %s. Available %s, requested %s",
            operation, availableAmount, requestedAmount));
    this.operation = operation.toString().toLowerCase(Locale.ROOT);
    this.availableAmount = availableAmount;
    this.requestedAmount = requestedAmount;
  }

  @Override
  public ErrorCode getErrorCode() {
    return ErrorCode.INSUFFICIENT_FUNDS;
  }
}
