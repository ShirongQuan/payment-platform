package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HttpFraudClientTest {

  @Mock private ResilientFraudGateway resilientFraudGateway;

  // Timer.start(...)/Timer.builder(...).register(...) call meterRegistry.config().clock(),
  // which a Mockito mock returns null for unless stubbed - use a real, lightweight in-memory
  // registry instead so the timer machinery actually works.
  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private HttpFraudClient client;

  @BeforeEach
  void setUp() {
    client = new HttpFraudClient(resilientFraudGateway, meterRegistry);
  }

  @Test
  void shouldReturnDecisionWhenGatewayReturnsNormally() {
    FraudDecision expected = FraudDecision.approve(12, List.of("LOW_RISK"));
    when(resilientFraudGateway.checkAsync(any(FraudCheckRequest.class)))
        .thenReturn(CompletableFuture.completedFuture(expected));

    FraudDecision decision = client.check(request());

    assertThat(decision).isEqualTo(expected);
  }

  @Test
  void shouldIncludeRootExceptionTypeWhenJoinFails() {
    when(resilientFraudGateway.checkAsync(any(FraudCheckRequest.class)))
        .thenReturn(CompletableFuture.failedFuture(new TimeoutException("timed out")));

    FraudDecision decision = client.check(request());

    assertThat(decision.outcome()).isEqualTo(FraudOutcome.UNAVAILABLE);
    assertThat(decision.reasons()).containsExactly("fraud_join_exception:TimeoutException");
  }

  private static FraudCheckRequest request() {
    return new FraudCheckRequest(
        UUID.randomUUID(), "idem-key", new BigDecimal("2.50"), "USD", "merchant-1", "203.0.113.10");
  }
}
