package org.example.auth.fraud;

import java.util.List;

/** Raw response body returned by the external fraud-service {@code /fraud/check} endpoint. */
public record FraudCheckResponse(
    String decision, // APPROVE / DECLINE
    int riskScore,
    List<RuleResult> reasons,
    boolean lockAccountRecommended,
    String lockReasonCode) {}
