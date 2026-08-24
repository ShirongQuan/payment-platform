package org.example.fraud.application;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.component.RiskRule;
import org.example.fraud.domain.RiskReport;
import org.example.fraud.domain.RuleResult;
import org.springframework.stereotype.Service;

/**
 * Aggregates all configured {@link RiskRule} evaluations into a single {@link RiskReport}.
 *
 * <p>Each rule independently decides whether it applies/triggers for the given request; rules
 * that don't trigger return {@link Optional#empty()} and are excluded from the report. The total
 * score is the sum of all triggered rule scores, later compared against the decline threshold by
 * the caller.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RiskScoringEngine {
  private final List<RiskRule> rules;

  public RiskReport evaluate(FraudCheckRequest request) {
    List<RuleResult> ruleResultList =
        rules.stream().map(r -> r.evaluate(request)).flatMap(Optional::stream).toList();
    int totalScore = ruleResultList.stream().mapToInt(RuleResult::score).sum();

    log.debug(
        "Evaluated {} risk rules, accountId={}, triggeredRuleCount={}, totalScore={}",
        rules.size(),
        request.accountId(),
        ruleResultList.size(),
        totalScore);

    return (new RiskReport(totalScore, ruleResultList));
  }
}
