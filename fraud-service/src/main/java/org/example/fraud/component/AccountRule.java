package org.example.fraud.component;

import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.application.VelocityService;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.properties.AccountVelocityRuleProperties;
import org.springframework.stereotype.Component;

/**
 * Risk rule that flags an account issuing more than {@code threshold} requests within a
 * configured sliding time window, using {@link VelocityService} for the actual frequency count.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountRule implements RiskRule {

  private final AccountVelocityRuleProperties ruleProperties;
  private final VelocityService velocityService;

  @Override
  public String name() {
    return "ACCOUNT_VELOCITY_RULE";
  }

  @Override
  public Optional<RuleResult> evaluate(FraudCheckRequest fraudCheckRequest) {

    if (!(ruleProperties.enabled())) {
      return Optional.empty();
    }
    int riskScore = 0;
    if (velocityService.isTooFrequent(
        "account",
        fraudCheckRequest.accountId().toString(),
        ruleProperties.threshold(),
        Duration.ofSeconds(ruleProperties.windowSeconds()))) {

      riskScore += ruleProperties.score();
      log.debug(
          "Account velocity rule triggered, accountId={}, threshold={}, windowSeconds={}, score={}",
          fraudCheckRequest.accountId(),
          ruleProperties.threshold(),
          ruleProperties.windowSeconds(),
          riskScore);
      return Optional.of(
          new RuleResult(
              name(),
              riskScore,
              ruleProperties.reasonCode() + " in " + ruleProperties.windowSeconds() + "s"));
    } else {
      return Optional.empty();
    }
  }
}
