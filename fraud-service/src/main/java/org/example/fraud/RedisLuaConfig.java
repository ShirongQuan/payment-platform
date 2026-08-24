package org.example.fraud;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Registers the Lua scripts executed atomically against Redis for velocity rate-limiting, so the
 * increment/trim/count sequence used by {@link
 * org.example.fraud.application.VelocityServiceRedisImpl} runs as a single server-side operation
 * without read-then-write races across concurrent requests.
 */
@Configuration
public class RedisLuaConfig {

  /** Sliding-window rate-limit script: {@code scripts/sliding_window_rate_limit.lua}. */
  @Bean
  public RedisScript<Long> slidingWindowScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/sliding_window_rate_limit.lua"));
    script.setResultType(Long.class);
    return script;
  }
}
