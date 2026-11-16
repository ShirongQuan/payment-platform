package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.example.shared.correlation.CorrelationIdConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class FraudMdcExecutorTest {

  private ExecutorService virtualThreadExecutor;

  @AfterEach
  void tearDown() {
    MDC.clear();
    if (virtualThreadExecutor != null) {
      virtualThreadExecutor.shutdown();
    }
  }

  @Test
  void shouldPropagateCorrelationIdIntoAsyncTask() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    virtualThreadExecutor = config.fraudMdcVirtualThreadExecutor();
    Executor bean = config.fraudMdcExecutor(virtualThreadExecutor);

    String correlationId = "0264473d-9c23-40b5-a2fb-00591737bcfc";
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, correlationId);

    CompletableFuture<String> seenInWorker = new CompletableFuture<>();
    bean.execute(() -> seenInWorker.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)));

    assertThat(seenInWorker.get(2, TimeUnit.SECONDS)).isEqualTo(correlationId);
  }

  @Test
  void shouldNotLeakCorrelationIdAcrossTasks() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    virtualThreadExecutor = config.fraudMdcVirtualThreadExecutor();
    Executor bean = config.fraudMdcExecutor(virtualThreadExecutor);

    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "first-correlation-id");
    CompletableFuture<Void> first = new CompletableFuture<>();
    bean.execute(
        () -> {
          // Simulate accidental task mutation. Since each task here runs on a brand-new virtual
          // thread (never reused, unlike a pooled platform thread), this mutation can never leak
          // into a later task's MDC regardless of ordering.
          MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "mutated-in-worker");
          first.complete(null);
        });
    first.get(2, TimeUnit.SECONDS);

    MDC.remove(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);
    CompletableFuture<String> secondSeen = new CompletableFuture<>();
    bean.execute(() -> secondSeen.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY)));

    assertThat(secondSeen.get(2, TimeUnit.SECONDS)).isNull();
  }

  @Test
  void shouldRunEachTaskOnItsOwnVirtualThread() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    virtualThreadExecutor = config.fraudMdcVirtualThreadExecutor();

    CompletableFuture<Thread> firstThread = new CompletableFuture<>();
    virtualThreadExecutor.execute(() -> firstThread.complete(Thread.currentThread()));
    CompletableFuture<Thread> secondThread = new CompletableFuture<>();
    virtualThreadExecutor.execute(() -> secondThread.complete(Thread.currentThread()));

    Thread first = firstThread.get(2, TimeUnit.SECONDS);
    Thread second = secondThread.get(2, TimeUnit.SECONDS);

    assertThat(first.isVirtual()).isTrue();
    assertThat(second.isVirtual()).isTrue();
    assertThat(first.getName()).startsWith("fraud-mdc-");
    assertThat(second.getName()).startsWith("fraud-mdc-");
    // Thread-per-task executor: no reuse across submissions.
    assertThat(first).isNotEqualTo(second);
  }
}

