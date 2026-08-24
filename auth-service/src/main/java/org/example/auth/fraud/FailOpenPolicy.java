package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Fallback policy applied when the fraud service is unavailable.
 *
 * <p>Rather than always declining when fraud checks can't be performed, a small, tightly-scoped
 * allow-list of trusted accounts may be permitted to proceed for amounts at or below a configured
 * ceiling. This trades a small amount of fraud risk for availability, and is intentionally
 * disabled by default (see {@link FailOpenProperties#enabled()}).
 */
@Slf4j
@Component
public class FailOpenPolicy {

  private final boolean enabled;
  private final BigDecimal maxAmount;
  private final Set<UUID> trustedAccountIds;

  public FailOpenPolicy(FailOpenProperties properties) {
    this.enabled = properties.enabled();
    this.maxAmount = properties.maxAmount();
    this.trustedAccountIds = parseTrustedAccountIds(properties.trustedAccountIds());
    log.debug(
        "Initialized fail-open policy, enabled={}, maxAmount={}, trustedAccountCount={}",
        enabled,
        maxAmount,
        trustedAccountIds.size());
  }

  /**
   * Decides whether to allow a request to proceed without a fraud decision.
   *
   * @return true only if fail-open is enabled, the account is on the trusted list, and the
   *     amount does not exceed the configured ceiling
   */
  public boolean allow(UUID accountId, BigDecimal amount) {
    if (!enabled || accountId == null || amount == null) {
      return false;
    }
    if (!trustedAccountIds.contains(accountId)) {
      return false;
    }
    return amount.compareTo(maxAmount) <= 0;
  }

  private Set<UUID> parseTrustedAccountIds(List<String> trustedAccountIds) {
    if (trustedAccountIds == null) {
      return Set.of();
    }
    return trustedAccountIds.stream()
        .map(String::trim)
        .filter(value -> !value.isEmpty())
        .map(this::parseUuidSafely)
        .flatMap(Optional::stream)
        .collect(Collectors.toUnmodifiableSet());
  }

  private java.util.Optional<UUID> parseUuidSafely(String rawValue) {
    try {
      return java.util.Optional.of(UUID.fromString(rawValue));
    } catch (IllegalArgumentException ex) {
      log.warn("Ignoring invalid trusted account id in fail-open config: {}", rawValue);
      return java.util.Optional.empty();
    }
  }
}
