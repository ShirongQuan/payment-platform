package org.example.auth.fraud;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
  }

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
