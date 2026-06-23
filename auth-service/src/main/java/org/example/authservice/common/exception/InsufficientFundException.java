package org.example.authservice.common.exception;

import java.math.BigDecimal;
import lombok.Getter;

/** Thrown when a reserve or debit operation exceeds the account's available balance. Maps to HTTP 400. */
@Getter
public class InsufficientFundException extends RuntimeException {

  private final BigDecimal availableAmount;
  private final BigDecimal requestedAmount;

  public InsufficientFundException(BigDecimal availableAmount, BigDecimal requestedAmount) {
    super(
        String.format(
            "Insufficient fund. Available %s, requested %s", availableAmount, requestedAmount));
    this.availableAmount = availableAmount;
    this.requestedAmount = requestedAmount;
  }
}
