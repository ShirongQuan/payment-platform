package org.example.fraud.component;

import java.util.Optional;
import org.example.fraud.api.FraudCheckRequest;
import org.example.fraud.domain.RuleResult;

/**
 * Common contract for all risk rules.
 *
 * <p>The engine depends only on this interface, which makes it easy to add new rules without
 * changing engine logic.
 */
public interface RiskRule {

  /** Human-readable / stable rule name. */
  String name();

  /** Evaluate the rule against the given fraudCheckRequest. */
  Optional<RuleResult> evaluate(FraudCheckRequest fraudCheckRequest);
}
