package org.example.shared.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot auto-configuration that wires up correlation ID propagation for any consuming
 * service.
 *
 * <p>Registers the three collaborating beans used to resolve, propagate, and surface a
 * correlation ID across a request's lifecycle: {@link CorrelationIdFilter} (inbound HTTP
 * requests), {@link CorrelationIdInterceptor} (outbound {@code RestTemplate} calls), and {@link
 * CorrelationIdResolver} (programmatic access, e.g. for non-HTTP entry points like Kafka
 * consumers). Each bean is only registered via {@link ConditionalOnMissingBean} so that a
 * consuming service can override any of them with its own implementation.
 */
@AutoConfiguration
public class CorrelationIdAutoConfiguration {

  /**
   * Registers the default {@link CorrelationIdFilter} that resolves/propagates a correlation ID
   * for inbound servlet requests.
   */
  @Bean
  @ConditionalOnMissingBean
  CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }

  /**
   * Registers the default {@link CorrelationIdInterceptor} that attaches the current correlation
   * ID to outbound {@code RestTemplate} requests.
   */
  @Bean
  @ConditionalOnMissingBean
  CorrelationIdInterceptor correlationIdInterceptor() {
    return new CorrelationIdInterceptor();
  }

  /**
   * Registers the default {@link CorrelationIdResolver} for programmatic correlation ID
   * resolution outside the servlet request lifecycle.
   */
  @Bean
  @ConditionalOnMissingBean
  CorrelationIdResolver correlationIdResolver() {
    return new CorrelationIdResolver();
  }
}

