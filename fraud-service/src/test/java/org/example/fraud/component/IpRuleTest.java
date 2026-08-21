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
import org.example.fraud.properties.IpVelocityRuleProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IpRuleTest {

  @Mock private VelocityService velocityService;

  @Test
  void shouldReturnEmptyWhenRuleDisabled() {
    IpRule rule = new IpRule(new IpVelocityRuleProperties(false, 2, 30, 40, "IP_VELOCITY"), velocityService);

    Optional<RuleResult> result = rule.evaluate(request());

    assertThat(result).isEmpty();
    verify(velocityService, never())
        .isTooFrequent("ip", "1.2.3.4", 2, Duration.ofSeconds(30));
  }

  @Test
  void shouldReturnRuleResultWhenVelocityExceeded() {
    IpRule rule = new IpRule(new IpVelocityRuleProperties(true, 2, 30, 40, "IP_VELOCITY"), velocityService);
    when(velocityService.isTooFrequent("ip", "1.2.3.4", 2, Duration.ofSeconds(30))).thenReturn(true);

    Optional<RuleResult> result = rule.evaluate(request());

    assertThat(result).isPresent();
    assertThat(result.get().ruleName()).isEqualTo("IP_VELOCITY_RULE");
    assertThat(result.get().score()).isEqualTo(40);
    assertThat(result.get().reason()).isEqualTo("IP_VELOCITY in 30s");
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
