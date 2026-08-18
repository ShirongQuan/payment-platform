package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "fraud.fail-open")
public record FailOpenProperties(
    boolean enabled, BigDecimal maxAmount, List<String> trustedAccountIds) {

  public FailOpenProperties {
    maxAmount = maxAmount == null ? new BigDecimal("0.00") : maxAmount;
    trustedAccountIds = trustedAccountIds == null ? List.of() : List.copyOf(trustedAccountIds);
  }
}
