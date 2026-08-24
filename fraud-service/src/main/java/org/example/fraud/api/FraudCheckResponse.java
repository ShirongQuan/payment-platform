package org.example.fraud.api;

import java.util.List;
import org.example.fraud.domain.FraudDecision;
import org.example.fraud.domain.RuleResult;

/** Finalized fraud check outcome: the decision, its numeric risk score, and which rules fired. */
public record FraudCheckResponse(FraudDecision decision, int riskScore, List<RuleResult> reasons)
    implements FraudCheckResult {}
