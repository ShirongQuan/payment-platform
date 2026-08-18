package org.example.auth.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;
import org.example.auth.authorisation.api.AuthorisationRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FraudOrchestratorTest {

  private static final String CLIENT_IP = "203.0.113.10";

  @Mock private FraudClient fraudClient;
  @Mock private FailOpenPolicy failOpenPolicy;

  private FraudOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    orchestrator = new FraudOrchestrator(fraudClient, failOpenPolicy);
  }

  @Test
  void shouldApproveWhenFraudUnavailableAndFailOpenAllows() {
    AuthorisationRequest request = request();
    when(fraudClient.check(any(FraudCheckRequest.class)))
        .thenReturn(FraudDecision.unavailable("FRAUD_TIMEOUT"));
    when(failOpenPolicy.allow(request.accountId(), request.amount())).thenReturn(true);

    FraudDecision decision =
        orchestrator.evaluate(request, request.currencyCode(), CLIENT_IP, UUID.randomUUID());

    assertThat(decision.outcome()).isEqualTo(FraudOutcome.APPROVE);
    assertThat(decision.reasons()).contains("FRAUD_SERVICE_UNAVAILABLE", "FRAUD_TIMEOUT");
    assertThat(decision.reasons()).contains("FRAUD_UNAVAILABLE_TRUSTED_TINY_AMOUNT");
    verify(failOpenPolicy).allow(request.accountId(), request.amount());
  }

  @Test
  void shouldDeclineWhenFraudUnavailableAndFailOpenDenies() {
    AuthorisationRequest request = request();
    when(fraudClient.check(any(FraudCheckRequest.class)))
        .thenReturn(FraudDecision.unavailable("FRAUD_TIMEOUT"));
    when(failOpenPolicy.allow(request.accountId(), request.amount())).thenReturn(false);

    FraudDecision decision =
        orchestrator.evaluate(request, request.currencyCode(), CLIENT_IP, UUID.randomUUID());

    assertThat(decision.outcome()).isEqualTo(FraudOutcome.DECLINE);
    assertThat(decision.reasons()).contains("FRAUD_SERVICE_UNAVAILABLE", "FRAUD_TIMEOUT");
  }

  @Test
  void shouldReturnRawDecisionWhenFraudRespondsNormally() {
    AuthorisationRequest request = request();
    FraudDecision raw = FraudDecision.approve(7, java.util.List.of("LOW_RISK"));
    when(fraudClient.check(any(FraudCheckRequest.class))).thenReturn(raw);

    FraudDecision decision =
        orchestrator.evaluate(request, request.currencyCode(), CLIENT_IP, UUID.randomUUID());

    assertThat(decision).isEqualTo(raw);
  }

  private static AuthorisationRequest request() {
    return new AuthorisationRequest(
        UUID.randomUUID(), "idem-key", new BigDecimal("2.50"), "USD", "merchant-1");
  }
}

