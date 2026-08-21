package org.example.fraud.application;

import static org.example.fraud.domain.FraudDecision.APPROVE;
import static org.example.fraud.domain.FraudDecision.PENDING;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.shared.correlation.CorrelationIdResolver;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class FraudServiceImpl implements FraudService {

  private final FailureModeService failureModeService;
  private final RiskScoringEngine riskScoringEngine;
  private final ObjectMapper objectMapper;
  private final RiskProperties riskProperties;
  private final FraudEvaluationRepository fraudEvaluationRepository;

  private final AmountDeviationRuleCacheService amountDeviationRuleCacheService;
  private final AmountDeviationRuleProperties amountDeviationRuleProperties;
  private final CorrelationIdResolver correlationIdResolver;

  @Transactional
  @Override
  public FraudCheckResult check(FraudCheckRequest request) {
    // apply injected delay/failure for resilience demos
    failureModeService.applyMockChaosIfNeeded();

    // try to insert the fraud evaluation row
    UUID fraudEvaluationId = UUID.randomUUID();
    String canonical =
        RequestHashing.canonicalJoin(
            request.accountId() == null ? "" : request.accountId().toString(),
            request.amount().toPlainString(),
            Objects.toString(request.currencyCode(), ""),
            Objects.toString(request.merchantReference(), ""),
            Objects.toString(request.ipAddress(), ""));
    String requestHash = RequestHashing.sha256Hex(canonical);
    UUID correlationId = correlationIdResolver.resolveOrCreate();

    int insertPendingResult =
        fraudEvaluationRepository.tryInsertPending(
            fraudEvaluationId,
            request.accountId(),
            request.amount(),
            request.currencyCode(),
            request.merchantReference(),
            request.idempotencyKey(),
            requestHash,
            PENDING.toString(),
            riskProperties.rulesVersion(),
            "[]",
            request.ipAddress(),
            correlationId,
            OffsetDateTime.now());

    if (insertPendingResult == 0) {
      log.warn(
          "Resolving fraud evaluation idempotency after detecting duplicate account Id / idempotency key");
      return resolveIdempotencyAfterDuplicateFound(request, requestHash);
    }

    RiskReport riskReport = riskScoringEngine.evaluate(request);

    FraudDecision decision =
        riskReport.totalScore() >= riskProperties.declineThreshold()
            ? FraudDecision.DECLINE
            : FraudDecision.APPROVE;

    String ruleResultJson;
    try {
      ruleResultJson = objectMapper.writeValueAsString(riskReport.triggeredRules());
    } catch (Exception e) {
      throw new IllegalStateException("Failed to serialize rule results", e);
    }

    int finalizeResult =
        fraudEvaluationRepository.finalizeEvaluation(
            fraudEvaluationId, riskReport.totalScore(), decision.name(), ruleResultJson);

    if (finalizeResult == 0) {
      // Unlikely to happen
      throw new IllegalStateException(
          "Failed to finalize fraud evaluation: no PENDING row updated for evaluationId="
              + fraudEvaluationId
              + ", accountId="
              + request.accountId()
              + ", idempotencyKey="
              + request.idempotencyKey());
    }

    // Invalidate cache for amount deviation rule
    if (amountDeviationRuleProperties.enabled() && decision == APPROVE) {
      log.debug("Invalidate cache for amount deviation rule");
      amountDeviationRuleCacheService.invalidate(request.accountId());
    }
    return new FraudCheckResponse(decision, riskReport.totalScore(), riskReport.triggeredRules());
  }

  FraudCheckResult resolveIdempotencyAfterDuplicateFound(
      FraudCheckRequest request, String requestHash) {
    FraudEvaluationEntity existing =
        fraudEvaluationRepository
            .findByAccountIdAndIdempotencyKey(request.accountId(), request.idempotencyKey())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Duplicate account Id / idempotency key detected but no fraud evaluation found"));

    if (!existing.getRequestHash().equals(requestHash)) {
      throw new IdempotencyConflictException();
    } else if (existing.getDecision() == PENDING) {
      throw new FraudEvaluationInProgressException(existing.getId(), existing.getIdempotencyKey());
    }
    return toResponse(existing);
  }

  private FraudCheckResponse toResponse(FraudEvaluationEntity existing) {

    List<RuleResult> ruleResultList =
        existing.getRuleResult() == null
            ? List.of()
            : objectMapper.convertValue(existing.getRuleResult(), new TypeReference<>() {});

    return new FraudCheckResponse(existing.getDecision(), existing.getRiskScore(), ruleResultList);
  }


}
