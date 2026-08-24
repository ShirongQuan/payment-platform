package org.example.fraud.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fraud.properties.AmountDeviationRuleProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis cache for the per-account average-approved-amount baseline used by {@link
 * org.example.fraud.component.AmountDeviationRule}.
 *
 * <p>The baseline is expensive to compute (DB aggregate over a rolling window), so it's cached
 * with a short TTL and explicitly invalidated whenever a new APPROVE decision is recorded for the
 * account (see {@link FraudServiceImpl#check}), so subsequent checks recompute a fresh baseline.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AmountDeviationRuleCacheService {
  private final StringRedisTemplate redis;
  private final AmountDeviationRuleProperties ruleProperties;

  private static final String KEY_PREFIX = "fraud:baseline:acct:";

  private String buildKey(UUID accountId) {
    return KEY_PREFIX + accountId + ":" + ruleProperties.windowDays() + "d";
  }

  /** Reads the cached average-amount baseline for the account, or {@code null} on a cache miss. */
  public BigDecimal get(UUID accountId) {
    String rawValue = redis.opsForValue().get(buildKey(accountId));
    log.debug("Amount deviation baseline cache lookup, accountId={}, hit={}", accountId, rawValue != null);
    return rawValue == null ? null : new BigDecimal(rawValue);
  }

  /** Caches the computed average-amount baseline for the account with the configured TTL. */
  public void put(UUID accountId, BigDecimal value) {
    log.debug("Caching amount deviation baseline, accountId={}, value={}", accountId, value);
    redis
        .opsForValue()
        .set(
            buildKey(accountId),
            value.toPlainString(),
            Duration.ofMinutes(ruleProperties.cacheTtlMinutes()));
  }

  /** Evicts the cached baseline so the next check recomputes it from the database. */
  public void invalidate(UUID accountId) {
    log.debug("Invalidating amount deviation baseline cache, accountId={}", accountId);
    redis.delete(buildKey(accountId));
  }
}
