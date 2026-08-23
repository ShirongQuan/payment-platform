package org.example.auth.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.example.auth.common.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {

  @Mock private StringRedisTemplate redis;
  @Mock private ValueOperations<String, String> valueOperations;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private record TestPayload(String value) {}

  @Test
  void get_shouldReturnEmpty_whenKeyNotFoundInRedis() {
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn(null);

    IdempotencyService service = new IdempotencyService(redis, objectMapper);
    Optional<TestPayload> result =
        service.get(OperationType.AUTHORISE, UUID.randomUUID(), "key-1", TestPayload.class);

    assertThat(result).isEmpty();
  }

  @Test
  void get_shouldBuildKey_fromOperationTypeScopeIdAndIdempotencyKey() {
    UUID scopeId = UUID.randomUUID();
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn(null);

    IdempotencyService service = new IdempotencyService(redis, objectMapper);
    service.get(OperationType.CAPTURE, scopeId, "capture-key-1", TestPayload.class);

    verify(valueOperations).get(eq("capture:idempotency:" + scopeId + ":capture-key-1"));
  }

  @Test
  void get_shouldUseSameKeyFormat_forReverseOperationType() {
    UUID scopeId = UUID.randomUUID();
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn(null);

    IdempotencyService service = new IdempotencyService(redis, objectMapper);
    service.get(OperationType.REVERSE, scopeId, "reverse-key-1", TestPayload.class);

    verify(valueOperations).get(eq("reverse:idempotency:" + scopeId + ":reverse-key-1"));
  }

  @Test
  void get_shouldDeserializeCachedJsonPayload() {
    UUID scopeId = UUID.randomUUID();
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn("{\"value\":\"hello\"}");

    IdempotencyService service = new IdempotencyService(redis, objectMapper);
    Optional<TestPayload> result =
        service.get(OperationType.AUTHORISE, scopeId, "key-1", TestPayload.class);

    assertThat(result).contains(new TestPayload("hello"));
  }

  @Test
  void get_shouldThrowIllegalState_whenCachedPayloadIsMalformedJson() {
    when(redis.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.get(anyString())).thenReturn("{not-valid-json");

    IdempotencyService service = new IdempotencyService(redis, objectMapper);

    assertThatThrownBy(
            () -> service.get(OperationType.AUTHORISE, UUID.randomUUID(), "key-1", TestPayload.class))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to deserialize idempotency payload")
        .hasCauseInstanceOf(JacksonException.class);
  }

  @Test
  void store_shouldSerializeValueAndSetWithTtl() {
    UUID scopeId = UUID.randomUUID();
    Duration ttl = Duration.ofHours(24);
    when(redis.opsForValue()).thenReturn(valueOperations);

    IdempotencyService service = new IdempotencyService(redis, objectMapper);
    service.store(OperationType.CAPTURE, scopeId, "capture-key-1", new TestPayload("hello"), ttl);

    verify(valueOperations)
        .set(
            eq("capture:idempotency:" + scopeId + ":capture-key-1"),
            eq("{\"value\":\"hello\"}"),
            eq(ttl));
  }

  @Test
  void store_shouldThrowIllegalState_whenSerializationFails() {
    ObjectMapper failingMapper = mock(ObjectMapper.class);
    when(failingMapper.writeValueAsString(any())).thenThrow(mock(JacksonException.class));

    IdempotencyService service = new IdempotencyService(redis, failingMapper);

    assertThatThrownBy(
            () ->
                service.store(
                    OperationType.AUTHORISE,
                    UUID.randomUUID(),
                    "key-1",
                    new TestPayload("hello"),
                    Duration.ofHours(24)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Failed to serialize idempotency payload")
        .hasCauseInstanceOf(JacksonException.class);

    verify(valueOperations, never()).set(any(), any(), any(Duration.class));
  }
}


