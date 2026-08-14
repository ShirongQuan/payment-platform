package org.example.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import redis.embedded.RedisServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

class VelocityServiceRedisImplTest {

  private static RedisServer redisServer;
  private static LettuceConnectionFactory connectionFactory;
  private static VelocityService velocityService;
  private static boolean redisAvailable;

  @BeforeAll
  static void setUpRedis() throws IOException {
    try {
      int redisPort = 16379;
      redisServer = new RedisServer(redisPort);
      redisServer.start();

      connectionFactory = new LettuceConnectionFactory("127.0.0.1", redisPort);
      connectionFactory.afterPropertiesSet();

      StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
      redisTemplate.afterPropertiesSet();

      DefaultRedisScript<Long> script = new DefaultRedisScript<>();
      script.setScriptText(
          new String(
              Objects.requireNonNull(new ClassPathResource("scripts/sliding_window_rate_limit.lua").getInputStream())
                  .readAllBytes(),
              StandardCharsets.UTF_8));
      script.setResultType(Long.class);
      RedisScript<Long> redisScript = script;
      velocityService = new VelocityServiceRedisImpl(redisTemplate, redisScript);
      redisAvailable = true;
    } catch (IOException e) {
      redisAvailable = false;
    }
  }

  @AfterAll
  static void tearDownRedis() throws IOException {
    if (connectionFactory != null) {
      connectionFactory.destroy();
    }
    if (redisServer != null) {
      redisServer.stop();
    }
  }

  @Test
  void shouldReturnTrueOnlyAfterThresholdExceeded() {
    Assumptions.assumeTrue(redisAvailable, "Embedded redis not available in this environment");

    boolean first = velocityService.isTooFrequent("ip", "1.2.3.4", 2, Duration.ofSeconds(10));
    boolean second = velocityService.isTooFrequent("ip", "1.2.3.4", 2, Duration.ofSeconds(10));
    boolean third = velocityService.isTooFrequent("ip", "1.2.3.4", 2, Duration.ofSeconds(10));

    assertThat(first).isFalse();
    assertThat(second).isFalse();
    assertThat(third).isTrue();
  }
}
