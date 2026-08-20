package org.example.auth.fraud;

import java.util.List;

public record FraudCheckResponse(
    String decision, // APPROVE / DECLINE
    int riskScore,
    List<RuleResult> reasons) {}
