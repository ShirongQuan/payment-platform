package org.example.fraud.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.component.RiskRule;
import org.example.fraud.domain.RiskReport;
import org.example.fraud.domain.RuleResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RiskScoringEngineTest {

  @Mock private RiskRule ipRule;
  @Mock private RiskRule accountRule;
  @Mock private RiskRule amountRule;

  @Test
  void shouldCombineTriggeredRuleScores() {
    FraudCheckRequest request = request();
    when(ipRule.evaluate(request))
        .thenReturn(Optional.of(new RuleResult("IP_VELOCITY_RULE", 40, "IP_VELOCITY_EXCEEDED")));
    when(accountRule.evaluate(request))
        .thenReturn(Optional.of(new RuleResult("ACCOUNT_VELOCITY_RULE", 30, "ACCOUNT_VELOCITY_EXCEEDED")));
    when(amountRule.evaluate(request)).thenReturn(Optional.empty());

    RiskScoringEngine engine = new RiskScoringEngine(List.of(ipRule, accountRule, amountRule));

    RiskReport report = engine.evaluate(request);

    assertThat(report.totalScore()).isEqualTo(70);
    assertThat(report.triggeredRules()).hasSize(2);
  }

  private FraudCheckRequest request() {
    return new FraudCheckRequest(
        UUID.randomUUID(),
        "idem-key",
        new BigDecimal("10.00"),
        "GBP",
        "merchant-ref",
        "1.2.3.4");
  }
}
