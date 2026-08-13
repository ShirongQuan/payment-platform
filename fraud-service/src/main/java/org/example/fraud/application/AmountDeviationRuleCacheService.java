package org.example.fraud.application;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.example.fraud.properties.AmountDeviationRuleProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AmountDeviationRuleCacheService {
  private final StringRedisTemplate redis;
  private final AmountDeviationRuleProperties ruleProperties;

  private static final String KEY_PREFIX = "fraud:baseline:acct:";

  private String buildKey(UUID accountId) {
    return KEY_PREFIX + accountId + ":" + ruleProperties.windowDays() + "d";
  }

  // get
  public BigDecimal get(UUID accountId) {
    String rawValue = redis.opsForValue().get(buildKey(accountId));
    return rawValue == null ? null : new BigDecimal(rawValue);
  }

  // set with TTL
  public void put(UUID accountId, BigDecimal value) {
    redis
        .opsForValue()
        .set(
            buildKey(accountId),
            value.toPlainString(),
            Duration.ofMinutes(ruleProperties.cacheTtlMinutes()));
  }

  // invalidate
  public void invalidate(UUID accountId) {
    redis.delete(buildKey(accountId));
  }
}
