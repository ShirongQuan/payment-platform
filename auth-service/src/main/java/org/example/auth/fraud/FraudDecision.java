package org.example.auth.fraud;

import java.util.List;

public record FraudDecision(FraudOutcome outcome, int riskScore, List<String> reasons) {
  public static FraudDecision approve(int riskScore, List<String> reasons) {
    return new FraudDecision(FraudOutcome.APPROVE, riskScore, reasons);
  }

  public static FraudDecision decline(int riskScore, List<String> reasons) {
    return new FraudDecision(FraudOutcome.DECLINE, riskScore, reasons);
  }

  public static FraudDecision unavailable(String reason) {
    return new FraudDecision(FraudOutcome.UNAVAILABLE, -1, List.of(reason));
  }

  public boolean isDeclined() {
    return outcome == FraudOutcome.DECLINE;
  }

  public boolean isUnavailable() {
    return outcome == FraudOutcome.UNAVAILABLE;
  }
}
