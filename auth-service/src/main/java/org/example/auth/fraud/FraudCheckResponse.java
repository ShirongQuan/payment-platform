package org.example.auth.fraud;

import java.util.List;

// TODO how to handle fraud check pending response?
public record FraudCheckResponse(
    String decision, // APPROVE / DECLINE
    int riskScore,
    List<RuleResult> reasons) {}
