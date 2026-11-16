package org.example.auth.fraud;

import io.opentelemetry.context.Context;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.example.shared.correlation.CorrelationIdInterceptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
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
   * @CircuitBreaker}). Wraps {@link #fraudMdcVirtualThreadExecutor} with an MDC-propagating
   * decorator, then with {@link Context#taskWrapping} so the OpenTelemetry trace context (not
   * just MDC) is propagated onto the virtual thread running the task. Without this, the
   * RestClient call to fraud-service - which runs on this executor - finds no current span and
   * starts a brand-new, disconnected root trace instead of a child span of the incoming
   * /authorisations request.
   *
   * <p>Unlike a pooled {@code ThreadPoolTaskExecutor}, a plain {@link ExecutorService} backed by
   * virtual threads has no {@code TaskDecorator} hook, so the MDC capture/restore logic
   * previously configured via {@code setTaskDecorator} is applied manually here as a one-off
   * wrapping {@link Executor}, before layering the OpenTelemetry context wrapper on top.
   */
  @Bean(name = "fraudMdcExecutor")
  Executor fraudMdcExecutor(
      @Qualifier("fraudMdcVirtualThreadExecutor") ExecutorService fraudMdcVirtualThreadExecutor) {
    Executor mdcPropagatingExecutor =
        runnable -> {
          // Capture the submitting thread's MDC context (e.g. correlationId) at submission time.
          Map<String, String> callerMdc = MDC.getCopyOfContextMap();
          fraudMdcVirtualThreadExecutor.execute(
              () -> {
                // Swap in the caller's MDC for the duration of task execution. Virtual threads
                // are never reused across tasks, so there's no "previous" MDC state to restore
                // afterwards (unlike a pooled platform thread).
                if (callerMdc == null || callerMdc.isEmpty()) {
                  MDC.clear();
                } else {
                  MDC.setContextMap(callerMdc);
                }
                runnable.run();
              });
        };
    return Context.taskWrapping(mdcPropagatingExecutor);
  }

  /**
   * Raw executor backing {@link #fraudMdcExecutor}: an unbounded, thread-per-task {@link
   * ExecutorService} backed by JDK virtual threads rather than a fixed/bounded platform thread
   * pool. Since the only work submitted here is a blocking HTTP call to fraud-service, virtual
   * threads let each in-flight fraud check park cheaply on I/O without consuming a dedicated OS
   * thread - removing the need to size core/max pool/queue capacity (previously 4/16/1000) and
   * the pre-start warm-up workaround in {@link FraudServiceWarmup} (virtual thread creation is
   * cheap, so there is no pool to pre-start).
   *
   * <p>Registered as its own {@code @Bean} (rather than inlined into {@link #fraudMdcExecutor})
   * so Spring manages its lifecycle directly: {@code ExecutorService#shutdown()} is inferred and
   * invoked automatically when the application context closes.
   */
  @Bean(name = "fraudMdcVirtualThreadExecutor")
  ExecutorService fraudMdcVirtualThreadExecutor() {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("fraud-mdc-", 0).factory());
  }
}
