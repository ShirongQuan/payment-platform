package org.example.fraud.component;

import java.math.BigDecimal;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.application.AmountDeviationRuleCacheService;
import org.example.fraud.domain.FraudDecision;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.infrastructure.AmountBaseline;
import org.example.fraud.infrastructure.FraudEvaluationRepository;
import org.example.fraud.properties.AmountDeviationRuleProperties;
import org.springframework.stereotype.Component;

/**
 * Risk rule that flags a transaction whose amount significantly exceeds the account's historical
 * average approved amount.
 *
 * <p>The average-amount baseline is read-through cached (Redis first, DB fallback via {@link
 * AmountDeviationRuleCacheService}) since it's derived from an expensive rolling-window
 * aggregate query and only needs to change when a new approved transaction occurs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AmountDeviationRule implements RiskRule {

  private final AmountDeviationRuleProperties ruleProperties;
  private final FraudEvaluationRepository fraudEvaluationRepository;
  private final AmountDeviationRuleCacheService amountDeviationRuleCacheService;

  @Override
  public String name() {
    return "AMOUNT_DEVIATION_RULE";
  }

  @Override
  public Optional<RuleResult> evaluate(FraudCheckRequest request) {

    if (!ruleProperties.enabled()) {
      return Optional.empty();
    }
    // check the average amount of the approved requests in the last 30 days from the cache
    log.debug("Checking cached average amount baseline for account {}", request.accountId());
    BigDecimal avgAmount = amountDeviationRuleCacheService.get(request.accountId());
    if (avgAmount == null) {
      // check from DB
      AmountBaseline baseline =
          fraudEvaluationRepository.findAmountBaseline(
              request.accountId(), FraudDecision.APPROVE.name(), ruleProperties.windowDays());
      avgAmount = baseline.getAvgAmount();

      if (avgAmount == null || baseline.getTxnCount() < ruleProperties.minSamples()) {
        log.debug(
            "Skip amount deviation rule for account {}, baseline average amount {}, transaction count {}",
            request.accountId(),
            avgAmount,
            baseline.getTxnCount());
        return Optional.empty();
      } else {
        // save the avgAmount to cache
        amountDeviationRuleCacheService.put(request.accountId(), avgAmount);
      }
    }
    if ((request.amount().compareTo(BigDecimal.valueOf(ruleProperties.amountReference())) > 0)
        && (request
                .amount()
                .compareTo(
                    avgAmount.multiply(BigDecimal.valueOf(ruleProperties.amountMultiplier())))
            > 0)) {
      log.debug(
          "Amount deviation rule triggered, accountId={}, amount={}, baselineAvgAmount={}, score={}",
          request.accountId(),
          request.amount(),
          avgAmount,
          ruleProperties.score());
      return Optional.of(
          new RuleResult(name(), ruleProperties.score(), ruleProperties.reasonCode()));
    } else {
      return Optional.empty();
    }
  }
}
