package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.example.shared.correlation.CorrelationIdConstants;
import org.example.shared.correlation.CorrelationIdScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class FraudMdcExecutorTest {

  private ExecutorService virtualThreadExecutor;

  /**
   * {@link ScopedValue#orElse} requires a non-null argument even when the value is bound (it
   * throws {@link NullPointerException} eagerly), so it cannot be used to mean "or null if
   * unbound". Use {@link ScopedValue#isBound()} instead to safely read the current value, or
   * {@code null} if it isn't bound.
   */
  private static String currentCorrelationId() {
    return CorrelationIdScope.CORRELATION_ID.isBound()
        ? CorrelationIdScope.CORRELATION_ID.get()
        : null;
  }

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
    Executor executor = config.fraudMdcExecutor(virtualThreadExecutor);

    String correlationId = "0264473d-9c23-40b5-a2fb-00591737bcfc";
    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, correlationId);

    CompletableFuture<String> seenViaMdc = new CompletableFuture<>();
    CompletableFuture<String> seenViaScopedValue = new CompletableFuture<>();

    executor.execute(() -> {
      seenViaScopedValue.complete(currentCorrelationId());
      seenViaMdc.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY));
    });
    // The ScopedValue is the source of truth for propagation...
    assertThat(seenViaScopedValue.get(2, TimeUnit.SECONDS)).isEqualTo(correlationId);
    // ...and it's bridged onto MDC too, so Logback's %X{correlationId} pattern still works.
    assertThat(seenViaMdc.get(2, TimeUnit.SECONDS)).isEqualTo(correlationId);
  }

  @Test
  void shouldNotLeakCorrelationIdAcrossTasks() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    virtualThreadExecutor = config.fraudMdcVirtualThreadExecutor();
    Executor executor = config.fraudMdcExecutor(virtualThreadExecutor);

    MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "first-correlation-id");
    CompletableFuture<Void> first = new CompletableFuture<>();
    executor.execute(() -> {
      // Simulate accidental task mutation. Since each task here runs on a brand-new virtual
      // thread (never reused, unlike a pooled platform thread), this mutation can never leak
      // into a later task's MDC regardless of ordering.
      MDC.put(CorrelationIdConstants.CORRELATION_ID_MDC_KEY, "mutated-in-worker");
      first.complete(null);
    });
    first.get(2, TimeUnit.SECONDS);

    MDC.remove(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);

    CompletableFuture<String> secondSeenViaScopedValue = new CompletableFuture<>();
    CompletableFuture<String> secondSeenViaMdc = new CompletableFuture<>();
    executor.execute(() -> {
      secondSeenViaScopedValue.complete(currentCorrelationId());
      secondSeenViaMdc.complete(MDC.get(CorrelationIdConstants.CORRELATION_ID_MDC_KEY));
    });
    assertThat(secondSeenViaScopedValue.get(2, TimeUnit.SECONDS)).isNull();
    assertThat(secondSeenViaMdc.get(2, TimeUnit.SECONDS)).isNull();
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

  @Test
  void shouldPropagateAbsenceOfCorrelationIdWhenNotSet() throws Exception {
    FraudHttpClientConfig config = new FraudHttpClientConfig();
    virtualThreadExecutor = config.fraudMdcVirtualThreadExecutor();
    Executor executor = config.fraudMdcExecutor(virtualThreadExecutor);

    MDC.remove(CorrelationIdConstants.CORRELATION_ID_MDC_KEY);

    CompletableFuture<String> seenViaScopedValue = new CompletableFuture<>();
    executor.execute(
        () -> seenViaScopedValue.complete(currentCorrelationId()));

    assertThat(seenViaScopedValue.get(2, TimeUnit.SECONDS)).isNull();
  }
}
