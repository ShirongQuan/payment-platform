package org.example.auth.idempotency;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.example.auth.common.OperationType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class IdempotencyService {
  private final StringRedisTemplate redis;
  private final ObjectMapper objectMapper;

  /**
   * @param scopeId the identifier scoping the idempotency key (e.g. accountId for authorise,
   *     authorisationId for capture/reverse).
   */
  public <T> Optional<T> get(
      OperationType operationType, UUID scopeId, String idempotencyKey, Class<T> type) {
    String key = buildKey(operationType, scopeId, idempotencyKey);
    String cached = redis.opsForValue().get(key);
    if (cached == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(objectMapper.readValue(cached, type));
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to deserialize idempotency payload", e);
    }
  }

  /**
   * @param scopeId the identifier scoping the idempotency key (e.g. accountId for authorise,
   *     authorisationId for capture/reverse).
   */
  public <T> void store(
      OperationType operationType, UUID scopeId, String idempotencyKey, T response, Duration ttl) {
    String key = buildKey(operationType, scopeId, idempotencyKey);

    try {
      String value = objectMapper.writeValueAsString(response);
      redis.opsForValue().set(key, value, ttl);
    } catch (JacksonException e) {
      throw new IllegalStateException("Failed to serialize idempotency payload", e);
    }
  }

  private String buildKey(OperationType operationType, UUID scopeId, String idempotencyKey) {
    return String.format(
        "%s:idempotency:%s:%s",
        operationType.name().toLowerCase(Locale.ROOT), scopeId, idempotencyKey);
  }
}
