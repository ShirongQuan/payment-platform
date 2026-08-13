package org.example.fraud.application;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.component.RiskRule;
import org.example.fraud.domain.RiskReport;
import org.example.fraud.domain.RuleResult;
import org.springframework.stereotype.Service;

@RequiredArgsConstructor
@Service
public class RiskScoringEngine {
  private final List<RiskRule> rules;

  public RiskReport evaluate(FraudCheckRequest request) {
    List<RuleResult> ruleResultList =
        rules.stream().map(r -> r.evaluate(request)).flatMap(Optional::stream).toList();
    int totalScore = ruleResultList.stream().mapToInt(RuleResult::score).sum();

    return (new RiskReport(totalScore, ruleResultList));
  }
}
