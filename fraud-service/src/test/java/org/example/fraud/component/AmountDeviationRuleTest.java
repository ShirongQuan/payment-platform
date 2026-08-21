package org.example.fraud.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.application.AmountDeviationRuleCacheService;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.infrastructure.AmountBaseline;
import org.example.fraud.infrastructure.FraudEvaluationRepository;
import org.example.fraud.properties.AmountDeviationRuleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AmountDeviationRuleTest {

  @Mock private FraudEvaluationRepository repository;
  @Mock private AmountDeviationRuleCacheService cacheService;

  @Test
  void shouldReturnEmptyWhenRuleDisabled() {
    AmountDeviationRuleProperties props =
        new AmountDeviationRuleProperties(false, 3, 30, 200, 5, 30, 25, "AMOUNT_DEVIATION");
    AmountDeviationRule rule = new AmountDeviationRule(props, repository, cacheService);
    FraudCheckRequest request = request(new BigDecimal("1200.00"));

    Optional<RuleResult> result = rule.evaluate(request);

    assertThat(result).isEmpty();
    verify(cacheService, never()).get(any(UUID.class));
    verify(repository, never()).findAmountBaseline(any(UUID.class), any(), anyInt());
  }

  @Test
  void shouldReturnEmptyWhenBaselineSamplesInsufficient() {
    AmountDeviationRuleProperties props =
        new AmountDeviationRuleProperties(true, 3, 30, 200, 5, 30, 25, "AMOUNT_DEVIATION");
    AmountDeviationRule rule = new AmountDeviationRule(props, repository, cacheService);
    FraudCheckRequest request = request(new BigDecimal("900.00"));
    when(cacheService.get(request.accountId())).thenReturn(null);

    AmountBaseline baseline = mock(AmountBaseline.class);
    when(baseline.getAvgAmount()).thenReturn(new BigDecimal("300.00"));
    when(baseline.getTxnCount()).thenReturn(2L);

    when(repository.findAmountBaseline(any(UUID.class), eq("APPROVE"), eq(30)))
        .thenReturn(baseline);

    Optional<RuleResult> result = rule.evaluate(request);

    assertThat(result).isEmpty();
    verify(cacheService, never()).put(request.accountId(), new BigDecimal("300.00"));
  }

  @Test
  void shouldTriggerRuleWhenAmountExceedsBothThresholdsUsingCachedBaseline() {
    AmountDeviationRuleProperties props =
        new AmountDeviationRuleProperties(true, 3, 30, 200, 5, 30, 25, "AMOUNT_DEVIATION");
    AmountDeviationRule rule = new AmountDeviationRule(props, repository, cacheService);
    FraudCheckRequest request = request(new BigDecimal("1200.00"));
    when(cacheService.get(request.accountId())).thenReturn(new BigDecimal("300.00"));

    Optional<RuleResult> result = rule.evaluate(request);

    assertThat(result).isPresent();
    assertThat(result.get().ruleName()).isEqualTo("AMOUNT_DEVIATION_RULE");
    assertThat(result.get().score()).isEqualTo(25);
    assertThat(result.get().reason()).isEqualTo("AMOUNT_DEVIATION");
    verify(repository, never()).findAmountBaseline(request.accountId(), "APPROVE", 30);
  }

  private FraudCheckRequest request(BigDecimal amount) {
    return new FraudCheckRequest(
        UUID.randomUUID(), "idem-key", amount, "GBP", "merchant-ref", "1.2.3.4");
  }
}
