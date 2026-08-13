package org.example.fraud.infrastructure;

import java.math.BigDecimal;

public interface AmountBaseline {

  BigDecimal getAvgAmount();

  long getTxnCount();
}
