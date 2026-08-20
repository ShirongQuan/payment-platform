package org.example.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.example.shared.idempotency.RequestHashing;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.api.FraudCheckResponse;
import org.example.fraud.api.FraudCheckResult;
import org.example.fraud.domain.FraudDecision;
import org.example.fraud.domain.RiskReport;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.exception.FraudEvaluationInProgressException;
import org.example.fraud.exception.IdempotencyConflictException;
import org.example.fraud.failure.FailureModeService;
import org.example.fraud.infrastructure.FraudEvaluationEntity;
import org.example.fraud.infrastructure.FraudEvaluationRepository;
import org.example.fraud.properties.AmountDeviationRuleProperties;
import org.example.fraud.properties.RiskProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class FraudServiceImplTest {

  @Mock private FailureModeService failureModeService;
  @Mock private RiskScoringEngine riskScoringEngine;
  @Mock private FraudEvaluationRepository fraudEvaluationRepository;
  @Mock private AmountDeviationRuleCacheService amountDeviationRuleCacheService;

  private FraudServiceImpl fraudService;

  @BeforeEach
  void setUp() {
    fraudService =
        new FraudServiceImpl(
            failureModeService,
            riskScoringEngine,
            new ObjectMapper(),
            new RiskProperties("v1.0", 70),
            fraudEvaluationRepository,
            amountDeviationRuleCacheService,
            new AmountDeviationRuleProperties(true, 3, 30, 200, 5, 30, 25, "AMOUNT_DEVIATION"));
  }

  @Test
  void shouldApproveWhenScoreIsBelowDeclineThreshold() {
    FraudCheckRequest request = request();
    when(fraudEvaluationRepository.tryInsertPending(
            any(UUID.class),
            any(UUID.class),
            any(BigDecimal.class),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(OffsetDateTime.class)))
        .thenReturn(1);
    when(riskScoringEngine.evaluate(request))
        .thenReturn(new RiskReport(40, List.of(new RuleResult("IP_VELOCITY_RULE", 40, "IP"))));
    when(fraudEvaluationRepository.finalizeEvaluation(
            any(UUID.class), anyInt(), anyString(), anyString()))
        .thenReturn(1);

    FraudCheckResult result = fraudService.check(request);

    assertThat(result).isInstanceOf(FraudCheckResponse.class);
    FraudCheckResponse response = (FraudCheckResponse) result;
    assertThat(response.decision()).isEqualTo(FraudDecision.APPROVE);
    assertThat(response.riskScore()).isEqualTo(40);
    verify(amountDeviationRuleCacheService).invalidate(request.accountId());
  }

  @Test
  void shouldDeclineWhenScoreReachesDeclineThreshold() {
    FraudCheckRequest request = request();
    when(fraudEvaluationRepository.tryInsertPending(
            any(UUID.class),
            any(UUID.class),
            any(BigDecimal.class),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(OffsetDateTime.class)))
        .thenReturn(1);
    when(riskScoringEngine.evaluate(request))
        .thenReturn(
            new RiskReport(
                70,
                List.of(
                    new RuleResult("IP_VELOCITY_RULE", 40, "IP"),
                    new RuleResult("ACCOUNT", 30, "ACC"))));
    when(fraudEvaluationRepository.finalizeEvaluation(
            any(UUID.class), anyInt(), anyString(), anyString()))
        .thenReturn(1);

    FraudCheckResult result = fraudService.check(request);

    assertThat(result).isInstanceOf(FraudCheckResponse.class);
    FraudCheckResponse response = (FraudCheckResponse) result;
    assertThat(response.decision()).isEqualTo(FraudDecision.DECLINE);
    assertThat(response.riskScore()).isEqualTo(70);
    verify(amountDeviationRuleCacheService, never()).invalidate(request.accountId());
  }

  @Test
  void shouldThrowInProgressWhenDuplicateHasSameHashAndPendingDecision() {
    FraudCheckRequest request = request();
    String requestHash = requestHash(request);
    FraudEvaluationEntity existing =
        new FraudEvaluationEntity(
            UUID.randomUUID(),
            request.accountId(),
            request.amount(),
            request.currencyCode(),
            request.merchantReference(),
            request.idempotencyKey(),
            requestHash,
            0,
            "v1.0",
            FraudDecision.PENDING,
            List.of(),
            request.ipAddress(),
            request.correlationId(),
            OffsetDateTime.now());

    when(fraudEvaluationRepository.tryInsertPending(
            any(UUID.class),
            any(UUID.class),
            any(BigDecimal.class),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(OffsetDateTime.class)))
        .thenReturn(0);
    when(fraudEvaluationRepository.findByAccountIdAndIdempotencyKey(
            request.accountId(), request.idempotencyKey()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> fraudService.check(request))
        .isInstanceOf(FraudEvaluationInProgressException.class)
        .hasMessageContaining(existing.getId().toString())
        .hasMessageContaining(request.idempotencyKey());
    verify(riskScoringEngine, never()).evaluate(any(FraudCheckRequest.class));
  }

  private String requestHash(FraudCheckRequest request) {
    String canonical =
        RequestHashing.canonicalJoin(
            request.accountId() == null ? null : request.accountId().toString(),
            request.amount() == null ? null : request.amount().toPlainString(),
            request.currencyCode(),
            request.merchantReference(),
            request.ipAddress());
    return RequestHashing.sha256Hex(canonical);
  }

  @Test
  void shouldReplayStoredResponseWhenDuplicateHasSameHashAndFinalDecision() {
    FraudCheckRequest request = request();
    String requestHash = requestHash(request);
    FraudEvaluationEntity existing =
        new FraudEvaluationEntity(
            UUID.randomUUID(),
            request.accountId(),
            request.amount(),
            request.currencyCode(),
            request.merchantReference(),
            request.idempotencyKey(),
            requestHash,
            40,
            "v1.0",
            FraudDecision.APPROVE,
            List.of(
                Map.of(
                    "ruleName", "IP_VELOCITY_RULE",
                    "score", 40,
                    "reason", "IP_VELOCITY_EXCEEDED in 30s")),
            request.ipAddress(),
            request.correlationId(),
            OffsetDateTime.now());

    when(fraudEvaluationRepository.tryInsertPending(
            any(UUID.class),
            any(UUID.class),
            any(BigDecimal.class),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(OffsetDateTime.class)))
        .thenReturn(0);
    when(fraudEvaluationRepository.findByAccountIdAndIdempotencyKey(
            request.accountId(), request.idempotencyKey()))
        .thenReturn(Optional.of(existing));

    FraudCheckResult result = fraudService.check(request);

    assertThat(result).isInstanceOf(FraudCheckResponse.class);
    FraudCheckResponse replay = (FraudCheckResponse) result;
    assertThat(replay.decision()).isEqualTo(FraudDecision.APPROVE);
    assertThat(replay.riskScore()).isEqualTo(40);
    assertThat(replay.reasons()).hasSize(1);
    assertThat(replay.reasons().get(0).ruleName()).isEqualTo("IP_VELOCITY_RULE");
    verify(riskScoringEngine, never()).evaluate(any(FraudCheckRequest.class));
  }

  @Test
  void shouldThrowConflictWhenDuplicateHasDifferentHash() {
    FraudCheckRequest request = request();
    FraudEvaluationEntity existing =
        new FraudEvaluationEntity(
            UUID.randomUUID(),
            request.accountId(),
            request.amount(),
            request.currencyCode(),
            request.merchantReference(),
            request.idempotencyKey(),
            "different-hash",
            0,
            "v1.0",
            FraudDecision.PENDING,
            List.of(),
            request.ipAddress(),
            request.correlationId(),
            OffsetDateTime.now());

    when(fraudEvaluationRepository.tryInsertPending(
            any(UUID.class),
            any(UUID.class),
            any(BigDecimal.class),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            any(UUID.class),
            any(OffsetDateTime.class)))
        .thenReturn(0);
    when(fraudEvaluationRepository.findByAccountIdAndIdempotencyKey(
            request.accountId(), request.idempotencyKey()))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> fraudService.check(request))
        .isInstanceOf(IdempotencyConflictException.class);
    verify(riskScoringEngine, never()).evaluate(any(FraudCheckRequest.class));
  }

  private FraudCheckRequest request() {
    return new FraudCheckRequest(
        UUID.randomUUID(),
        "idem-key",
        new BigDecimal("10.00"),
        "GBP",
        "merchant-ref",
        "1.2.3.4",
        UUID.randomUUID());
  }
}
