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

/** Configures the HTTP client and dedicated executor used to call the external fraud service. */
@Configuration
public class FraudHttpClientConfig {

  /** Builds the {@link RestClient} used by {@link ResilientFraudGateway} to call the fraud service. */
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

  /**
   * Dedicated executor for async fraud calls (used by {@code @TimeLimiter}/{@code
   * @CircuitBreaker}). Propagates the calling thread's MDC (notably the correlation id) onto the
   * executor thread so log lines from the async call can still be correlated back to the request.
   */
  @Bean(name = "fraudMdcExecutor")
  Executor fraudMdcExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setThreadNamePrefix("fraud-mdc-");
    executor.setCorePoolSize(4);
    executor.setMaxPoolSize(16);
    executor.setQueueCapacity(1000);
    executor.setTaskDecorator(
        runnable -> {
          // Capture the submitting thread's MDC context (e.g. correlationId) at submission time.
          Map<String, String> callerMdc = MDC.getCopyOfContextMap();
          return () -> {
            // Swap in the caller's MDC for the duration of task execution, then restore
            // whatever MDC this pooled thread had before (since threads are reused).
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


