package org.example.shared.correlation;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class CorrelationIdAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  CorrelationIdFilter correlationIdFilter() {
    return new CorrelationIdFilter();
  }

  @Bean
  @ConditionalOnMissingBean
  CorrelationIdInterceptor correlationIdInterceptor() {
    return new CorrelationIdInterceptor();
  }

  @Bean
  @ConditionalOnMissingBean
  CorrelationIdResolver correlationIdResolver() {
    return new CorrelationIdResolver();
  }
}

