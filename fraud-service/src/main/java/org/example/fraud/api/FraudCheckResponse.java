package org.example.fraud.api;

import java.util.List;
import org.example.fraud.domain.FraudDecision;
import org.example.fraud.domain.RuleResult;

/**
 * Finalized fraud check outcome: the decision, its numeric risk score, which rules fired, and
 * whether the caller should lock the account (see {@link
 * org.example.fraud.properties.AccountLockRuleProperties}). {@code lockAccountRecommended} is
 * always {@code false} (with a {@code null} reason code) for APPROVE decisions.
 */
public record FraudCheckResponse(
    FraudDecision decision,
    int riskScore,
    List<RuleResult> reasons,
    boolean lockAccountRecommended,
    String lockReasonCode)
    implements FraudCheckResult {}
