package org.example.fraud.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.application.VelocityService;
import org.example.fraud.domain.RuleResult;
import org.example.fraud.properties.AccountVelocityRuleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountRuleTest {

  @Mock private VelocityService velocityService;

  @Test
  void shouldReturnEmptyWhenRuleDisabled() {
    FraudCheckRequest request = request();
    AccountVelocityRuleProperties props =
        new AccountVelocityRuleProperties(false, 3, 60, 30, "ACCOUNT_VELOCITY");
    AccountRule rule = new AccountRule(props, velocityService);

    Optional<RuleResult> result = rule.evaluate(request);

    assertThat(result).isEmpty();
    verify(velocityService, never())
        .isTooFrequent("account", request.accountId().toString(), 3, Duration.ofSeconds(60));
  }

  @Test
  void shouldUseAccountIdAsVelocityKeyAndReturnResultWhenExceeded() {
    FraudCheckRequest request = request();
    AccountVelocityRuleProperties props =
        new AccountVelocityRuleProperties(true, 3, 60, 30, "ACCOUNT_VELOCITY");
    AccountRule rule = new AccountRule(props, velocityService);
    when(velocityService.isTooFrequent("account", request.accountId().toString(), 3, Duration.ofSeconds(60)))
        .thenReturn(true);

    Optional<RuleResult> result = rule.evaluate(request);

    assertThat(result).isPresent();
    assertThat(result.get().ruleName()).isEqualTo("ACCOUNT_VELOCITY_RULE");
    assertThat(result.get().score()).isEqualTo(30);
    assertThat(result.get().reason()).isEqualTo("ACCOUNT_VELOCITY in 60s");
    verify(velocityService)
        .isTooFrequent("account", request.accountId().toString(), 3, Duration.ofSeconds(60));
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
