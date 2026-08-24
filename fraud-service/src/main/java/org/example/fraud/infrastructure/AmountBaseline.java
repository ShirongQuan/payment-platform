package org.example.fraud.infrastructure;

import java.math.BigDecimal;

/**
 * JPA projection for the aggregate query in {@link
 * FraudEvaluationRepository#findAmountBaseline}, used by {@link
 * org.example.fraud.component.AmountDeviationRule} to establish an account's historical spending
 * baseline.
 */
public interface AmountBaseline {

  /** Average approved transaction amount over the configured window, or {@code null} if none. */
  BigDecimal getAvgAmount();

  /** Number of approved transactions the average was computed over. */
  long getTxnCount();
}
