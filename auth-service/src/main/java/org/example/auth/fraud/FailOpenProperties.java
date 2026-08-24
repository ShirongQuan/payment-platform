package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for {@link FailOpenPolicy}, bound from the {@code fraud.fail-open} prefix.
 *
 * @param enabled whether fail-open is active at all
 * @param maxAmount the inclusive ceiling amount that may be allowed through when fraud is down
 * @param trustedAccountIds allow-list of account IDs (as strings) eligible for fail-open
 */
@ConfigurationProperties(prefix = "fraud.fail-open")
public record FailOpenProperties(
    boolean enabled, BigDecimal maxAmount, List<String> trustedAccountIds) {

  public FailOpenProperties {
    maxAmount = maxAmount == null ? new BigDecimal("0.00") : maxAmount;
    trustedAccountIds = trustedAccountIds == null ? List.of() : List.copyOf(trustedAccountIds);
  }
}
