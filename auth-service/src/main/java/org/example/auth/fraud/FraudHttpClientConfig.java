package org.example.auth.fraud;

import io.opentelemetry.context.Context;
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
      RestClient.Builder restClientBuilder,
      FraudGatewayProperties properties,
      CorrelationIdInterceptor correlationIdInterceptor) {
    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
    requestFactory.setConnectTimeout((int) properties.http().connectTimeout().toMillis());
    requestFactory.setReadTimeout((int) properties.http().readTimeout().toMillis());

    // Use the Spring Boot auto-configured RestClient.Builder (not a bare RestClient.builder())
    // so that the observation/tracing request interceptor Spring Boot wires in is preserved.
    // Building from RestClient.builder() directly would skip that instrumentation, meaning
    // outbound calls to fraud-service would carry no client span and no traceparent header,
    // causing fraud-service to start a brand-new, disconnected trace for every fraud check.
    return restClientBuilder
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .requestInterceptor(correlationIdInterceptor)
        .build();
  }

  /**
   * Dedicated executor for async fraud calls (used by {@code @TimeLimiter}/{@code
   * @CircuitBreaker}). Wraps {@link #fraudMdcThreadPoolTaskExecutor} with {@link
   * Context#taskWrapping} so the OpenTelemetry trace context (not just MDC) is propagated onto
   * the pooled thread. Without this, the RestClient call to fraud-service - which runs on this
   * executor - finds no current span and starts a brand-new, disconnected root trace instead of
   * a child span of the incoming /authorisations request.
   *
   * <p>Deliberately a thin wrapper around a separate {@link #fraudMdcThreadPoolTaskExecutor} bean
   * rather than wrapping a freshly-constructed executor inline: {@code Context.taskWrapping(...)}
   * returns a plain lambda-backed {@link Executor}, not a {@link ThreadPoolTaskExecutor}, so
   * Spring can't recognise it as a {@code DisposableBean}/{@code InitializingBean}. If the
   * underlying pool were created and wrapped in the same method, Spring would never call {@code
   * initialize()}/{@code shutdown()} on it, leaking the pool's threads across context restarts.
   * Registering the raw {@link ThreadPoolTaskExecutor} as its own bean keeps it under normal
   * Spring lifecycle management (auto-initialized, auto-shut-down on context close), while this
   * bean only adds the context-propagating wrapper on top.
   */
  @Bean(name = "fraudMdcExecutor")
  Executor fraudMdcExecutor(ThreadPoolTaskExecutor fraudMdcThreadPoolTaskExecutor) {
    return Context.taskWrapping(fraudMdcThreadPoolTaskExecutor);
  }

  /**
   * Raw thread pool backing {@link #fraudMdcExecutor}, propagating the calling thread's MDC
   * (notably the correlation id) onto the executor thread so log lines from the async call can
   * still be correlated back to the request.
   *
   * <p>Registered as its own {@code @Bean} (rather than built as a plain object inside {@link
   * #fraudMdcExecutor}) so Spring manages its lifecycle directly: {@code initialize()} is called
   * automatically via {@code InitializingBean} on startup, and {@code shutdown()} via {@code
   * DisposableBean} when the application context closes.
   */
  @Bean(name = "fraudMdcThreadPoolTaskExecutor")
  ThreadPoolTaskExecutor fraudMdcThreadPoolTaskExecutor() {
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
    // No explicit initialize()/shutdown() calls here - Spring calls both automatically since
    // this ThreadPoolTaskExecutor is registered as its own bean (InitializingBean/DisposableBean).
    return executor;
  }
}
