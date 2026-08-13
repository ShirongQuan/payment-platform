package org.example.fraud.application;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class VelocityServiceRedisImpl implements VelocityService {

  private final StringRedisTemplate redis;
  private final RedisScript<Long> slidingWindowScript;

  @Override
  public boolean isTooFrequent(String dimension, String name, int maxRequests, Duration window) {
    long nowMs = System.currentTimeMillis();
    long windowMs = window.toMillis();
    String key = "fraud:sw:" + dimension + ":" + name;
    String member = nowMs + "-" + UUID.randomUUID();
    long ttlSeconds = window.getSeconds() + 5;

    Long count =
        redis.execute(
            slidingWindowScript,
            Collections.singletonList(key),
            String.valueOf(nowMs),
            String.valueOf(windowMs),
            member,
            String.valueOf(ttlSeconds));

    return count != null && count > maxRequests;
  }

  // alternative java implementation
  // true => too frequent (more than maxRequests within window)
  //  public boolean isIpTooFrequent(String dimension, int maxRequests, Duration window) {
  //    long nowMs = System.currentTimeMillis();
  //    long windowStart = nowMs - window.toMillis();
  //    String key = "fraud:sw:" + dimension + ":" + ip;
  //
  //    // 1) remove old events outside window
  //    redis.opsForZSet().removeRangeByScore(key, 0, windowStart);
  //
  //    // 2) add current event
  //    String member = nowMs + "-" + UUID.randomUUID(); // unique member
  //    redis.opsForZSet().add(key, member, nowMs);
  //
  //    // 3) count events in current window
  //    Long count = redis.opsForZSet().zCard(key);
  //
  //    // 4) set TTL so inactive keys disappear
  //    redis.expire(key, window.plusSeconds(5));
  //
  //    return count != null && count > maxRequests;
  //  }
}
