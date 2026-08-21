package org.example.auth.fraud;

import java.util.Map;
import java.util.concurrent.Executor;
import org.example.shared.correlation.CorrelationIdInterceptor;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.client.RestClient;

@Configuration
public class FraudHttpClientConfig {

  @Bean
  RestClient fraudRestClient(
      FraudGatewayProperties properties, CorrelationIdInterceptor correlationIdInterceptor) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.http().connectTimeout().toMillis());
    requestFactory.setReadTimeout((int) properties.http().readTimeout().toMillis());

    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .requestInterceptor(correlationIdInterceptor)
        .build();
  }

  @Bean(name = "fraudMdcExecutor")
  Executor fraudMdcExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("fraud-mdc-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(1000);
    executor.setTaskDecorator(
        runnable -> {
          Map<String, String> callerMdc = MDC.getCopyOfContextMap();
          return () -> {
            Map<String, String> previous = MDC.getCopyOfContextMap();
            try {
              if (callerMdc == null || callerMdc.isEmpty()) {
                MDC.clear();
              } else {
                MDC.setContextMap(callerMdc);
              }
              runnable.run();
            } finally {
              if (previous == null || previous.isEmpty()) {
                MDC.clear();
              } else {
                MDC.setContextMap(previous);
              }
            }
          };
        });
    executor.initialize();
    return executor;
  }
}


