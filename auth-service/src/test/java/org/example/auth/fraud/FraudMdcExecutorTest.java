package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.example.shared.correlation.CorrelationIdConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class FraudMdcExecutorTest {

  private ThreadPoolTaskExecutor executor;

  @AfterEach
  void tearDown() {
    MDC.clear();
    if (executor != null) {
      executor.shutdown();
    }
  }

  @Test
  void shouldPropagateCorrelationIdIntoAsyncTask() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    executor = config.fraudMdcThreadPoolTaskExecutor();
    // Directly instantiating the bean-factory method bypasses Spring's lifecycle management, so
    // initialize() must be called manually here (Spring would normally call this automatically
    // via InitializingBean since fraudMdcThreadPoolTaskExecutor is registered as its own bean).
    executor.initialize();
    Executor bean = executor;

    String correlationId = "0264473d-9c23-40b5-a2fb-00591737bcfc";
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, correlationId);

    CompletableFuture<String> seenInWorker = new CompletableFuture<>();
    bean.execute(() -> seenInWorker.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)));

    assertThat(seenInWorker.get(2, TimeUnit.SECONDS)).isEqualTo(correlationId);
  }

  @Test
  void shouldNotLeakCorrelationIdAcrossTasks() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    executor = config.fraudMdcThreadPoolTaskExecutor();
    executor.initialize();
    Executor bean = executor;

    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "first-correlation-id");
    CompletableFuture<Void> first = new CompletableFuture<>();
    bean.execute(
        () -> {
          // Simulate accidental task mutation; decorator should restore worker context afterward.
          MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "mutated-in-worker");
          first.complete(null);
        });
    first.get(2, TimeUnit.SECONDS);

    MDC.remove(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
    CompletableFuture<String> secondSeen = new CompletableFuture<>();
    bean.execute(() -> secondSeen.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)));

    assertThat(secondSeen.get(2, TimeUnit.SECONDS)).isNull();
  }
}

